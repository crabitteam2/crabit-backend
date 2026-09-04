#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

[[ "$#" == "3" ]] || die "usage: deploy.sh <backend-sha256:digest> <recap-sha256:digest> <e2e|demo>"
readonly BACKEND_IMAGE_DIGEST="$1"
readonly RECAP_IMAGE_DIGEST="$2"
readonly REQUESTED_PROFILE="$3"
require_digest "${BACKEND_IMAGE_DIGEST}"
require_digest "${RECAP_IMAGE_DIGEST}"
require_profile "${REQUESTED_PROFILE}"
prepare_deployment_context
validate_recap_runtime_binding "${RUNTIME_ENV}"

readonly CONFIGURED_PROFILE="$(env_value CRABIT_SPRING_PROFILE "${RUNTIME_ENV}")"
[[ "${CONFIGURED_PROFILE}" == "${REQUESTED_PROFILE}" ]] \
	|| die "requested profile does not match the environment configuration"
readonly BACKEND_IMAGE_REFERENCE="${CRABIT_IMAGE_REPOSITORY}@${BACKEND_IMAGE_DIGEST}"
readonly RECAP_IMAGE_REFERENCE="${CRABIT_RECAP_IMAGE_REPOSITORY}@${RECAP_IMAGE_DIGEST}"

exec 9>"${STATE_DIR}/operations.lock"
flock -n 9 || die "another deployment or reset operation is active"
validate_snapshot_proof "${SNAPSHOT_PROOF}"
if [[ -f "${CURRENT_RELEASE_ENV}" ]]; then
	validate_release_env "${CURRENT_RELEASE_ENV}"
fi

next_release_env="$(mktemp "${STATE_DIR}/next-release.XXXXXX")"
write_release_env "${next_release_env}" "${BACKEND_IMAGE_REFERENCE}" "${RECAP_IMAGE_REFERENCE}"
validate_release_env "${next_release_env}"

compose_for_release() {
	local release_env="$1"
	COMPOSE_RESULT=(docker compose --env-file "${RUNTIME_ENV}" --env-file "${release_env}" -f "${COMPOSE_FILE}")
}

verify_running_release_pair() {
	local release_env="$1"
	local expected_backend expected_recap backend_id recap_id configured_image
	expected_backend="$(env_value CRABIT_BACKEND_IMAGE "${release_env}")"
	expected_recap="$(env_value CRABIT_RECAP_IMAGE "${release_env}")"
	compose_for_release "${release_env}"
	backend_id="$("${COMPOSE_RESULT[@]}" ps -q backend)"
	recap_id="$("${COMPOSE_RESULT[@]}" ps -q recap)"
	[[ -n "${backend_id}" && -n "${recap_id}" ]] || die "release pair containers were not created"
	wait_for_service_health "${recap_id}" recap
	wait_for_service_health "${backend_id}" backend
	configured_image="$(docker inspect --format '{{.Config.Image}}' "${backend_id}")"
	[[ "${configured_image}" == "${expected_backend}" ]] \
		|| die "running backend image differs from the selected digest"
	configured_image="$(docker inspect --format '{{.Config.Image}}' "${recap_id}")"
	[[ "${configured_image}" == "${expected_recap}" ]] \
		|| die "running recap image differs from the selected digest"
	verify_local_registry_digest "${expected_backend}"
	verify_local_registry_digest "${expected_recap}"
}

activate_release_pair() {
	local release_env="$1"
	local recap_id
	compose_for_release "${release_env}"
	"${COMPOSE_RESULT[@]}" config --quiet
	"${COMPOSE_RESULT[@]}" up -d postgres
	"${COMPOSE_RESULT[@]}" up -d recap
	recap_id="$("${COMPOSE_RESULT[@]}" ps -q recap)"
	[[ -n "${recap_id}" ]] || die "recap container was not created"
	wait_for_service_health "${recap_id}" recap
	"${COMPOSE_RESULT[@]}" up -d backend caddy
	verify_running_release_pair "${release_env}"
}

restore_current_release() (
	set -Eeuo pipefail
	validate_release_env "${CURRENT_RELEASE_ENV}"
	local backend_image recap_image
	backend_image="$(env_value CRABIT_BACKEND_IMAGE "${CURRENT_RELEASE_ENV}")"
	recap_image="$(env_value CRABIT_RECAP_IMAGE "${CURRENT_RELEASE_ENV}")"
	docker pull "${backend_image}" >/dev/null
	docker pull "${recap_image}" >/dev/null
	activate_release_pair "${CURRENT_RELEASE_ENV}"
)

deployment_started=false
cleanup_failed_deployment() {
	local status=$?
	trap - EXIT
	if [[ "${status}" -ne 0 && "${deployment_started}" == true ]]; then
		if [[ -f "${CURRENT_RELEASE_ENV}" ]]; then
			set +e
			restore_current_release
			restore_status=$?
			set -e
			if [[ "${restore_status}" -eq 0 ]]; then
				printf 'failed release was replaced by the previously verified backend/recap pair\n' >&2
			else
				printf 'failed release could not restore the previously verified pair; operator intervention is required\n' >&2
			fi
		else
			compose_for_release "${next_release_env}"
			"${COMPOSE_RESULT[@]}" stop caddy backend recap >/dev/null 2>&1 || true
			printf 'first deployment failed; partial serving containers were stopped\n' >&2
		fi
	fi
	rm -f "${next_release_env}"
	exit "${status}"
}
trap cleanup_failed_deployment EXIT

docker pull "${BACKEND_IMAGE_REFERENCE}" >/dev/null
docker pull "${RECAP_IMAGE_REFERENCE}" >/dev/null
deployment_started=true
activate_release_pair "${next_release_env}"
verify_https_readiness "$(env_value CRABIT_PUBLIC_HOST "${RUNTIME_ENV}")"

if [[ -f "${CURRENT_RELEASE_ENV}" ]] && ! cmp -s "${CURRENT_RELEASE_ENV}" "${next_release_env}"; then
	cp "${CURRENT_RELEASE_ENV}" "${PREVIOUS_RELEASE_ENV}"
	chmod 0600 "${PREVIOUS_RELEASE_ENV}"
fi
mv "${next_release_env}" "${CURRENT_RELEASE_ENV}"
chmod 0600 "${CURRENT_RELEASE_ENV}"
deployment_started=false
trap - EXIT

printf 'deployment verified: environment=%s profile=%s backend_digest=%s recap_digest=%s\n' \
	"${CRABIT_ENV_NAME}" "${REQUESTED_PROFILE}" "${BACKEND_IMAGE_DIGEST}" "${RECAP_IMAGE_DIGEST}"
