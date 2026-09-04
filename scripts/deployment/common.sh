#!/usr/bin/env bash
set -Eeuo pipefail

readonly CRABIT_IMAGE_REPOSITORY="${CRABIT_IMAGE_REPOSITORY:-crabitteam2/crabit-backend}"
readonly CRABIT_RECAP_IMAGE_REPOSITORY="${CRABIT_RECAP_IMAGE_REPOSITORY:-crabitteam2/crabit-data}"
readonly DEPLOYMENT_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly DEPLOYMENT_ROOT="$(cd "${DEPLOYMENT_SCRIPT_DIR}/../.." && pwd)"
readonly COMPOSE_FILE="${DEPLOYMENT_ROOT}/deploy/compose.yaml"

die() {
	printf 'deployment error: %s\n' "$1" >&2
	exit 1
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command is unavailable: $1"
}

require_digest() {
	[[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]] || die "image digest must be sha256 plus 64 lowercase hex characters"
}

require_profile() {
	case "$1" in
		e2e|demo) ;;
		*) die "Spring profile must be exactly e2e or demo" ;;
	esac
}

file_mode() {
	stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"
}

env_value() {
	local key="$1"
	local file="$2"
	local count
	count="$(awk -F= -v key="${key}" '$1 == key { count++ } END { print count + 0 }' "${file}")"
	[[ "${count}" == "1" ]] || die "${file} must contain exactly one ${key} entry"
	awk -v key="${key}" 'index($0, key "=") == 1 { sub(/^[^=]*=/, ""); print }' "${file}"
}

validate_runtime_env() {
	local file="$1"
	[[ -f "${file}" ]] || die "runtime environment file does not exist: ${file}"
	[[ "$(file_mode "${file}")" == "600" ]] || die "runtime environment file must have mode 0600"
	if grep -Ev '^[A-Z][A-Z0-9_]*=[A-Za-z0-9._:/@+-]+$' "${file}" | grep -q .; then
		die "runtime environment file contains an unsupported or unsafe line"
	fi
	awk -F= '{ if (seen[$1]++) exit 1 }' "${file}" || die "runtime environment contains duplicate keys"
	for key in CRABIT_ENV CRABIT_COMPOSE_PROJECT CRABIT_SPRING_PROFILE CRABIT_PUBLIC_HOST \
			CRABIT_DATABASE_NAME CRABIT_DATABASE_USERNAME CRABIT_DATABASE_PASSWORD \
			CRABIT_GCP_PROJECT_ID CRABIT_GCP_ZONE CRABIT_GCP_INSTANCE CRABIT_GCP_DATA_DISK; do
		[[ -n "$(env_value "${key}" "${file}")" ]] || die "${key} must not be blank"
	done
	[[ "$(env_value CRABIT_GCP_ZONE "${file}")" == "asia-northeast3-a" ]] \
		|| die "runtime environment must use the approved Seoul zone"
	[[ "$(env_value CRABIT_GCP_INSTANCE "${file}")" == "crabit-$(env_value CRABIT_ENV "${file}")" ]] \
		|| die "runtime environment instance does not match its environment"
	[[ "$(env_value CRABIT_GCP_DATA_DISK "${file}")" == "crabit-$(env_value CRABIT_ENV "${file}")-data" ]] \
		|| die "runtime environment data disk does not match its environment"
	if grep -q '^CRABIT_WISH_PHOTO_' "${file}"; then
		local enabled environment project number bucket account
		enabled="$(env_value CRABIT_WISH_PHOTO_ENABLED "${file}")"
		[[ "${enabled}" == true || "${enabled}" == false ]] || die "Wish photo opt-in must be true or false"
		if [[ "${enabled}" == true || "$(grep -c '^CRABIT_WISH_PHOTO_' "${file}")" -gt 1 ]]; then
			environment="$(env_value CRABIT_ENV "${file}")"
			project="$(env_value CRABIT_GCP_PROJECT_ID "${file}")"
			number="$(env_value CRABIT_WISH_PHOTO_PROJECT_NUMBER "${file}")"
			[[ "${environment}" == staging || "${environment}" == stable-demo ]] || die "Unsupported photo environment"
			[[ "${number}" =~ ^[0-9]{6,20}$ ]] || die "Invalid photo project number"
			[[ "$(env_value CRABIT_WISH_PHOTO_ENVIRONMENT "${file}")" == "${environment}" \
				&& "$(env_value CRABIT_WISH_PHOTO_PROJECT_ID "${file}")" == "${project}" \
				&& "$(env_value CRABIT_WISH_PHOTO_BUCKET "${file}")" == "crabit-wish-photo-${environment}-${number}" \
				&& "$(env_value CRABIT_WISH_PHOTO_SERVICE_ACCOUNT "${file}")" == "crabit-${environment}-runtime@${project}.iam.gserviceaccount.com" ]] \
				|| die "Wish photo runtime environment binding mismatch"
			if [[ "${enabled}" == true ]]; then
				[[ "${project}" == project-9ee29576-dd79-4a1c-a70 && "${number}" == 182907578804 ]] || die "Unapproved photo project"
			fi
		fi
	fi
}

