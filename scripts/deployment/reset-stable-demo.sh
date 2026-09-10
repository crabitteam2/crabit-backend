#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
prepare_deployment_context
validate_recap_runtime_binding "${RUNTIME_ENV}"

[[ "$(env_value CRABIT_SPRING_PROFILE "${RUNTIME_ENV}")" == "demo" ]] \
	|| die "reset is allowed only for a demo environment"
exec 9>"${STATE_DIR}/operations.lock"
flock -n 9 || die "another deployment or reset operation is active"
validate_snapshot_proof "${SNAPSHOT_PROOF}"
validate_release_env "${CURRENT_RELEASE_ENV}"
current_backend_image="$(env_value CRABIT_BACKEND_IMAGE "${CURRENT_RELEASE_ENV}")"
current_recap_image="$(env_value CRABIT_RECAP_IMAGE "${CURRENT_RELEASE_ENV}")"

# Shell overrides also protect legacy releases from a future runtime opt-in.
export CRABIT_BACKEND_IMAGE="${current_backend_image}"
export CRABIT_RECAP_IMAGE="${current_recap_image}"
export CRABIT_FEED_RANKING_ENABLED="$(feed_enabled "${CURRENT_RELEASE_ENV}")"
export CRABIT_FEED_CLASSIFIER_VERSION=""
export CRABIT_FEED_RANKING_CREDENTIAL=""
export CRABIT_FEED_RANKING_URL=http://feed:8081/internal/v1/feed-rankings
if [[ "${CRABIT_FEED_RANKING_ENABLED}" == true ]]; then
	export CRABIT_FEED_CLASSIFIER_VERSION="$(env_value CRABIT_FEED_CLASSIFIER_VERSION "${CURRENT_RELEASE_ENV}")"
	export CRABIT_FEED_RANKING_CREDENTIAL="$(env_value CRABIT_FEED_RANKING_CREDENTIAL "${RUNTIME_ENV}")"
	[[ "${CRABIT_FEED_RANKING_CREDENTIAL}" =~ ^[A-Za-z0-9._:/@+-]+$ \
		&& "${CRABIT_FEED_RANKING_CREDENTIAL}" != "$(env_value CRABIT_RECAP_GENERATION_CREDENTIAL "${RUNTIME_ENV}")" ]] \
		|| die "Resetting an enabled feed release requires its dedicated credential"
	bash "${DEPLOYMENT_SCRIPT_DIR}/verify-feed-runtime.sh" \
		"${current_backend_image}" "${current_recap_image}" "${CRABIT_FEED_CLASSIFIER_VERSION}"
fi

compose=(docker compose --env-file "${RUNTIME_ENV}" --env-file "${CURRENT_RELEASE_ENV}" -f "${COMPOSE_FILE}")
"${compose[@]}" config --quiet
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
reservation_log="$(mktemp "${STATE_DIR}/recap-reservation.XXXXXX.log")"
weekly_response="$(mktemp "${STATE_DIR}/weekly-recap.XXXXXX.json")"
monthly_response="$(mktemp "${STATE_DIR}/monthly-recap.XXXXXX.json")"
default_response="$(mktemp "${STATE_DIR}/default-recap.XXXXXX.json")"
backend_restarted=false
reset_completed=false
cleanup_reset() {
	rm -f "${reset_log}" "${reservation_log}" "${weekly_response}" \
		"${monthly_response}" "${default_response}"
	if [[ "${backend_restarted}" == true && "${reset_completed}" != true ]]; then
		"${compose[@]}" stop backend >/dev/null 2>&1 || true
	fi
}
trap cleanup_reset EXIT
if ! "${compose[@]}" --profile reset run --rm demo-reset >"${reset_log}" 2>&1; then
	die "Demo reset failed; backend remains stopped for operator intervention"
