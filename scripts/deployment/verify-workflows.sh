#!/usr/bin/env bash
set -Eeuo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly WORKFLOWS="${ROOT}/.github/workflows"

for command in docker jq; do
	command -v "${command}" >/dev/null 2>&1 || { printf 'missing command: %s\n' "${command}" >&2; exit 1; }
done
for script in "${ROOT}"/scripts/deployment/*.sh; do bash -n "${script}"; done

if grep -RniE '(^|[^[:alnum:]_-])latest([^[:alnum:]_-]|$)|ssh-keyscan|StrictHostKeyChecking[= ]no|set -x|pull_request_target' \
		"${WORKFLOWS}" "${ROOT}/deploy" \
		"${ROOT}/scripts/deployment/common.sh" \
		"${ROOT}/scripts/deployment/deploy.sh" \
		"${ROOT}/scripts/deployment/reset-stable-demo.sh" \
		"${ROOT}/scripts/deployment/rollback.sh" \
		"${ROOT}/scripts/deployment/verify-image.sh" \
		"${ROOT}/scripts/deployment/verify-runtime.sh"; then
	printf 'forbidden floating image, SSH, debug, or privileged-trigger pattern found\n' >&2
	exit 1
fi

publish="${WORKFLOWS}/publish-and-deploy-stable-demo.yml"
staging="${WORKFLOWS}/deploy-staging.yml"
reset="${WORKFLOWS}/reset-stable-demo.yml"
grep -q 'develop' "${publish}"
grep -q 'main' "${publish}"
grep -q "github.ref == 'refs/heads/main'" "${publish}"
grep -q 'needs.publish.outputs.image_digest' "${publish}"
grep -q 'workflow_dispatch' "${staging}"
! grep -Eq '^[[:space:]]+(push|workflow_run):' "${staging}"
grep -q 'origin/develop' "${staging}"
grep -q 'workflow_dispatch' "${reset}"
! grep -Eq '^[[:space:]]+(push|workflow_run|schedule):' "${reset}"

config_file="$(mktemp)"
trap 'rm -f "${config_file}"' EXIT
CRABIT_ENV=verify-static \
CRABIT_COMPOSE_PROJECT=crabit-verify-static \
CRABIT_SPRING_PROFILE=demo \
CRABIT_PUBLIC_HOST=localhost \
CRABIT_DATABASE_NAME=crabit \
CRABIT_DATABASE_USERNAME=crabit \
CRABIT_DATABASE_PASSWORD=verify_secret \
CRABIT_BACKEND_IMAGE=crabitteam2/crabit-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
CRABIT_DEMO_TOKEN_OWNER=owner CRABIT_DEMO_TOKEN_FRIEND=friend \
CRABIT_DEMO_TOKEN_NONFRIEND=nonfriend CRABIT_DEMO_TOKEN_BLOCKED=blocked \
CRABIT_DEMO_TOKEN_OTHER_ACADEMY=other CRABIT_DEMO_TOKEN_STAFF=staff \
	docker compose -f "${ROOT}/deploy/compose.yaml" --profile reset config --format json >"${config_file}"

jq -e '.services.backend.ports == null and .services.postgres.ports == null' "${config_file}" >/dev/null
jq -e '(.services.caddy.ports | map(.published) | sort) == ["443", "80"]' "${config_file}" >/dev/null
jq -e '.networks.database.internal == true' "${config_file}" >/dev/null
jq -e '.services.backend.image | test("^crabitteam2/crabit-backend@sha256:[0-9a-f]{64}$")' "${config_file}" >/dev/null
jq -e '.services.postgres.image | contains("@sha256:")' "${config_file}" >/dev/null
jq -e '.services.caddy.image | contains("@sha256:")' "${config_file}" >/dev/null

printf 'workflow and Compose invariants verified\n'
