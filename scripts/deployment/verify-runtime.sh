#!/usr/bin/env bash
set -Eeuo pipefail

[[ "$#" == "2" ]] || {
	printf 'usage: verify-runtime.sh <local-backend-image> <local-recap-image>\n' >&2
	exit 2
}
readonly BACKEND_IMAGE="$1"
readonly RECAP_IMAGE="$2"
readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ENV_NAME="verify-${$}"
readonly PROJECT="crabit-${ENV_NAME}"
readonly RECAP_TOKEN="verify_recap_generation_secret_32_chars"
readonly OWNER_TOKEN="verify_owner_secret"
readonly GENERATION_ID="00000000-0000-4000-8000-000000009001"
readonly OWNER_ID="00000000-0000-0000-0000-000000000201"
readonly ACCOUNT_ID="00000000-0000-0000-0000-000000000301"
readonly ACADEMY_ID="00000000-0000-0000-0000-000000000101"
readonly WISH_ID="00000000-0000-0000-0000-000000000401"
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
CRABIT_BACKEND_IMAGE=${BACKEND_IMAGE}
CRABIT_RECAP_IMAGE=${RECAP_IMAGE}
CRABIT_RECAP_GENERATION_CREDENTIAL=${RECAP_TOKEN}
CRABIT_RECAP_GENERATION_POLL_DELAY_MS=500
CRABIT_DEMO_TOKEN_OWNER=${OWNER_TOKEN}
CRABIT_DEMO_TOKEN_FRIEND=verify_friend_secret
CRABIT_DEMO_TOKEN_NONFRIEND=verify_nonfriend_secret
CRABIT_DEMO_TOKEN_BLOCKED=verify_blocked_secret
CRABIT_DEMO_TOKEN_OTHER_ACADEMY=verify_other_academy_secret
CRABIT_DEMO_TOKEN_STAFF=verify_staff_secret
CRABIT_DEMO_BALANCE_PROVIDER_URL=https://demo-console.example/api/provider/balance-lookups
CRABIT_DEMO_BALANCE_PROVIDER_TOKEN=verify_demo_balance_provider_secret
CRABIT_GCP_PROJECT_ID=crabit-verify-project
CRABIT_GCP_ZONE=asia-northeast3-a
CRABIT_GCP_INSTANCE=crabit-${ENV_NAME}
CRABIT_GCP_DATA_DISK=crabit-${ENV_NAME}-data
EOF
chmod 0600 "${env_file}"
compose=(docker compose --env-file "${env_file}" -f "${ROOT}/deploy/compose.yaml")

cleanup() {
	"${compose[@]}" --profile reset down --volumes --remove-orphans >/dev/null 2>&1 || true
	rm -rf "${tmp_dir}"
}
trap cleanup EXIT

runtime_diagnostics() {
	printf 'runtime diagnostics (no application payloads or credentials):\n' >&2
	"${compose[@]}" ps >&2 || true
	for service in backend recap postgres; do
		container_id="$("${compose[@]}" ps -q "${service}" 2>/dev/null || true)"
		[[ -z "${container_id}" ]] || docker inspect --format \
			'{{.Name}} state={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} image={{.Config.Image}}' \
			"${container_id}" >&2 || true
	done
}

wait_for_service() {
	local service="$1"
	local container_id status
	container_id="$("${compose[@]}" ps -q "${service}")"
	[[ -n "${container_id}" ]] || {
		printf '%s container was not created\n' "${service}" >&2
		runtime_diagnostics
		return 1
	}
	for _ in $(seq 1 60); do
		status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' \
			"${container_id}")"
		[[ "${status}" == "healthy" ]] && return 0
		if [[ "${status}" == "unhealthy" ]]; then
			printf '%s became unhealthy\n' "${service}" >&2
			runtime_diagnostics
			return 1
		fi
		sleep 2
	done
	printf '%s readiness timed out\n' "${service}" >&2
	runtime_diagnostics
	return 1
}

