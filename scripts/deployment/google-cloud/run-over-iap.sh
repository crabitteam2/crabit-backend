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
case "${remote_command}" in
	deploy.sh|reset-stable-demo.sh|rollback.sh) ;;
	*) gcp_die "remote command is not an approved deployment entrypoint" ;;
esac

readonly config="$(environment_json "${environment}")"
readonly instance="$(jq -r '.instance' <<< "${config}")"
readonly zone="$(plan_value '.location.zone')"
readonly host_alias="gce-${instance}"
readonly release_id="${GITHUB_SHA:-manual}"
[[ "${release_id}" == "manual" || "${release_id}" =~ ^[0-9a-f]{40}$ ]] || gcp_die "release ID is invalid"
readonly release=".local/share/crabit/releases/${release_id}"
ssh-keygen -F "${host_alias}" -f "${CRABIT_GCP_KNOWN_HOSTS}" >/dev/null \
	|| gcp_die "pinned known_hosts does not contain the exact instance HostKeyAlias"

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
