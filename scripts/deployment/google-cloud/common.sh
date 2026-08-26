#!/usr/bin/env bash
set -Eeuo pipefail

readonly GCP_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly GCP_REPOSITORY_ROOT="$(cd "${GCP_SCRIPT_DIR}/../../.." && pwd)"
readonly GCP_PLAN="${CRABIT_GCP_PLAN:-${GCP_REPOSITORY_ROOT}/deploy/google-cloud/plan.json}"

gcp_die() {
	printf 'google cloud deployment error: %s\n' "$1" >&2
	exit 1
}

gcp_require_command() {
	command -v "$1" >/dev/null 2>&1 || gcp_die "required command is unavailable: $1"
}

gcp_require_env() {
	local name="$1"
	[[ -n "${!name:-}" ]] || gcp_die "required environment variable is blank: ${name}"
}

validate_plan() {
	local plan="${1:-${GCP_PLAN}}"
	jq -e '
		.schema_version == 1
		and .schema_kind == "crabit-google-cloud-plan-v1"
		and .github_repository == "crabitteam2/crabit-backend"
		and .location == {region:"asia-northeast3",zone:"asia-northeast3-a"}
		and .network.vpc == "crabit-nonprod"
		and .network.subnet == "crabit-seoul"
		and .network.subnet_cidr == "10.30.0.0/24"
		and .network.public_tcp_ports == [80,443]
		and .network.iap_tcp_ports == [22]
		and .network.iap_source_range == "35.235.240.0/20"
		and .network.forbidden_public_tcp_ports == [22,8080,5432]
		and .network.os_login == true
		and .network.block_project_ssh_keys == true
		and .image.repository == "crabitteam2/crabit-backend"
		and .image.selector == "immutable-sha256-digest"
		and .image.retain_previous_verified_digest == true
		and .image.mutable_tags_allowed == false
		and .identity.oidc_issuer == "https://token.actions.githubusercontent.com"
		and .identity.service_account_keys_allowed == false
		and .budget == {display_name:"crabit-non-production-google-cloud",currency:"USD",amount:200,alert_thresholds:[100,150,180],hard_cap:false}
		and .snapshots.required_status == "READY"
		and .snapshots.required_before == ["initialization","deploy","reset","rollback","restore"]
		and .snapshots.single_writer == true
		and .migration == {mode:"greenfield",historical_database_import:false,vultr_access_allowed:false,rollback_provider:"google-cloud-only"}
		and ([.environments[].name] == ["staging","stable-demo"])
		and all(.environments[];
			.machine_type == "e2-medium"
			and .boot_disk_gb == 30
			and .data_disk_gb == 100
			and .data_disk_type == "pd-balanced"
			and (.spring_profile == "e2e" or .spring_profile == "demo")
			and (.instance | test("^crabit-[a-z0-9-]+$"))
			and (.data_disk | test("^crabit-[a-z0-9-]+-data$"))
		)
		and ([.environments[].instance] | length == (unique | length))
		and ([.environments[].address] | length == (unique | length))
		and ([.environments[].data_disk] | length == (unique | length))
		and ([.environments[].snapshot_policy] | length == (unique | length))
		and ([.environments[].deployer_service_account] | length == (unique | length))
		and ([.environments[].runtime_service_account] | length == (unique | length))
		and ([.environments[].network_tag] | length == (unique | length))
		and ([.environments[].operation_lock] | length == (unique | length))
	' "${plan}" >/dev/null || gcp_die "Google Cloud plan violates the approved architecture"
}

environment_json() {
	local requested_environment="$1"
	jq -cer --arg environment "${requested_environment}" \
		'.environments[] | select(.name == $environment)' "${GCP_PLAN}" \
		|| gcp_die "unknown environment: ${requested_environment}"
}

plan_value() {
	jq -er "$1" "${GCP_PLAN}"
}

validate_google_identity() {
	gcp_require_env GCP_PROJECT_ID
	gcp_require_env GCP_PROJECT_NUMBER
	[[ "${GCP_PROJECT_ID}" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]] || gcp_die "GCP_PROJECT_ID is invalid"
	[[ "${GCP_PROJECT_NUMBER}" =~ ^[0-9]{6,20}$ ]] || gcp_die "GCP_PROJECT_NUMBER is invalid"
}

deployer_email() {
	local requested_environment="$1"
	local account
	account="$(environment_json "${requested_environment}" | jq -r '.deployer_service_account')"
	printf '%s@%s.iam.gserviceaccount.com\n' "${account}" "${GCP_PROJECT_ID}"
}

runtime_email() {
	local requested_environment="$1"
	local account
	account="$(environment_json "${requested_environment}" | jq -r '.runtime_service_account')"
	printf '%s@%s.iam.gserviceaccount.com\n' "${account}" "${GCP_PROJECT_ID}"
}

gcloud_json() {
	gcloud --quiet "$@" --project "${GCP_PROJECT_ID}" --format=json
}
