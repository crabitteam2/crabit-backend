#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
[[ "$#" -ge "5" ]] || gcp_die "usage: run-over-iap.sh <environment> <archive> <runtime-env> <snapshot-proof> <remote-command> [args...]"
readonly environment="$1"
readonly archive="$2"
readonly runtime_env="$3"
readonly snapshot_proof="$4"
readonly remote_command="$5"
shift 5

for command in gcloud jq ssh-keygen; do gcp_require_command "${command}"; done
validate_plan
validate_google_identity
gcp_require_env CRABIT_GCP_KNOWN_HOSTS
[[ -f "${archive}" && -f "${runtime_env}" && -f "${snapshot_proof}" && -f "${CRABIT_GCP_KNOWN_HOSTS}" ]] \
	|| gcp_die "archive, runtime env, snapshot proof, or pinned known_hosts file is absent"
[[ "$(stat -c '%a' "${runtime_env}" 2>/dev/null || stat -f '%Lp' "${runtime_env}")" == "600" ]] \
	|| gcp_die "runtime environment file must have mode 0600"
[[ "$(stat -c '%a' "${snapshot_proof}" 2>/dev/null || stat -f '%Lp' "${snapshot_proof}")" == "600" ]] \
	|| gcp_die "snapshot proof file must have mode 0600"
case "${remote_command}" in
	deploy.sh|reset-stable-demo.sh|rollback.sh) ;;
	*) gcp_die "remote command is not an approved deployment entrypoint" ;;
esac

readonly config="$(environment_json "${environment}")"
readonly instance="$(jq -r '.instance' <<< "${config}")"
readonly disk="$(jq -r '.data_disk' <<< "${config}")"
readonly runtime="$(runtime_email "${environment}")"
readonly zone="$(plan_value '.location.zone')"
readonly host_alias="gce-${instance}"
readonly release_id="${GITHUB_SHA:-manual}"
[[ "${release_id}" == "manual" || "${release_id}" =~ ^[0-9a-f]{40}$ ]] || gcp_die "release ID is invalid"
readonly release=".local/share/crabit/releases/${release_id}"

transport_env_value() {
	local key="$1"
	local file="$2"
	local count
	count="$(awk -F= -v key="${key}" '$1 == key { count++ } END { print count + 0 }' "${file}")"
	[[ "${count}" == "1" ]] || gcp_die "${file} must contain exactly one ${key} entry"
	awk -v key="${key}" 'index($0, key "=") == 1 { sub(/^[^=]*=/, ""); print }' "${file}"
}

for file in "${runtime_env}" "${snapshot_proof}"; do
	if grep -Ev '^[A-Z][A-Z0-9_]*=[A-Za-z0-9._:/@+-]+$' "${file}" | grep -q .; then
		gcp_die "transport input contains an unsupported or unsafe line: ${file}"
	fi
done
for key in CRABIT_ENV CRABIT_GCP_PROJECT_ID CRABIT_GCP_ZONE CRABIT_GCP_INSTANCE CRABIT_GCP_DATA_DISK; do
	transport_env_value "${key}" "${runtime_env}" >/dev/null
done
[[ "$(transport_env_value CRABIT_ENV "${runtime_env}")" == "${environment}" \
	&& "$(transport_env_value CRABIT_GCP_PROJECT_ID "${runtime_env}")" == "${GCP_PROJECT_ID}" \
	&& "$(transport_env_value CRABIT_GCP_ZONE "${runtime_env}")" == "${zone}" \
	&& "$(transport_env_value CRABIT_GCP_INSTANCE "${runtime_env}")" == "${instance}" \
	&& "$(transport_env_value CRABIT_GCP_DATA_DISK "${runtime_env}")" == "${disk}" ]] \
	|| gcp_die "runtime environment is not bound to the selected Google Cloud environment"
for key in CRABIT_GCP_ENV CRABIT_GCP_PROJECT_ID CRABIT_GCP_ZONE CRABIT_GCP_INSTANCE \
		CRABIT_GCP_DATA_DISK CRABIT_GCP_SNAPSHOT CRABIT_GCP_SNAPSHOT_STATUS \
		CRABIT_GCP_SNAPSHOT_SIZE_GB CRABIT_GCP_OPERATION_ID; do
	transport_env_value "${key}" "${snapshot_proof}" >/dev/null