fi
fixture_receipt_pattern='^CRABIT_DEMO_FIXTURE_RESET_COMPLETED account_id=[0-9a-f-]{36} weekly_start=[0-9]{4}-[0-9]{2}-[0-9]{2} weekly_end=[0-9]{4}-[0-9]{2}-[0-9]{2} monthly_start=[0-9]{4}-[0-9]{2}-[0-9]{2} monthly_end=[0-9]{4}-[0-9]{2}-[0-9]{2} weekly_request_key=[0-9a-f-]{36} monthly_request_key=[0-9a-f-]{36}$'
[[ "$(grep -Ec "${fixture_receipt_pattern}" "${reset_log}")" == "1" ]] \
	|| die "Demo reset returned no unique period-bound fixture receipt"
fixture_receipt="$(grep -E "${fixture_receipt_pattern}" "${reset_log}")"
read -r fixture_marker account_field weekly_start_field weekly_end_field \
	monthly_start_field monthly_end_field weekly_key_field monthly_key_field \
	<<<"${fixture_receipt}"
[[ "${fixture_marker}" == "CRABIT_DEMO_FIXTURE_RESET_COMPLETED" ]] \
	|| die "Demo fixture receipt marker is invalid"
account_id="${account_field#account_id=}"
weekly_start="${weekly_start_field#weekly_start=}"
weekly_end="${weekly_end_field#weekly_end=}"
monthly_start="${monthly_start_field#monthly_start=}"
monthly_end="${monthly_end_field#monthly_end=}"
weekly_request_key="${weekly_key_field#weekly_request_key=}"
monthly_request_key="${monthly_key_field#monthly_request_key=}"

reserve_recap() {
	local kind="$1"
	local period="$2"
	local request_key="$3"
	if ! "${compose[@]}" --profile reset run --rm --no-deps --entrypoint java demo-reset \
			-Dloader.main=com.crabit.backend.recap.RecapRegenerationCommand \
			-cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
			"--account=${account_id}" "--kind=${kind}" "--period=${period}" \
			"--request-key=${request_key}" >"${reservation_log}" 2>&1; then
		die "${kind} recap reservation failed; backend remains stopped for operator intervention"
	fi
	grep -Eq '^CRABIT_RECAP_RESERVED generation_id=[0-9a-f-]{36} generation_version=[1-9][0-9]*$' \
		"${reservation_log}" \
		|| die "${kind} recap reservation returned no valid receipt"
}

reserve_recap WEEKLY "${weekly_start}" "${weekly_request_key}"
reserve_recap MONTHLY "${monthly_start:0:7}" "${monthly_request_key}"

backend_restarted=true
if [[ "${CRABIT_FEED_RANKING_ENABLED}" == true ]]; then
	"${compose[@]}" up -d feed >/dev/null
	feed_id="$("${compose[@]}" ps -q feed)"
	[[ -n "${feed_id}" ]] || die "Stable Demo reset did not create feed"
	wait_for_service_health "${feed_id}" feed
fi
"${compose[@]}" up -d backend caddy >/dev/null
backend_id="$("${compose[@]}" ps -q backend)"
recap_id="$("${compose[@]}" ps -q recap)"
wait_for_service_health "${recap_id}" recap
wait_for_service_health "${backend_id}" backend
[[ "$(docker inspect --format '{{.Config.Image}}' "${backend_id}")" == "${current_backend_image}" \
	&& "$(docker inspect --format '{{.Config.Image}}' "${recap_id}")" == "${current_recap_image}" ]] \
	|| die "Stable Demo reset restarted a different release pair"
public_host="$(env_value CRABIT_PUBLIC_HOST "${RUNTIME_ENV}")"
verify_https_readiness "${public_host}"
if [[ "${CRABIT_FEED_RANKING_ENABLED}" == true ]]; then
	feed_id="$("${compose[@]}" ps -q feed)"
	[[ -n "${feed_id}" ]] || die "Stable Demo reset feed is missing"
	wait_for_service_health "${feed_id}" feed
	[[ "$(docker inspect --format '{{.Config.Image}}' "${feed_id}")" == "${current_recap_image}" ]] \
		|| die "Stable Demo reset restarted a different feed image"
	verify_local_registry_digest "${current_recap_image}"
