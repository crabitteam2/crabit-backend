#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
[[ "$#" == "1" ]] || gcp_die "usage: verify-environment.sh <staging|stable-demo>"
readonly environment="$1"
for command in gcloud jq; do gcp_require_command "${command}"; done
validate_plan
validate_google_identity
gcp_require_env CRABIT_PUBLIC_HOST

readonly config="$(environment_json "${environment}")"
readonly zone="$(plan_value '.location.zone')"
readonly region="$(plan_value '.location.region')"
readonly instance="$(jq -r '.instance' <<< "${config}")"
readonly disk="$(jq -r '.data_disk' <<< "${config}")"
readonly address="$(jq -r '.address' <<< "${config}")"
readonly tag="$(jq -r '.network_tag' <<< "${config}")"
readonly policy="$(jq -r '.snapshot_policy' <<< "${config}")"
readonly runtime="$(runtime_email "${environment}")"
readonly deployer="$(deployer_email "${environment}")"
readonly vpc="$(plan_value '.network.vpc')"

active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' --limit=1)"
[[ "${active_account}" == "${deployer}" ]] || gcp_die "active account is not the environment deployer service account"

address_json="$(gcloud_json compute addresses describe "${address}" --region "${region}")"
ip="$(jq -er 'select(.status == "IN_USE") | .address' <<< "${address_json}")" \
	|| gcp_die "reserved address is not in use"
expected_host="$(jq -r '.public_host_prefix' <<< "${config}").${ip//./-}.sslip.io"
[[ "${CRABIT_PUBLIC_HOST}" == "${expected_host}" ]] \
	|| gcp_die "CRABIT_PUBLIC_HOST does not match the reserved Google Cloud address"

instance_json="$(gcloud_json compute instances describe "${instance}" --zone "${zone}")"
jq -e --arg machine "$(jq -r '.machine_type' <<< "${config}")" --arg disk "${disk}" --arg runtime "${runtime}" --arg tag "${tag}" --arg vpc "${vpc}" '
	.status == "RUNNING"
	and (.machineType | endswith("/machineTypes/" + $machine))
	and any(.disks[]; .boot == false and .mode == "READ_WRITE" and .deviceName == "crabit-data" and (.source | endswith("/disks/" + $disk)))
	and any(.serviceAccounts[]; .email == $runtime)
	and (.tags.items | index($tag) != null)
	and all(.networkInterfaces[]; (.network | endswith("/networks/" + $vpc)))
	and any(.metadata.items[]; .key == "enable-oslogin" and .value == "TRUE")
	and any(.metadata.items[]; .key == "block-project-ssh-keys" and .value == "TRUE")
' <<< "${instance_json}" >/dev/null || gcp_die "instance read-back differs from the approved plan"

boot_disk="$(jq -r '.disks[] | select(.boot == true) | .source | split("/")[-1]' <<< "${instance_json}")"
boot_json="$(gcloud_json compute disks describe "${boot_disk}" --zone "${zone}")"
jq -e --argjson size "$(jq '.boot_disk_gb' <<< "${config}")" '
	.sizeGb == ($size | tostring) and (.type | endswith("/diskTypes/pd-balanced"))
' <<< "${boot_json}" >/dev/null || gcp_die "boot disk read-back differs from the approved plan"
data_json="$(gcloud_json compute disks describe "${disk}" --zone "${zone}")"
jq -e --argjson size "$(jq '.data_disk_gb' <<< "${config}")" --arg instance "${instance}" --arg policy "${policy}" '
	.sizeGb == ($size | tostring)
	and (.type | endswith("/diskTypes/pd-balanced"))
	and ([.users[]?] | length == 1)
	and (.users[0] | endswith("/instances/" + $instance))
	and any(.resourcePolicies[]; endswith("/resourcePolicies/" + $policy))
' <<< "${data_json}" >/dev/null || gcp_die "data disk single-writer read-back differs from the approved plan"

public_rule="$(gcloud_json compute firewall-rules describe crabit-public-https)"
jq -e --arg tag "${tag}" --arg vpc "${vpc}" '
	.direction == "INGRESS" and .disabled != true and .sourceRanges == ["0.0.0.0/0"]
	and (.network | endswith("/networks/" + $vpc))
	and (.targetTags | index($tag) != null)
	and ([.allowed[] | select(.IPProtocol == "tcp") | .ports[]] | sort) == ["443","80"]
' <<< "${public_rule}" >/dev/null || gcp_die "public firewall read-back differs from TCP 80/443 only"
iap_rule="$(gcloud_json compute firewall-rules describe crabit-iap-ssh)"
jq -e --arg tag "${tag}" --arg vpc "${vpc}" --arg source "$(plan_value '.network.iap_source_range')" '
	.direction == "INGRESS" and .disabled != true and .sourceRanges == [$source]
	and (.network | endswith("/networks/" + $vpc))
	and (.targetTags | index($tag) != null)
	and ([.allowed[] | select(.IPProtocol == "tcp") | .ports[]] == ["22"])
' <<< "${iap_rule}" >/dev/null || gcp_die "IAP firewall read-back differs from TCP 22 and the IAP source only"
all_rules="$(gcloud_json compute firewall-rules list)"
jq -e --arg tag "${tag}" --arg vpc "${vpc}" '
	all(.[];
		((.network | endswith("/networks/" + $vpc)) | not)
		or (.direction != "INGRESS" or .disabled == true)
		or ((.sourceRanges // []) | all(. != "0.0.0.0/0" and . != "::/0"))
		or (((.targetTags // []) | length > 0) and (((.targetTags // []) | index($tag)) == null))
		or all(.allowed[]?;
			.IPProtocol != "all"
			and (.ports != null)
			and all(.ports[]; . != "22" and . != "8080" and . != "5432")
		)
	)
' <<< "${all_rules}" >/dev/null || gcp_die "public TCP 22, 8080, or 5432 exposure exists for ${environment}"
printf 'Google Cloud environment verified: environment=%s instance=%s ip=%s single-writer=true\n' \
	"${environment}" "${instance}" "${ip}"
