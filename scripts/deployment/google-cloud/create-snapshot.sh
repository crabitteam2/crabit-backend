#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
[[ "$#" == "3" ]] || gcp_die "usage: create-snapshot.sh <staging|stable-demo> <operation-id> <proof-output>"
readonly environment="$1"
readonly operation_id="$2"
readonly proof_output="$3"

gcp_require_command gcloud
gcp_require_command jq
validate_plan
validate_google_identity
[[ "${operation_id}" =~ ^[a-z0-9][a-z0-9-]{2,50}$ ]] || gcp_die "operation ID is invalid"

readonly config="$(environment_json "${environment}")"
readonly zone="$(plan_value '.location.zone')"
readonly disk="$(jq -r '.data_disk' <<< "${config}")"
readonly expected_size="$(jq -r '.data_disk_gb' <<< "${config}")"
readonly instance="$(jq -r '.instance' <<< "${config}")"
readonly snapshot="${disk}-${operation_id}"
[[ "${snapshot}" =~ ^[a-z]([-a-z0-9]{0,61}[a-z0-9])?$ ]] || gcp_die "derived snapshot name is invalid"

disk_json="$(gcloud_json compute disks describe "${disk}" --zone "${zone}")"
jq -e --argjson size "${expected_size}" --arg instance "${instance}" '
	.sizeGb == ($size | tostring)
	and (.type | endswith("/diskTypes/pd-balanced"))
	and ([.users[]?] | length == 1)
	and (.users[0] | endswith("/instances/" + $instance))
' <<< "${disk_json}" >/dev/null || gcp_die "snapshot source is not the exact single-writer data disk"

existing="$(gcloud_json compute snapshots describe "${snapshot}" 2>/dev/null || true)"
if [[ -z "${existing}" ]]; then
	create_output="$(mktemp)"
	if ! gcloud --quiet compute snapshots create "${snapshot}" \
			--project "${GCP_PROJECT_ID}" \
			--source-disk "${disk}" \
			--source-disk-zone "${zone}" \
			--storage-location "$(plan_value '.location.region')" \
			--labels "crabit-environment=${environment},crabit-operation=${operation_id}" \
			--format=json > "${create_output}" 2>&1; then
		# The create may have reached Google Cloud. Only exact authoritative read-back can adopt it.
		printf 'snapshot create returned failure; reconciling by exact read-back\n' >&2
	fi
	rm -f "${create_output}"
fi

snapshot_json="$(gcloud_json compute snapshots describe "${snapshot}")" \
	|| gcp_die "snapshot is absent after create or reconciliation"
jq -e --arg disk "${disk}" --arg environment "${environment}" --arg operation "${operation_id}" --argjson size "${expected_size}" '
	.status == "READY"
	and .diskSizeGb == ($size | tostring)
	and (.sourceDisk | endswith("/disks/" + $disk))
	and .labels."crabit-environment" == $environment
	and .labels."crabit-operation" == $operation
	and (.creationTimestamp | type == "string" and length > 0)
' <<< "${snapshot_json}" >/dev/null || gcp_die "snapshot read-back does not match the exact operation"

snapshot_id="$(jq -r '.id' <<< "${snapshot_json}")"
created_at="$(jq -r '.creationTimestamp' <<< "${snapshot_json}")"
[[ "${snapshot_id}" =~ ^[0-9]+$ ]] || gcp_die "snapshot ID read-back is invalid"
umask 077
{
	printf 'CRABIT_GCP_ENV=%s\n' "${environment}"
		printf 'CRABIT_GCP_PROJECT_ID=%s\n' "${GCP_PROJECT_ID}"
		printf 'CRABIT_GCP_ZONE=%s\n' "${zone}"
		printf 'CRABIT_GCP_INSTANCE=%s\n' "${instance}"
		printf 'CRABIT_GCP_DATA_DISK=%s\n' "${disk}"
	printf 'CRABIT_GCP_SNAPSHOT=%s\n' "${snapshot}"
	printf 'CRABIT_GCP_SNAPSHOT_ID=%s\n' "${snapshot_id}"
	printf 'CRABIT_GCP_SNAPSHOT_STATUS=READY\n'
	printf 'CRABIT_GCP_SNAPSHOT_SIZE_GB=%s\n' "${expected_size}"
	printf 'CRABIT_GCP_OPERATION_ID=%s\n' "${operation_id}"
	printf 'CRABIT_GCP_SNAPSHOT_CREATED_AT=%s\n' "${created_at}"
} > "${proof_output}"
chmod 0600 "${proof_output}"
printf 'snapshot verified: environment=%s snapshot=%s id=%s\n' "${environment}" "${snapshot}" "${snapshot_id}"