done
[[ "$(transport_env_value CRABIT_GCP_ENV "${snapshot_proof}")" == "${environment}" \
	&& "$(transport_env_value CRABIT_GCP_PROJECT_ID "${snapshot_proof}")" == "${GCP_PROJECT_ID}" \
	&& "$(transport_env_value CRABIT_GCP_ZONE "${snapshot_proof}")" == "${zone}" \
	&& "$(transport_env_value CRABIT_GCP_INSTANCE "${snapshot_proof}")" == "${instance}" \
	&& "$(transport_env_value CRABIT_GCP_DATA_DISK "${snapshot_proof}")" == "${disk}" \
	&& "$(transport_env_value CRABIT_GCP_SNAPSHOT_STATUS "${snapshot_proof}")" == "READY" \
	&& "$(transport_env_value CRABIT_GCP_SNAPSHOT_SIZE_GB "${snapshot_proof}")" == "100" ]] \
	|| gcp_die "snapshot proof is not bound to the selected Google Cloud environment"
readonly snapshot_operation="$(transport_env_value CRABIT_GCP_OPERATION_ID "${snapshot_proof}")"
readonly snapshot_name="$(transport_env_value CRABIT_GCP_SNAPSHOT "${snapshot_proof}")"
[[ "${snapshot_operation}" =~ ^[a-z0-9][a-z0-9-]{2,50}$ \
	&& "${snapshot_name}" == "${disk}-${snapshot_operation}" ]] \
	|| gcp_die "snapshot proof is not bound to its environment data disk and operation"

ssh-keygen -F "${host_alias}" -f "${CRABIT_GCP_KNOWN_HOSTS}" >/dev/null \
	|| gcp_die "pinned known_hosts does not contain the exact instance HostKeyAlias"

readonly active_account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' --limit=1)"
[[ "${active_account}" == "$(deployer_email "${environment}")" ]] \
	|| gcp_die "active account is not the selected environment deployer"
remote_instance_json="$(gcloud_json compute instances describe "${instance}" --zone "${zone}")"
jq -e --arg instance "${instance}" --arg zone "${zone}" --arg disk "${disk}" --arg runtime "${runtime}" '
	.name == $instance
	and (.zone | endswith("/zones/" + $zone))
	and .status == "RUNNING"
	and any(.disks[]; .boot == false and .deviceName == "crabit-data" and (.source | endswith("/disks/" + $disk)))
	and any(.serviceAccounts[]; .email == $runtime)
' <<< "${remote_instance_json}" >/dev/null \
	|| gcp_die "IAP destination identity does not match the selected environment"

ssh_flags=(
	"--ssh-flag=-oBatchMode=yes"
	"--ssh-flag=-oStrictHostKeyChecking=yes"
	"--ssh-flag=-oUserKnownHostsFile=${CRABIT_GCP_KNOWN_HOSTS}"
	"--ssh-flag=-oHostKeyAlias=${host_alias}"
)
scp_flags=(
	"--scp-flag=-oBatchMode=yes"
	"--scp-flag=-oStrictHostKeyChecking=yes"
	"--scp-flag=-oUserKnownHostsFile=${CRABIT_GCP_KNOWN_HOSTS}"
	"--scp-flag=-oHostKeyAlias=${host_alias}"
)
base=(--project "${GCP_PROJECT_ID}" --zone "${zone}" --tunnel-through-iap --strict-host-key-checking=yes --quiet)

gcloud compute ssh "${instance}" "${base[@]}" "${ssh_flags[@]}" --command "mkdir -p '${release}'"
gcloud compute scp "${base[@]}" "${scp_flags[@]}" \
	"${archive}" "${runtime_env}" "${snapshot_proof}" "${instance}:${release}/"

quoted_args=""
for argument in "$@"; do
	printf -v quoted '%q' "${argument}"
	quoted_args+=" ${quoted}"
done
remote="chmod 600 '${release}/runtime.env' '${release}/snapshot-proof.env' && tar -xzf '${release}/deployment.tgz' -C '${release}' && chmod 700 '${release}/scripts/deployment/'*.sh '${release}/scripts/deployment/google-cloud/'*.sh && CRABIT_RUNTIME_ENV='${release}/runtime.env' CRABIT_SNAPSHOT_PROOF='${release}/snapshot-proof.env' '${release}/scripts/deployment/${remote_command}'${quoted_args}"
gcloud compute ssh "${instance}" "${base[@]}" "${ssh_flags[@]}" --command "${remote}"