fi

owner_token="$(env_value CRABIT_DEMO_TOKEN_OWNER "${RUNTIME_ENV}")"
[[ -n "${owner_token}" ]] || die "Stable Demo Owner token must not be blank"

fetch_recap() {
	local kind="$1"
	local query="$2"
	local target="$3"
	local suffix
	case "${kind}" in
		WEEKLY) suffix="weekly" ;;
		MONTHLY) suffix="monthly" ;;
		*) die "Unsupported recap read-back kind" ;;
	esac
	curl --fail --silent --show-error --max-time 5 \
		--header "Authorization: Bearer ${owner_token}" \
		"https://${public_host}/v1/card-balance-accounts/${account_id}/recaps/${suffix}${query}" \
		>"${target}"
}

validate_succeeded_recap() {
	local kind="$1"
	local period_start="$2"
	local period_end="$3"
	local response="$4"
	jq -e --arg kind "${kind}" --arg start "${period_start}" --arg end "${period_end}" '
		.kind == $kind and .status == "SUCCEEDED" and
		.period.startDate == $start and .period.endDateExclusive == $end and
		.period.timezone == "Asia/Seoul" and
		(.generationVersion | type == "number") and .generationVersion > 0 and
		.schemaVersion == 1 and .algorithmVersion == "recap-1" and
		(.generatedAt | type == "string") and (.generatedAt | length > 0) and
		(.result | type == "object")
	' "${response}" >/dev/null
}

wait_for_succeeded_recap() {
	local kind="$1"
	local period_start="$2"
	local period_end="$3"
	local query="$4"
	local target="$5"
	local status
	for _ in $(seq 1 120); do
		fetch_recap "${kind}" "${query}" "${target}" \
			|| die "${kind} Owner recap read-back failed"
		status="$(jq -er '.status | select(type == "string")' "${target}")" \
			|| die "${kind} Owner recap read-back was malformed"
		case "${status}" in
			SUCCEEDED)
				validate_succeeded_recap "${kind}" "${period_start}" "${period_end}" "${target}" \
					|| die "${kind} Owner recap success read-back was malformed"
				return 0
				;;
			NOT_GENERATED|GENERATING) ;;
			*) die "${kind} Owner recap entered non-success state ${status}" ;;
		esac
		sleep 2
	done
	die "${kind} Owner recap did not succeed before timeout"
}

verify_default_recap() {
	local kind="$1"
	local period_start="$2"
	local period_end="$3"
	local explicit_response="$4"
	local generation_version
	fetch_recap "${kind}" "" "${default_response}" \
		|| die "${kind} default Owner recap read-back failed"
	validate_succeeded_recap "${kind}" "${period_start}" "${period_end}" "${default_response}" \
		|| die "${kind} default Owner recap selected the wrong persisted result"
	generation_version="$(jq -er '.generationVersion' "${explicit_response}")"
	jq -e --argjson version "${generation_version}" '.generationVersion == $version' \
		"${default_response}" >/dev/null \
		|| die "${kind} default Owner recap selected a different generation"
}

wait_for_succeeded_recap WEEKLY "${weekly_start}" "${weekly_end}" \
	"?weekStart=${weekly_start}" "${weekly_response}"
wait_for_succeeded_recap MONTHLY "${monthly_start}" "${monthly_end}" \
	"?month=${monthly_start:0:7}" "${monthly_response}"
verify_default_recap WEEKLY "${weekly_start}" "${weekly_end}" "${weekly_response}"
verify_default_recap MONTHLY "${monthly_start}" "${monthly_end}" "${monthly_response}"
unset owner_token

printf 'CRABIT_DEMO_RESET_COMPLETED\n'
reset_completed=true