validate_recap_runtime_binding() {
	local file="$1"
	[[ -n "$(env_value CRABIT_RECAP_GENERATION_CREDENTIAL "${file}")" ]] \
		|| die "CRABIT_RECAP_GENERATION_CREDENTIAL must not be blank"
}

validate_snapshot_proof() {
	local file="$1"
	[[ -f "${file}" ]] || die "snapshot proof does not exist: ${file}"
	[[ "$(file_mode "${file}")" == "600" ]] || die "snapshot proof must have mode 0600"
	if grep -Ev '^[A-Z][A-Z0-9_]*=[A-Za-z0-9._:/@+-]+$' "${file}" | grep -q .; then
		die "snapshot proof contains an unsupported or unsafe line"
	fi
	for key in CRABIT_GCP_ENV CRABIT_GCP_PROJECT_ID CRABIT_GCP_ZONE CRABIT_GCP_DATA_DISK \
			CRABIT_GCP_SNAPSHOT CRABIT_GCP_SNAPSHOT_ID CRABIT_GCP_SNAPSHOT_STATUS \
			CRABIT_GCP_SNAPSHOT_SIZE_GB CRABIT_GCP_OPERATION_ID CRABIT_GCP_SNAPSHOT_CREATED_AT; do
		[[ -n "$(env_value "${key}" "${file}")" ]] || die "snapshot proof key is blank: ${key}"
	done
	[[ "$(env_value CRABIT_GCP_ENV "${file}")" == "${CRABIT_ENV_NAME}" ]] \
		|| die "snapshot environment does not match runtime environment"
	for key in CRABIT_GCP_PROJECT_ID CRABIT_GCP_ZONE CRABIT_GCP_INSTANCE CRABIT_GCP_DATA_DISK; do
		[[ "$(env_value "${key}" "${file}")" == "$(env_value "${key}" "${RUNTIME_ENV}")" ]] \
			|| die "snapshot ${key} does not match runtime environment"
	done
	[[ "$(env_value CRABIT_GCP_SNAPSHOT_STATUS "${file}")" == "READY" ]] \
		|| die "snapshot proof is not READY"
	[[ "$(env_value CRABIT_GCP_SNAPSHOT_SIZE_GB "${file}")" == "100" ]] \
		|| die "snapshot proof does not identify the approved 100 GB data disk"
	[[ "$(env_value CRABIT_GCP_SNAPSHOT_ID "${file}")" =~ ^[0-9]+$ ]] \
		|| die "snapshot proof ID is invalid"
	local disk snapshot operation
	disk="$(env_value CRABIT_GCP_DATA_DISK "${file}")"
	snapshot="$(env_value CRABIT_GCP_SNAPSHOT "${file}")"
	operation="$(env_value CRABIT_GCP_OPERATION_ID "${file}")"
	[[ "${operation}" =~ ^[a-z0-9][a-z0-9-]{2,50}$ && "${snapshot}" == "${disk}-${operation}" ]] \
		|| die "snapshot name is not bound to the exact operation"
}

