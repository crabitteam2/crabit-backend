#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
prepare_deployment_context
validate_recap_runtime_binding "${RUNTIME_ENV}"

[[ "$(env_value CRABIT_SPRING_PROFILE "${RUNTIME_ENV}")" == "demo" ]] \
	|| die "reset is allowed only for a demo environment"
validate_release_env "${CURRENT_RELEASE_ENV}"
current_backend_image="$(env_value CRABIT_BACKEND_IMAGE "${CURRENT_RELEASE_ENV}")"
current_recap_image="$(env_value CRABIT_RECAP_IMAGE "${CURRENT_RELEASE_ENV}")"

exec 9>"${STATE_DIR}/operations.lock"
flock -n 9 || die "another deployment or reset operation is active"
validate_snapshot_proof "${SNAPSHOT_PROOF}"

compose=(docker compose --env-file "${RUNTIME_ENV}" --env-file "${CURRENT_RELEASE_ENV}" -f "${COMPOSE_FILE}")
backend_id="$("${compose[@]}" ps -q backend)"
recap_id="$("${compose[@]}" ps -q recap)"
[[ -n "${backend_id}" && -n "${recap_id}" ]] || die "Stable Demo release pair is not running"
wait_for_service_health "${recap_id}" recap
wait_for_service_health "${backend_id}" backend
[[ "$(docker inspect --format '{{.Config.Image}}' "${backend_id}")" == "${current_backend_image}" \
	&& "$(docker inspect --format '{{.Config.Image}}' "${recap_id}")" == "${current_recap_image}" ]] \
	|| die "running Stable Demo pair differs from verified current release state"
verify_local_registry_digest "${current_backend_image}"
verify_local_registry_digest "${current_recap_image}"

"${compose[@]}" stop backend >/dev/null
reset_log="$(mktemp "${STATE_DIR}/reset.XXXXXX.log")"
trap 'rm -f "${reset_log}"' EXIT
if ! "${compose[@]}" --profile reset run --rm demo-reset >"${reset_log}" 2>&1; then
	die "Demo reset failed; backend remains stopped for operator intervention"
fi
grep -q 'CRABIT_DEMO_RESET_COMPLETED' "${reset_log}" \
	|| die "Demo reset returned success without the committed completion marker"

"${compose[@]}" up -d backend caddy >/dev/null
backend_id="$("${compose[@]}" ps -q backend)"
recap_id="$("${compose[@]}" ps -q recap)"
wait_for_service_health "${recap_id}" recap
wait_for_service_health "${backend_id}" backend
[[ "$(docker inspect --format '{{.Config.Image}}' "${backend_id}")" == "${current_backend_image}" \
	&& "$(docker inspect --format '{{.Config.Image}}' "${recap_id}")" == "${current_recap_image}" ]] \
	|| die "Stable Demo reset restarted a different release pair"
verify_https_readiness "$(env_value CRABIT_PUBLIC_HOST "${RUNTIME_ENV}")"

printf 'CRABIT_DEMO_RESET_COMPLETED\n'