container_environment_value() {
	local container_id="$1"
	local key="$2"
	docker inspect --format '{{json .Config.Env}}' "${container_id}" \
		| jq -er --arg prefix "${key}=" \
			'[.[] | select(startswith($prefix)) | ltrimstr($prefix)] | if length == 1 then .[0] else error("missing or duplicate environment key") end'
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

sha256_stdin() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum | awk '{print $1}'
	else
		shasum -a 256 | awk '{print $1}'
	fi
}

lookup_weekly_recap() {
	"${compose[@]}" exec -T backend wget -q -O - \
		--header="Authorization: Bearer ${OWNER_TOKEN}" \
		"http://127.0.0.1:8080/v1/card-balance-accounts/${ACCOUNT_ID}/recaps/weekly?weekStart=2026-08-24"
}

"${compose[@]}" config --quiet
"${compose[@]}" up -d postgres recap >/dev/null
wait_for_service recap
"${compose[@]}" up -d backend >/dev/null
wait_for_service backend
verify_swagger_documents || { runtime_diagnostics; exit 1; }

backend_id="$("${compose[@]}" ps -q backend)"
recap_id="$("${compose[@]}" ps -q recap)"
postgres_id="$("${compose[@]}" ps -q postgres)"
for service in backend recap postgres; do
	container_id="$("${compose[@]}" ps -q "${service}")"
	port_bindings="$(docker inspect --format '{{json .HostConfig.PortBindings}}' "${container_id}")"
	[[ "${port_bindings}" == "null" || "${port_bindings}" == "{}" ]] \
		|| { printf '%s unexpectedly publishes a host port\n' "${service}" >&2; exit 1; }
done

docker inspect --format '{{json .NetworkSettings.Networks}}' "${recap_id}" \
	| jq -e --arg recap "${PROJECT}_recap" 'keys == [$recap]' >/dev/null
docker inspect --format '{{json .NetworkSettings.Networks}}' "${postgres_id}" \
	| jq -e --arg database "${PROJECT}_database" 'keys == [$database]' >/dev/null
docker inspect --format '{{json .NetworkSettings.Networks}}' "${backend_id}" \
	| jq -e --arg edge "${PROJECT}_edge" --arg database "${PROJECT}_database" --arg recap "${PROJECT}_recap" \
		'(keys | sort) == ([$edge, $database, $recap] | sort)' >/dev/null

recap_user="$(docker inspect --format '{{.Config.User}}' "${recap_id}")"
[[ -n "${recap_user}" && "${recap_user}" != "0" && "${recap_user}" != "root" ]]
[[ "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' "${recap_id}")" == "true" ]]
[[ "$(docker inspect --format '{{.HostConfig.Privileged}}' "${recap_id}")" == "false" ]]
docker inspect --format '{{json .HostConfig.CapAdd}}' "${recap_id}" \
	| jq -e '. == null or . == []' >/dev/null
docker inspect --format '{{json .HostConfig.SecurityOpt}}' "${recap_id}" \
	| jq -e 'index("no-new-privileges:true") != null' >/dev/null

backend_recap_token="$(container_environment_value "${backend_id}" CRABIT_RECAP_GENERATION_CREDENTIAL)"
recap_token="$(container_environment_value "${recap_id}" CRABIT_RECAP_TOKEN)"
[[ -n "${backend_recap_token}" && "${backend_recap_token}" == "${recap_token}" ]]
unset backend_recap_token recap_token
[[ "$(container_environment_value "${backend_id}" CRABIT_RECAP_GENERATION_ENABLED)" == "true" ]]
[[ "$(container_environment_value "${backend_id}" CRABIT_RECAP_GENERATION_URL)" \
	== "http://recap:8081/internal/v1/recap-generations" ]]

digestable="$(jq -cnS \
	--arg student "${OWNER_ID}" --arg account "${ACCOUNT_ID}" --arg academy "${ACADEMY_ID}" --arg wish "${WISH_ID}" \
	'{schema_version:1,algorithm_version:"recap-1",student_id:$student,card_balance_account_id:$account,
	 academy_id:$academy,kind:"WEEKLY",period:{start_date:"2026-08-24",end_date_exclusive:"2026-08-31",timezone:"Asia/Seoul"},
	 reference_date:"2026-08-30",snapshot_at:"2026-08-31T00:00:00Z",input:{representative_wish_id:$wish,
	 wishes:[{wish_id:$wish,title:"runtime wish",target_amount:1500000,created_at:"2026-08-16T00:00:00Z",
	 closed_at:null,deleted_at:null,status:"IN_PROGRESS",is_representative:true,saved_amount_at_period_end:250000}],
	 effective_transactions:[],visit_metrics:{received_visit_count:0,unique_received_visitor_count:0,
	 previous_week_received_visit_count:0,monthly_outgoing_visit_count:0},
	 peer_metrics:{habit_active_weeks:[],achievement_rates:[]},success_story_candidates:[]}}')"