prepare_deployment_context() {
	require_command docker
	require_command flock
	require_command curl
	require_command jq
	readonly RUNTIME_ENV="${CRABIT_RUNTIME_ENV:-${HOME}/.config/crabit/runtime.env}"
	validate_runtime_env "${RUNTIME_ENV}"
	readonly CRABIT_ENV_NAME="$(env_value CRABIT_ENV "${RUNTIME_ENV}")"
	[[ "${CRABIT_ENV_NAME}" =~ ^[a-z0-9][a-z0-9-]{1,30}$ ]] || die "CRABIT_ENV is invalid"
	readonly STATE_DIR="${CRABIT_STATE_DIR:-${HOME}/.local/state/crabit/${CRABIT_ENV_NAME}}"
	mkdir -p "${STATE_DIR}"
	chmod 0700 "${STATE_DIR}"
	readonly CURRENT_RELEASE_ENV="${STATE_DIR}/current-release.env"
	readonly PREVIOUS_RELEASE_ENV="${STATE_DIR}/previous-release.env"
	readonly LEGACY_CURRENT_IMAGE_ENV="${STATE_DIR}/current-image.env"
	readonly SNAPSHOT_PROOF="${CRABIT_SNAPSHOT_PROOF:-}"
}

reject_legacy_single_image_state() {
	if [[ ! -f "${CURRENT_RELEASE_ENV}" \
			&& ( -e "${LEGACY_CURRENT_IMAGE_ENV}" || -L "${LEGACY_CURRENT_IMAGE_ENV}" ) ]]; then
		die "legacy deployment state detected at ${LEGACY_CURRENT_IMAGE_ENV}; deployment stopped before changing serving containers. Seed ${CURRENT_RELEASE_ENV} with verified immutable CRABIT_BACKEND_IMAGE and CRABIT_RECAP_IMAGE entries, set mode 0600, then retry"
	fi
}

write_release_env() {
	local target="$1"
	local backend_image="$2"
	local recap_image="$3"
	(umask 077; printf 'CRABIT_BACKEND_IMAGE=%s\nCRABIT_RECAP_IMAGE=%s\n' \
		"${backend_image}" "${recap_image}" > "${target}")
}

validate_release_env() {
	local file="$1"
	[[ -f "${file}" ]] || die "release state does not exist: ${file}"
	[[ "$(file_mode "${file}")" == "600" ]] || die "release state must have mode 0600"
	if grep -Ev '^[A-Z][A-Z0-9_]*=[A-Za-z0-9._:/@+-]+$' "${file}" | grep -q .; then
		die "release state contains an unsupported or unsafe line"
	fi
	[[ "$(wc -l < "${file}" | tr -d ' ')" == "2" ]] \
		|| die "release state must contain exactly two image references"
	local backend_image recap_image
	backend_image="$(env_value CRABIT_BACKEND_IMAGE "${file}")"
	recap_image="$(env_value CRABIT_RECAP_IMAGE "${file}")"
	[[ "${backend_image}" =~ ^${CRABIT_IMAGE_REPOSITORY}@sha256:[0-9a-f]{64}$ ]] \
		|| die "backend release image is not an immutable Crabit digest"
	[[ "${recap_image}" =~ ^${CRABIT_RECAP_IMAGE_REPOSITORY}@sha256:[0-9a-f]{64}$ ]] \
		|| die "recap release image is not an immutable Crabit digest"
}

wait_for_service_health() {
	local container_id="$1"
	local service="$2"
	local attempt
	local status
	for attempt in $(seq 1 60); do
		status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "${container_id}")"
		case "${status}" in
			healthy) return 0 ;;
			unhealthy) die "${service} container became unhealthy" ;;
		esac
		sleep 2
	done
	die "${service} readiness timed out"
}

verify_local_registry_digest() {
	local image="$1"
	docker image inspect "${image}" --format '{{json .RepoDigests}}' \
		| jq -e --arg image "${image}" 'index($image) != null' >/dev/null \
		|| die "local registry digest read-back does not match ${image%%@*}"
}

verify_https_readiness() {
	local public_host="$1"
	local attempt
	local response_file
	response_file="$(mktemp)"
	trap 'rm -f "${response_file}"' RETURN

	for attempt in $(seq 1 12); do
		if curl --fail --silent --show-error --max-time 3 \
				"https://${public_host}/actuator/health/readiness" > "${response_file}" \
				&& jq -e 'keys == ["status"] and .status == "UP"' \
					"${response_file}" >/dev/null; then
			rm -f "${response_file}"
			trap - RETURN
			return 0
		fi
		[[ "${attempt}" == "12" ]] || sleep 2
	done

	rm -f "${response_file}"
	trap - RETURN
	die "HTTPS readiness did not return aggregate UP after 12 attempts"
}
