#!/usr/bin/env bash
set -Eeuo pipefail

[[ "$#" == "1" ]] || { printf 'usage: verify-runtime.sh <local-image>\n' >&2; exit 2; }
readonly IMAGE="$1"
readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ENV_NAME="verify-${$}"
readonly PROJECT="crabit-${ENV_NAME}"
tmp_dir="$(mktemp -d)"
env_file="${tmp_dir}/runtime.env"
cat >"${env_file}" <<EOF
CRABIT_ENV=${ENV_NAME}
CRABIT_COMPOSE_PROJECT=${PROJECT}
CRABIT_SPRING_PROFILE=demo
CRABIT_PUBLIC_HOST=localhost
CRABIT_DATABASE_NAME=crabit_verify
CRABIT_DATABASE_USERNAME=crabit
CRABIT_DATABASE_PASSWORD=verify_database_secret
CRABIT_BACKEND_IMAGE=${IMAGE}
CRABIT_DEMO_TOKEN_OWNER=verify_owner_secret
CRABIT_DEMO_TOKEN_FRIEND=verify_friend_secret
CRABIT_DEMO_TOKEN_NONFRIEND=verify_nonfriend_secret
CRABIT_DEMO_TOKEN_BLOCKED=verify_blocked_secret
CRABIT_DEMO_TOKEN_OTHER_ACADEMY=verify_other_academy_secret
CRABIT_DEMO_TOKEN_STAFF=verify_staff_secret
CRABIT_DEMO_BALANCE_PROVIDER_URL=https://demo-console.example/api/provider/balance-lookups
CRABIT_DEMO_BALANCE_PROVIDER_TOKEN=verify_demo_balance_provider_secret
EOF
chmod 0600 "${env_file}"
compose=(docker compose --env-file "${env_file}" -f "${ROOT}/deploy/compose.yaml")
cleanup() {
	"${compose[@]}" --profile reset down --volumes --remove-orphans >/dev/null 2>&1 || true
	rm -rf "${tmp_dir}"
}
trap cleanup EXIT

backend_diagnostics() {
	printf 'backend runtime diagnostics:\n' >&2
	"${compose[@]}" ps >&2 || true
	"${compose[@]}" logs --no-color --tail 200 backend postgres >&2 || true
}

wait_for_backend() {
	local container_id="$1"
	local status
	for _ in $(seq 1 60); do
		status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' \
			"${container_id}")"
		[[ "${status}" == "healthy" ]] && return 0
		if [[ "${status}" == "unhealthy" ]]; then
			printf 'backend became unhealthy\n' >&2
			backend_diagnostics
			return 1
		fi
		sleep 2
	done
	printf 'backend readiness timed out\n' >&2
	backend_diagnostics
	return 1
}

verify_swagger_documents() {
	local json_document="${tmp_dir}/openapi.json"
	local yaml_document="${tmp_dir}/openapi.yaml"
	"${compose[@]}" exec -T backend wget -q -O - \
		'http://127.0.0.1:8080/v3/api-docs' >"${json_document}"
	jq -e '.openapi == "3.1.0" and (.paths | type == "object") and (.paths | length > 0)' \
		"${json_document}" >/dev/null \
		|| { printf 'Swagger JSON is not a non-empty OpenAPI 3.1 document\n' >&2; return 1; }
	"${compose[@]}" exec -T backend wget -q -O - \
		'http://127.0.0.1:8080/v3/api-docs.yaml' >"${yaml_document}"
	grep -Eq '^openapi:[[:space:]]+3\.1\.0[[:space:]]*$' "${yaml_document}" \
		|| { printf 'Swagger YAML is not an OpenAPI 3.1 document\n' >&2; return 1; }
}

"${compose[@]}" config --quiet
"${compose[@]}" up -d postgres backend >/dev/null
backend_id="$("${compose[@]}" ps -q backend)"
wait_for_backend "${backend_id}"
verify_swagger_documents || { backend_diagnostics; exit 1; }

for service in backend postgres; do
	container_id="$("${compose[@]}" ps -q "${service}")"
	port_bindings="$(docker inspect --format '{{json .HostConfig.PortBindings}}' "${container_id}")"
	[[ "${port_bindings}" == "null" || "${port_bindings}" == "{}" ]] \
		|| { printf '%s unexpectedly publishes a host port\n' "${service}" >&2; exit 1; }
done

"${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U crabit -d crabit_verify \
	-c "UPDATE wish SET purpose = 'persistent mutation' WHERE id = '00000000-0000-0000-0000-000000000401'" >/dev/null
"${compose[@]}" restart backend >/dev/null
wait_for_backend "${backend_id}"
purpose="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
	-c "SELECT purpose FROM wish WHERE id = '00000000-0000-0000-0000-000000000401'")"
[[ "${purpose}" == "persistent mutation" ]] || { printf 'ordinary restart did not preserve Demo mutation\n' >&2; exit 1; }

"${compose[@]}" stop backend >/dev/null
reset_output="$("${compose[@]}" --profile reset run --rm demo-reset 2>&1)"
grep -q 'CRABIT_DEMO_RESET_COMPLETED' <<<"${reset_output}" \
	|| { printf 'one-shot reset did not emit its completion marker\n' >&2; exit 1; }
purpose="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
	-c "SELECT purpose FROM wish WHERE id = '00000000-0000-0000-0000-000000000401'")"
[[ "${purpose}" == "노트북" ]] || { printf 'one-shot reset did not restore canonical fixture\n' >&2; exit 1; }

printf 'runtime verified: profile=demo swagger=json,yaml persistence=preserved reset=restored\n'
