#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
[[ "$#" == "1" ]] || gcp_die "usage: verify-firewall.sh <staging|stable-demo>"
readonly environment="$1"
for command in gcloud jq; do gcp_require_command "${command}"; done
validate_plan
validate_google_identity

readonly config="$(environment_json "${environment}")"
readonly tag="$(jq -r '.network_tag' <<< "${config}")"
readonly runtime="$(runtime_email "${environment}")"
readonly vpc="$(plan_value '.network.vpc')"
readonly subnet="$(plan_value '.network.subnet_cidr')"
readonly iap_source="$(plan_value '.network.iap_source_range')"
readonly all_tags="$(jq -c '[.environments[].network_tag]' "${GCP_PLAN}")"

public_rule="$(gcloud_json compute firewall-rules describe crabit-public-https)"
jq -e --arg vpc "${vpc}" --argjson tags "${all_tags}" '
	.direction == "INGRESS"
	and .disabled != true
	and .sourceRanges == ["0.0.0.0/0"]
	and ((.sourceTags // []) | length == 0)
	and ((.sourceServiceAccounts // []) | length == 0)
	and (.network | endswith("/networks/" + $vpc))
	and ((.targetTags | sort) == ($tags | sort))
	and ((.targetServiceAccounts // []) | length == 0)
	and (.allowed | length) == 1
	and .allowed[0].IPProtocol == "tcp"
	and ((.allowed[0].ports | sort) == ["443","80"])
' <<< "${public_rule}" >/dev/null \
	|| gcp_die "public firewall read-back differs from exact TCP 80/443 ingress"

iap_rule="$(gcloud_json compute firewall-rules describe crabit-iap-ssh)"
jq -e --arg vpc "${vpc}" --arg source "${iap_source}" --argjson tags "${all_tags}" '
	.direction == "INGRESS"
	and .disabled != true
	and .sourceRanges == [$source]
	and ((.sourceTags // []) | length == 0)
	and ((.sourceServiceAccounts // []) | length == 0)
	and (.network | endswith("/networks/" + $vpc))
	and ((.targetTags | sort) == ($tags | sort))
	and ((.targetServiceAccounts // []) | length == 0)
	and (.allowed | length) == 1
	and .allowed[0] == {IPProtocol:"tcp",ports:["22"]}
' <<< "${iap_rule}" >/dev/null \
	|| gcp_die "IAP firewall read-back differs from exact TCP 22 ingress"

all_rules="$(gcloud_json compute firewall-rules list)"
jq -e \
	--arg tag "${tag}" \
	--arg runtime "${runtime}" \
	--arg vpc "${vpc}" \
	--arg subnet "${subnet}" \
	--arg iap "${iap_source}" '
	def port_bounds:
		split("-") | map(tonumber)
		| if length == 1 then [.[0], .[0]]
		  elif length == 2 and .[0] <= .[1] then .
		  else error("invalid firewall port range") end;
	def allows_port($port):
		.IPProtocol == "all"
		or (.IPProtocol == "tcp"
			and (((.ports // []) | length == 0)
				or any(.ports[]; port_bounds as $bounds | $bounds[0] <= $port and $port <= $bounds[1])));
	def applies_to($tag; $runtime):
		(((.targetTags // []) | length == 0) and ((.targetServiceAccounts // []) | length == 0))
		or ((.targetTags // []) | index($tag) != null)
		or ((.targetServiceAccounts // []) | index($runtime) != null);
	def exact_sources($expected):
		.sourceRanges == $expected
		and ((.sourceTags // []) | length == 0)
		and ((.sourceServiceAccounts // []) | length == 0);
	all(.[ ];
		((.network | endswith("/networks/" + $vpc)) | not)
		or .direction != "INGRESS"
		or .disabled == true
		or (applies_to($tag; $runtime) | not)
		or all(.allowed[]?;
			((allows_port(22) | not) or exact_sources([$iap]))
			and ((allows_port(8080) | not) or exact_sources([$subnet]))
			and ((allows_port(5432) | not) or exact_sources([$subnet]))
		)
	)
' <<< "${all_rules}" >/dev/null \
	|| gcp_die "effective ingress exposes TCP 22, 8080, or 5432 outside its approved source"

printf 'Google Cloud firewall verified: environment=%s public=80/443 iap=22 forbidden=22,8080,5432\n' \
	"${environment}"
