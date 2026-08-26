#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

[[ "$#" == "2" ]] || die "usage: deploy.sh <sha256:digest> <e2e|demo>"
readonly IMAGE_DIGEST="$1"
readonly REQUESTED_PROFILE="$2"
require_digest "${IMAGE_DIGEST}"
require_profile "${REQUESTED_PROFILE}"
prepare_deployment_context

readonly CONFIGURED_PROFILE="$(env_value CRABIT_SPRING_PROFILE "${RUNTIME_ENV}")"
[[ "${CONFIGURED_PROFILE}" == "${REQUESTED_PROFILE}" ]] \
	|| die "requested profile does not match the environment configuration"
readonly IMAGE_REFERENCE="${CRABIT_IMAGE_REPOSITORY}@${IMAGE_DIGEST}"

exec 9>"${STATE_DIR}/operations.lock"
flock -n 9 || die "another deployment or reset operation is active"
validate_snapshot_proof "${SNAPSHOT_PROOF}"

next_image_env="$(mktemp "${STATE_DIR}/next-image.XXXXXX")"
trap 'rm -f "${next_image_env}"' EXIT
write_image_env "${next_image_env}" "${IMAGE_REFERENCE}"

docker pull "${IMAGE_REFERENCE}" >/dev/null
compose=(docker compose --env-file "${RUNTIME_ENV}" --env-file "${next_image_env}" -f "${COMPOSE_FILE}")
"${compose[@]}" config --quiet
"${compose[@]}" up -d postgres
"${compose[@]}" up -d backend caddy

backend_id="$("${compose[@]}" ps -q backend)"
[[ -n "${backend_id}" ]] || die "backend container was not created"
wait_for_backend_health "${backend_id}"

configured_image="$(docker inspect --format '{{.Config.Image}}' "${backend_id}")"
[[ "${configured_image}" == "${IMAGE_REFERENCE}" ]] || die "running backend image differs from the selected digest"
docker image inspect "${IMAGE_REFERENCE}" --format '{{json .RepoDigests}}' \
	| jq -e --arg image "${IMAGE_REFERENCE}" 'index($image) != null' >/dev/null \
	|| die "local registry digest read-back does not match the selected digest"

verify_https_readiness "$(env_value CRABIT_PUBLIC_HOST "${RUNTIME_ENV}")"

if [[ -f "${CURRENT_IMAGE_ENV}" ]] && ! cmp -s "${CURRENT_IMAGE_ENV}" "${next_image_env}"; then
	cp "${CURRENT_IMAGE_ENV}" "${PREVIOUS_IMAGE_ENV}"
	chmod 0600 "${PREVIOUS_IMAGE_ENV}"
fi
mv "${next_image_env}" "${CURRENT_IMAGE_ENV}"
chmod 0600 "${CURRENT_IMAGE_ENV}"
trap - EXIT

printf 'deployment verified: environment=%s profile=%s digest=%s\n' \
	"${CRABIT_ENV_NAME}" "${REQUESTED_PROFILE}" "${IMAGE_DIGEST}"
