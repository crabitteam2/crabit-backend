#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
prepare_deployment_context

[[ "$(env_value CRABIT_SPRING_PROFILE "${RUNTIME_ENV}")" == "demo" ]] \
	|| die "reset is allowed only for a demo environment"
[[ -f "${CURRENT_IMAGE_ENV}" ]] || die "there is no verified current image state"
current_image="$(env_value CRABIT_BACKEND_IMAGE "${CURRENT_IMAGE_ENV}")"
[[ "${current_image}" =~ ^${CRABIT_IMAGE_REPOSITORY}@sha256:[0-9a-f]{64}$ ]] \
	|| die "current image state is not an immutable Crabit digest"

exec 9>"${STATE_DIR}/operations.lock"
flock -n 9 || die "another deployment or reset operation is active"
validate_snapshot_proof "${SNAPSHOT_PROOF}"

compose=(docker compose --env-file "${RUNTIME_ENV}" --env-file "${CURRENT_IMAGE_ENV}" -f "${COMPOSE_FILE}")
backend_id="$("${compose[@]}" ps -q backend)"
[[ -n "${backend_id}" ]] || die "Stable Demo backend is not running"
[[ "$(docker inspect --format '{{.Config.Image}}' "${backend_id}")" == "${current_image}" ]] \
	|| die "running Stable Demo digest differs from verified current image state"

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
wait_for_backend_health "${backend_id}"
verify_https_readiness "$(env_value CRABIT_PUBLIC_HOST "${RUNTIME_ENV}")"

printf 'CRABIT_DEMO_RESET_COMPLETED\n'