input_digest="sha256:$(printf '%s' "${digestable}" | sha256_stdin)"
request_json="$(jq -cnS --argjson input "${digestable}" --arg generation "${GENERATION_ID}" \
	--arg digest "${input_digest}" '$input + {generation_id:$generation,input_digest:$digest}')"

"${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U crabit -d crabit_verify \
	-v generation_id="${GENERATION_ID}" -v account_id="${ACCOUNT_ID}" -v student_id="${OWNER_ID}" \
	-v academy_id="${ACADEMY_ID}" -v input_digest="${input_digest}" -v request_json="${request_json}" <<'SQL' >/dev/null
INSERT INTO recap_generation (
    id, account_id, student_id, academy_id, kind, period_start, period_end_exclusive,
    schema_version, algorithm_version, generation_version, input_digest, request_json,
    state, attempt_count, created_at, current_version
) VALUES (
    :'generation_id'::uuid, :'account_id'::uuid, :'student_id'::uuid, :'academy_id'::uuid,
    'WEEKLY', DATE '2026-08-24', DATE '2026-08-31', 1, 'recap-1', 1,
    :'input_digest', :'request_json', 'PENDING', 0, CURRENT_TIMESTAMP, FALSE
);
SQL
unset request_json digestable

generation_state=""
for _ in $(seq 1 90); do
	generation_state="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
		-c "SELECT state FROM recap_generation WHERE id='${GENERATION_ID}'")"
	case "${generation_state}" in
		SUCCEEDED) break ;;
		FAILED)
			error_code="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
				-c "SELECT COALESCE(error_code, 'UNKNOWN') FROM recap_generation WHERE id='${GENERATION_ID}'")"
			printf 'real recap generation failed: %s\n' "${error_code}" >&2
			exit 1
			;;
	esac
	sleep 1
done
[[ "${generation_state}" == "SUCCEEDED" ]] \
	|| { printf 'real recap generation did not succeed before timeout\n' >&2; exit 1; }

generation_proof="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
	-c "SELECT count(*) || ':' || bool_and(current_version) || ':' || bool_and(view_json IS NOT NULL) FROM recap_generation WHERE id='${GENERATION_ID}'")"
[[ "${generation_proof}" == "1:true:true" ]]
# The dedicated reservation process must not initialize/reset demo fixtures or start a worker.
readonly REGENERATION_KEY="00000000-0000-4000-8000-000000009002"
legacy_result="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
  -c "SELECT md5(request_json || view_json || internal_metrics_json) FROM recap_generation WHERE id='${GENERATION_ID}'")"
for _ in 1 2; do
  "${compose[@]}" run --rm --no-deps --entrypoint java backend \
    -Dloader.main=com.crabit.backend.recap.RecapRegenerationCommand -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    "--account=${ACCOUNT_ID}" --kind=WEEKLY --period=2026-08-24 "--request-key=${REGENERATION_KEY}" \
    >"${tmp_dir}/reservation.log" 2>&1
  grep -q 'CRABIT_RECAP_RESERVED' "${tmp_dir}/reservation.log"
done
for _ in $(seq 1 90); do
  regeneration_state="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
    -c "SELECT state FROM recap_generation WHERE reservation_key='explicit:${REGENERATION_KEY}'")"
  [[ "${regeneration_state}" == "SUCCEEDED" ]] && break
  [[ "${regeneration_state}" != "FAILED" ]] || { printf 'prepared regeneration failed\n' >&2; exit 1; }
  sleep 1
