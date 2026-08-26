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
readonly instance="$(jq -r '.instance' <<< "${config}")"
readonly disk="$(jq -r '.data_disk' <<< "${config}")"
readonly tag="$(jq -r '.network_tag' <<< "${config}")"
readonly policy="$(jq -r '.snapshot_policy' <<< "${config}")"
readonly runtime="$(runtime_email "${environment}")"
readonly deployer="$(deployer_email "${environment}")"
readonly vpc="$(plan_value '.network.vpc')"

active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' --limit=1)"
[[ "${active_account}" == "${deployer}" ]] || gcp_die "active account is not the environment deployer service account"

instance_json="$(gcloud_json compute instances describe "${instance}" --zone "${zone}")"
jq -e --arg machine "$(jq -r '.machine_type' <<< "${config}")" --arg disk "${disk}" --arg runtime "${runtime}" --arg tag "${tag}" --arg vpc "${vpc}" --arg instance "${instance}" --argjson boot_size "$(jq '.boot_disk_gb' <<< "${config}")" '
	.status == "RUNNING"
	and (.machineType | endswith("/machineTypes/" + $machine))
	and any(.disks[]; .boot == true and .diskSizeGb == ($boot_size | tostring) and (.source | endswith("/disks/" + $instance)))
	and any(.disks[]; .boot == false and .mode == "READ_WRITE" and .deviceName == "crabit-data" and (.source | endswith("/disks/" + $disk)))
	and any(.serviceAccounts[]; .email == $runtime)
	and (.tags.items | index($tag) != null)
	and all(.networkInterfaces[]; (.network | endswith("/networks/" + $vpc)))
	and any(.metadata.items[]; .key == "enable-oslogin" and .value == "TRUE")
	and any(.metadata.items[]; .key == "block-project-ssh-keys" and .value == "TRUE")
' <<< "${instance_json}" >/dev/null || gcp_die "instance read-back differs from the approved plan"

ip="$(jq -er '
	[.networkInterfaces[].accessConfigs[]? | select(.type == "ONE_TO_ONE_NAT") | .natIP]
	| select(length == 1) | .[0] | select(test("^[0-9]+(\\.[0-9]+){3}$"))
' <<< "${instance_json}")" || gcp_die "instance does not expose exactly one approved public IPv4 address"
expected_host="$(jq -r '.public_host_prefix' <<< "${config}").${ip//./-}.sslip.io"
[[ "${CRABIT_PUBLIC_HOST}" == "${expected_host}" ]] \
	|| gcp_die "CRABIT_PUBLIC_HOST does not match the selected environment VM address"
data_json="$(gcloud_json compute disks describe "${disk}" --zone "${zone}")"
jq -e --argjson size "$(jq '.data_disk_gb' <<< "${config}")" --arg instance "${instance}" --arg policy "${policy}" '
	.sizeGb == ($size | tostring)
	and (.type | endswith("/diskTypes/pd-balanced"))
	and ([.users[]?] | length == 1)
	and (.users[0] | endswith("/instances/" + $instance))
	and any(.resourcePolicies[]; endswith("/resourcePolicies/" + $policy))
' <<< "${data_json}" >/dev/null || gcp_die "data disk single-writer read-back differs from the approved plan"

"${GCP_SCRIPT_DIR}/verify-firewall.sh" "${environment}"
printf 'Google Cloud environment verified: environment=%s instance=%s ip=%s single-writer=true\n' \
	"${environment}" "${instance}" "${ip}"
