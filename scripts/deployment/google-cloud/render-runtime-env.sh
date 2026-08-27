#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
[[ "$#" == "2" ]] || gcp_die "usage: render-runtime-env.sh <staging|stable-demo> <output>"
readonly environment="$1"
readonly output="$2"
validate_plan
validate_google_identity
readonly config="$(environment_json "${environment}")"

for name in CRABIT_PUBLIC_HOST CRABIT_DATABASE_NAME CRABIT_DATABASE_USERNAME CRABIT_COMPOSE_PROJECT CRABIT_DATABASE_PASSWORD; do
	gcp_require_env "${name}"
done
[[ "${CRABIT_PUBLIC_HOST}" =~ ^[A-Za-z0-9.-]+$ ]] || gcp_die "CRABIT_PUBLIC_HOST is invalid"
[[ "${CRABIT_DATABASE_NAME}" =~ ^[A-Za-z0-9_]+$ && "${CRABIT_DATABASE_USERNAME}" =~ ^[A-Za-z0-9_]+$ ]] \
	|| gcp_die "database name or username is invalid"
[[ "${CRABIT_COMPOSE_PROJECT}" =~ ^[A-Za-z0-9_-]+$ ]] || gcp_die "Compose project is invalid"
[[ "${CRABIT_DATABASE_PASSWORD}" =~ ^[A-Za-z0-9_-]{16,}$ ]] || gcp_die "database password must be base64url-safe"

if [[ "${environment}" == "stable-demo" ]]; then
	for name in CRABIT_DEMO_TOKEN_OWNER CRABIT_DEMO_TOKEN_FRIEND CRABIT_DEMO_TOKEN_NONFRIEND \
			CRABIT_DEMO_TOKEN_BLOCKED CRABIT_DEMO_TOKEN_OTHER_ACADEMY CRABIT_DEMO_TOKEN_STAFF; do
		gcp_require_env "${name}"
		[[ "${!name}" =~ ^[A-Za-z0-9_-]{16,}$ ]] || gcp_die "${name} must be base64url-safe"
	done
	gcp_require_env CRABIT_DEMO_BALANCE_PROVIDER_URL
	gcp_require_env CRABIT_DEMO_BALANCE_PROVIDER_TOKEN
	[[ "${CRABIT_DEMO_BALANCE_PROVIDER_URL}" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?/api/provider/balance-lookups$ ]] \
		|| gcp_die "balance provider URL must target the exact HTTPS endpoint"
	[[ "${CRABIT_DEMO_BALANCE_PROVIDER_TOKEN}" =~ ^[A-Za-z0-9_-]{32,}$ ]] \
		|| gcp_die "balance provider token must be base64url-safe"
	persona_tokens=(
		"${CRABIT_DEMO_TOKEN_OWNER}" "${CRABIT_DEMO_TOKEN_FRIEND}" "${CRABIT_DEMO_TOKEN_NONFRIEND}"
		"${CRABIT_DEMO_TOKEN_BLOCKED}" "${CRABIT_DEMO_TOKEN_OTHER_ACADEMY}" "${CRABIT_DEMO_TOKEN_STAFF}"
	)
	[[ "$(printf '%s\n' "${persona_tokens[@]}" | sort -u | wc -l | tr -d ' ')" == "6" ]] \
		|| gcp_die "Stable Demo persona tokens must be pairwise distinct"
fi

umask 077
{
	printf 'CRABIT_ENV=%s\n' "${environment}"
	printf 'CRABIT_COMPOSE_PROJECT=%s\n' "${CRABIT_COMPOSE_PROJECT}"
	printf 'CRABIT_SPRING_PROFILE=%s\n' "$(jq -r '.spring_profile' <<< "${config}")"
	printf 'CRABIT_PUBLIC_HOST=%s\n' "${CRABIT_PUBLIC_HOST}"
	printf 'CRABIT_DATABASE_NAME=%s\n' "${CRABIT_DATABASE_NAME}"
	printf 'CRABIT_DATABASE_USERNAME=%s\n' "${CRABIT_DATABASE_USERNAME}"
	printf 'CRABIT_DATABASE_PASSWORD=%s\n' "${CRABIT_DATABASE_PASSWORD}"
	printf 'CRABIT_GCP_PROJECT_ID=%s\n' "${GCP_PROJECT_ID}"
	printf 'CRABIT_GCP_ZONE=%s\n' "$(plan_value '.location.zone')"
	printf 'CRABIT_GCP_INSTANCE=%s\n' "$(jq -r '.instance' <<< "${config}")"
	printf 'CRABIT_GCP_DATA_DISK=%s\n' "$(jq -r '.data_disk' <<< "${config}")"
	if [[ "${environment}" == "stable-demo" ]]; then
		for name in CRABIT_DEMO_TOKEN_OWNER CRABIT_DEMO_TOKEN_FRIEND CRABIT_DEMO_TOKEN_NONFRIEND \
				CRABIT_DEMO_TOKEN_BLOCKED CRABIT_DEMO_TOKEN_OTHER_ACADEMY CRABIT_DEMO_TOKEN_STAFF \
				CRABIT_DEMO_BALANCE_PROVIDER_URL CRABIT_DEMO_BALANCE_PROVIDER_TOKEN; do
			printf '%s=%s\n' "${name}" "${!name}"
		done
	fi
} > "${output}"
chmod 0600 "${output}"
printf 'runtime environment rendered: environment=%s output=%s\n' "${environment}" "${output}"