done
[[ "${regeneration_state}" == "SUCCEEDED" ]]
regeneration_proof="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
  -c "SELECT count(*) || ':' || bool_and(stage='GENERATION' AND preparation_attempt_count=1 AND attempt_count=1 AND current_version AND generation_version=2 AND request_json IS NOT NULL) FROM recap_generation WHERE reservation_key='explicit:${REGENERATION_KEY}'")"
[[ "${regeneration_proof}" == "1:true" ]]
[[ "$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
  -c "SELECT md5(request_json || view_json || internal_metrics_json) FROM recap_generation WHERE id='${GENERATION_ID}' AND NOT current_version")" == "${legacy_result}" ]]

first_recap="${tmp_dir}/weekly-first.json"
lookup_weekly_recap >"${first_recap}"
jq -e '.kind == "WEEKLY" and .status == "SUCCEEDED" and .generationVersion == 2 and
	.schemaVersion == 1 and .algorithmVersion == "recap-1" and (.result | type == "object")' \
	"${first_recap}" >/dev/null

"${compose[@]}" restart backend >/dev/null
wait_for_service backend
second_recap="${tmp_dir}/weekly-after-restart.json"
lookup_weekly_recap >"${second_recap}"
jq -S . "${first_recap}" >"${tmp_dir}/weekly-first.canonical.json"
jq -S . "${second_recap}" >"${tmp_dir}/weekly-after-restart.canonical.json"
cmp -s "${tmp_dir}/weekly-first.canonical.json" "${tmp_dir}/weekly-after-restart.canonical.json" \
	|| { printf 'stored recap changed after backend restart\n' >&2; exit 1; }

"${compose[@]}" stop recap >/dev/null
wait_for_service backend
lookup_weekly_recap | jq -e '.status == "SUCCEEDED" and (.result | type == "object")' >/dev/null
"${compose[@]}" up -d recap >/dev/null
wait_for_service recap

backend_id_before="$("${compose[@]}" ps -q backend)"
recap_id_before="$("${compose[@]}" ps -q recap)"
"${compose[@]}" up -d recap backend >/dev/null
wait_for_service recap
wait_for_service backend
[[ "$("${compose[@]}" ps -q backend)" == "${backend_id_before}" \
	&& "$("${compose[@]}" ps -q recap)" == "${recap_id_before}" ]] \
	|| { printf 'repeat-safe Compose activation recreated the release pair\n' >&2; exit 1; }

"${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U crabit -d crabit_verify \
	-c "UPDATE wish SET purpose = 'persistent mutation' WHERE id = '${WISH_ID}'" >/dev/null
lookup_weekly_recap >"${tmp_dir}/weekly-after-source-change.json"
jq -S . "${tmp_dir}/weekly-after-source-change.json" >"${tmp_dir}/weekly-after-source-change.canonical.json"
cmp -s "${tmp_dir}/weekly-first.canonical.json" "${tmp_dir}/weekly-after-source-change.canonical.json"
"${compose[@]}" stop backend >/dev/null
reset_output="$("${compose[@]}" --profile reset run --rm demo-reset 2>&1)"
grep -q 'CRABIT_DEMO_RESET_COMPLETED' <<<"${reset_output}" \
	|| { printf 'one-shot reset did not emit its completion marker\n' >&2; exit 1; }
[[ "$("${compose[@]}" ps -q recap)" == "${recap_id_before}" ]]
"${compose[@]}" up -d backend >/dev/null
wait_for_service backend
purpose="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
	-c "SELECT purpose FROM wish WHERE id = '${WISH_ID}'")"
[[ "${purpose}" == "노트북" ]] || { printf 'one-shot reset did not restore canonical fixture\n' >&2; exit 1; }
recap_rows="$("${compose[@]}" exec -T postgres psql -At -U crabit -d crabit_verify \
	-c 'SELECT count(*) FROM recap_generation')"
[[ "${recap_rows}" == "0" ]] || { printf 'one-shot reset retained generated recap state\n' >&2; exit 1; }
lookup_weekly_recap | jq -e '.status == "NOT_GENERATED" and .result == null' >/dev/null

printf 'runtime verified: private_recap=true generation=succeeded preparation=frozen regeneration=idempotent storage=persisted failure_isolated=true repeat_safe=true reset=restored\n'
