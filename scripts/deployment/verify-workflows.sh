#!/usr/bin/env bash
set -Eeuo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly WORKFLOWS="${ROOT}/.github/workflows"

for command in docker jq rg; do
	command -v "${command}" >/dev/null 2>&1 || { printf 'missing command: %s\n' "${command}" >&2; exit 1; }
done
for script in "${ROOT}"/scripts/deployment/*.sh; do bash -n "${script}"; done
for script in "${ROOT}"/scripts/deployment/google-cloud/*.sh; do bash -n "${script}"; done
bash -n "${ROOT}/deploy/google-cloud/bootstrap-host.sh"

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
grep -q -- 'docker build --provenance=false' "${publish}"
grep -Fq 'group: backend-publication-${{ github.sha }}' "${publish}"
grep -A1 -F 'group: backend-publication-${{ github.sha }}' "${publish}" \
	| grep -q 'cancel-in-progress: false'
grep -q 'workflow_dispatch' "${staging}"
! grep -Eq '^[[:space:]]+(push|workflow_run):' "${staging}"
grep -q 'origin/develop' "${staging}"
grep -q 'workflow_dispatch' "${reset}"
! grep -Eq '^[[:space:]]+(push|workflow_run|schedule):' "${reset}"
for workflow in "${publish}" "${reset}"; do
	grep -q 'CRABIT_DEMO_BALANCE_PROVIDER_URL' "${workflow}"
	grep -q 'CRABIT_DEMO_BALANCE_PROVIDER_TOKEN' "${workflow}"
done
for workflow in "${publish}" "${staging}" "${reset}"; do
	grep -q 'google-github-actions/auth@v3' "${workflow}"
	grep -q 'google-github-actions/setup-gcloud@v3' "${workflow}"
	grep -q 'id-token: write' "${workflow}"
	grep -q 'create-snapshot.sh' "${workflow}"
	grep -q 'run-over-iap.sh' "${workflow}"
	! grep -Eq 'DEPLOY_SSH_PRIVATE_KEY|GOOGLE_APPLICATION_CREDENTIALS|credentials_json' "${workflow}"
done
grep -q 'group: staging-operations' "${staging}"
grep -q 'group: stable-demo-operations' "${reset}"
grep -q 'group: stable-demo-operations' "${publish}"
grep -q 'reset-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}' "${reset}"

temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
config_file="${temporary_directory}/compose-config.json"

publish_step="${temporary_directory}/publish-step.sh"
awk '
	$0 == "      - name: Publish or adopt immutable commit tag" { found = 1; next }
	found && $0 == "        run: |" { copying = 1; next }
	copying && (/^      - name:/ || /^  [A-Za-z0-9_-]+:/) { exit }
	copying { sub(/^          /, ""); print }
' "${publish}" > "${publish_step}"
grep -q '^set -Eeuo pipefail$' "${publish_step}"

mkdir "${temporary_directory}/bin" "${temporary_directory}/state" \
	"${temporary_directory}/runner"
fake_docker="${temporary_directory}/bin/docker"
cat > "${fake_docker}" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail

readonly local_id="sha256:1111111111111111111111111111111111111111111111111111111111111111"
readonly different_id="sha256:2222222222222222222222222222222222222222222222222222222222222222"
readonly first_digest="sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
readonly moved_digest="sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
readonly resolution_count_file="${FAKE_DOCKER_STATE}/mutable-resolution-count"
readonly push_count_file="${FAKE_DOCKER_STATE}/push-count"

if [[ "$1" == "manifest" && "$2" == "inspect" ]]; then
	if [[ "$3" == "${IMAGE_REPOSITORY}@"* ]]; then
		if [[ "${FAKE_DOCKER_SCENARIO}" == "index-digest" ]]; then
			printf '%s\n' '{"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[]}'
		else
			config_digest="${local_id}"
			if [[ "${FAKE_DOCKER_SCENARIO}" == "same-label-different-image" \
					|| "${FAKE_DOCKER_SCENARIO}" == "pushed-digest-different-image" ]]; then
				config_digest="${different_id}"
			fi
			printf '{"schemaVersion":2,"mediaType":"application/vnd.docker.distribution.manifest.v2+json","config":{"digest":"%s"}}\n' \
				"${config_digest}"
		fi
		exit 0
	fi
	count=0
	[[ ! -f "${resolution_count_file}" ]] || count="$(<"${resolution_count_file}")"
	printf '%s\n' "$((count + 1))" > "${resolution_count_file}"
	if [[ "${FAKE_DOCKER_SCENARIO}" == "lookup-error" ]]; then
		printf 'unauthorized: authentication required\n' >&2
		exit 1
	fi
	if [[ "${FAKE_DOCKER_SCENARIO}" == "missing-tag" \
			|| "${FAKE_DOCKER_SCENARIO}" == "pushed-digest-different-image" ]]; then
		printf 'manifest unknown: manifest unknown\n' >&2
		exit 1
	fi
	exit 0
fi
if [[ "$1" == "pull" ]]; then
	if [[ "$2" == "${IMAGE_REPOSITORY}:sha-${GITHUB_SHA:0:12}" ]]; then
		count=0
		[[ ! -f "${resolution_count_file}" ]] || count="$(<"${resolution_count_file}")"
		count=$((count + 1))
		printf '%s\n' "${count}" > "${resolution_count_file}"
		if [[ "${FAKE_DOCKER_SCENARIO}" == "lookup-error" ]]; then
			printf 'unauthorized: authentication required\n' >&2
			exit 1
		fi
		if [[ "${FAKE_DOCKER_SCENARIO}" == "missing-tag" \
					|| "${FAKE_DOCKER_SCENARIO}" == "pushed-digest-different-image" ]]; then
			printf 'manifest unknown: manifest unknown\n' >&2
			exit 1
		fi
		digest="${first_digest}"
		if [[ "${FAKE_DOCKER_SCENARIO}" == "same-label-different-image" \
				|| "${count}" -ne 1 ]]; then
			digest="${moved_digest}"
		fi
		printf 'Digest: %s\n' "${digest}"
		exit 0
	fi
	if [[ "$2" == "${IMAGE_REPOSITORY}@"* ]]; then
		printf 'Digest: %s\n' "${2#*@}"
		exit 0
	fi
fi
if [[ "$1" == "image" && "$2" == "inspect" ]]; then
	reference="$3"
	format="${5:-}"
	if [[ "${reference}" == "crabit-backend:sha-${GITHUB_SHA:0:12}" ]]; then
		printf '%s\n' "${local_id}"
		exit 0
	fi
	if [[ "${format}" == *org.opencontainers.image.revision* ]]; then
		printf '%s\n' "${GITHUB_SHA}"
		exit 0
	fi
	if [[ "${format}" == *RepoDigests* ]]; then
		count="$(<"${resolution_count_file}")"
		digest="${first_digest}"
		[[ "${count}" -eq 1 ]] || digest="${moved_digest}"
		printf '["%s@%s"]\n' "${IMAGE_REPOSITORY}" "${digest}"
		exit 0
	fi
	if [[ "${reference}" == "${IMAGE_REPOSITORY}@"* ]]; then
		if [[ "${FAKE_DOCKER_SCENARIO}" == "same-label-different-image" ]]; then
			printf '%s\n' "${different_id}"
		else
			printf '%s\n' "${local_id}"
		fi
		exit 0
	fi
fi
if [[ "$1" == "tag" ]]; then
	exit 0
fi
if [[ "$1" == "push" ]]; then
	count=0
	[[ ! -f "${push_count_file}" ]] || count="$(<"${push_count_file}")"
	printf '%s\n' "$((count + 1))" > "${push_count_file}"
	digest="${first_digest}"
	[[ "${FAKE_DOCKER_SCENARIO}" != "pushed-digest-different-image" ]] \
		|| digest="${moved_digest}"
	printf '%s: digest: %s size: 1\n' "$2" "${digest}"
	exit 0
fi
printf 'unexpected fake docker invocation: %q' "$1" >&2
printf ' %q' "${@:2}" >&2
printf '\n' >&2
exit 64
FAKE_DOCKER
chmod 700 "${fake_docker}"

run_publish_step() {
	local scenario="$1"
	local output_file="${temporary_directory}/${scenario}.output"
	rm -f "${temporary_directory}/state/"* "${output_file}"
	printf '{"containerimage.digest":"%s","containerimage.config.digest":"%s"}\n' \
		'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc' \
		'sha256:1111111111111111111111111111111111111111111111111111111111111111' \
		> "${temporary_directory}/runner/tested-image-metadata.json"
	PATH="${temporary_directory}/bin:${PATH}" \
	FAKE_DOCKER_SCENARIO="${scenario}" \
	FAKE_DOCKER_STATE="${temporary_directory}/state" \
	GITHUB_SHA=0123456789abcdef0123456789abcdef01234567 \
	GITHUB_OUTPUT="${output_file}" \
	IMAGE_REPOSITORY=crabitteam2/crabit-backend \
	RUNNER_TEMP="${temporary_directory}/runner" \
		bash "${publish_step}"
}

if run_publish_step same-label-different-image >"${temporary_directory}/different.log" 2>&1; then
	printf 'publication accepted a same-label image with different tested bytes\n' >&2
	exit 1
fi
grep -q 'does not match the locally tested image' "${temporary_directory}/different.log" || {
	cat "${temporary_directory}/different.log" >&2
	printf 'publication did not reject the different image for byte identity\n' >&2
	exit 1
}

run_publish_step registry-rewritten-digest \
	>"${temporary_directory}/registry-rewritten.log" 2>&1
grep -qx 'image_digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
	"${temporary_directory}/registry-rewritten-digest.output"
[[ "$(<"${temporary_directory}/state/mutable-resolution-count")" -eq 1 ]] || {
	printf 'publication resolved the mutable commit tag more than once\n' >&2
	exit 1
}

if run_publish_step lookup-error >"${temporary_directory}/lookup-error.log" 2>&1; then
	printf 'publication treated a failed registry lookup as an absent tag\n' >&2
	exit 1
fi
[[ ! -f "${temporary_directory}/state/push-count" ]] || {
	printf 'publication pushed after a failed registry lookup\n' >&2
	exit 1
}
grep -q 'registry tag lookup failed' "${temporary_directory}/lookup-error.log" || {
	cat "${temporary_directory}/lookup-error.log" >&2
	printf 'publication did not fail closed with a lookup diagnostic\n' >&2
	exit 1
}

run_publish_step missing-tag >"${temporary_directory}/missing-tag.log" 2>&1
grep -qx 'image_digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
	"${temporary_directory}/missing-tag.output"
[[ "$(<"${temporary_directory}/state/mutable-resolution-count")" -eq 1 ]]
[[ "$(<"${temporary_directory}/state/push-count")" -eq 1 ]]

if run_publish_step pushed-digest-different-image \
		>"${temporary_directory}/pushed-different.log" 2>&1; then
	printf 'publication accepted a pushed digest with different image bytes\n' >&2
	exit 1
fi
grep -q 'does not match the locally tested image' \
	"${temporary_directory}/pushed-different.log"
[[ "$(<"${temporary_directory}/state/push-count")" -eq 1 ]]

if run_publish_step index-digest >"${temporary_directory}/index.log" 2>&1; then
	printf 'publication accepted a multi-platform index with untested children\n' >&2
	exit 1
fi
grep -q 'single-platform image manifest' "${temporary_directory}/index.log"

readiness_bin="${temporary_directory}/readiness-bin"
readiness_state="${temporary_directory}/readiness-state"
mkdir "${readiness_bin}" "${readiness_state}"

cat > "${readiness_bin}/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -Eeuo pipefail

[[ "$#" == "6" \
	&& "$1" == "--fail" \
	&& "$2" == "--silent" \
	&& "$3" == "--show-error" \
	&& "$4" == "--max-time" \
	&& "$5" == "3" \
	&& "$6" == "https://api-staging.example/actuator/health/readiness" ]] || {
	printf 'unexpected readiness curl invocation:' >&2
	printf ' %q' "$@" >&2
	printf '\n' >&2
	exit 64
}

count=0
[[ ! -f "${FAKE_CURL_STATE}/curl-count" ]] \
	|| count="$(<"${FAKE_CURL_STATE}/curl-count")"
count=$((count + 1))
printf '%s\n' "${count}" > "${FAKE_CURL_STATE}/curl-count"

case "${FAKE_CURL_SCENARIO}" in
	transient-up)
		[[ "${count}" -ne 1 ]] || exit 7
		printf '%s\n' '{"status":"UP"}'
		;;
	strict-then-up)
		case "${count}" in
			1) printf '%s\n' '{"status":"DOWN"}' ;;
			2) printf '%s\n' 'not-json' ;;
			3) printf '%s\n' '{"status":"UP","db":"UP"}' ;;
			4) printf '%s\n' 'null' ;;
			5) printf '%s\n' '[]' ;;
			6) printf '%s\n' '"UP"' ;;
			*) printf '%s\n' '{"status":"UP"}' ;;
		esac
		;;
	stale-success-bytes)
		if [[ "${count}" == "1" ]]; then
			printf '%s\n' '{"status":"UP"}'
			exit 7
		fi
		;;
	exhaustion)
		printf '%s\n' '{"status":"DOWN"}'
		;;
	*)
		printf 'unknown fake curl scenario: %s\n' "${FAKE_CURL_SCENARIO}" >&2
		exit 64
		;;
esac
FAKE_CURL

cat > "${readiness_bin}/sleep" <<'FAKE_SLEEP'
#!/usr/bin/env bash
set -Eeuo pipefail

[[ "$#" == "1" && "$1" == "2" ]] || {
	printf 'unexpected readiness sleep invocation:' >&2
	printf ' %q' "$@" >&2
	printf '\n' >&2
	exit 64
}
count=0
[[ ! -f "${FAKE_CURL_STATE}/sleep-count" ]] \
	|| count="$(<"${FAKE_CURL_STATE}/sleep-count")"
printf '%s\n' "$((count + 1))" > "${FAKE_CURL_STATE}/sleep-count"
FAKE_SLEEP
chmod 700 "${readiness_bin}/curl" "${readiness_bin}/sleep"

reset_readiness_state() {
	rm -f "${readiness_state}/curl-count" "${readiness_state}/sleep-count"
}

run_readiness_scenario() {
	local scenario="$1"
	PATH="${readiness_bin}:${PATH}" \
	FAKE_CURL_STATE="${readiness_state}" \
	FAKE_CURL_SCENARIO="${scenario}" \
		bash -c 'source "$1"; verify_https_readiness api-staging.example' \
			readiness-test "${ROOT}/scripts/deployment/common.sh"
}

reset_readiness_state
run_readiness_scenario transient-up >"${temporary_directory}/transient-up.log" 2>&1
[[ "$(<"${readiness_state}/curl-count")" == "2" ]]
[[ "$(<"${readiness_state}/sleep-count")" == "1" ]]

reset_readiness_state
run_readiness_scenario strict-then-up >"${temporary_directory}/strict-then-up.log" 2>&1
[[ "$(<"${readiness_state}/curl-count")" == "7" ]]
[[ "$(<"${readiness_state}/sleep-count")" == "6" ]]

reset_readiness_state
if run_readiness_scenario exhaustion >"${temporary_directory}/exhaustion.log" 2>&1; then
	printf 'readiness accepted twelve non-UP responses\n' >&2
	exit 1
fi
[[ "$(<"${readiness_state}/curl-count")" == "12" ]]
[[ "$(<"${readiness_state}/sleep-count")" == "11" ]]
grep -q 'HTTPS readiness did not return aggregate UP after 12 attempts' \
	"${temporary_directory}/exhaustion.log"

reset_readiness_state
if run_readiness_scenario stale-success-bytes \
		>"${temporary_directory}/stale-success-bytes.log" 2>&1; then
	printf 'readiness reused success-shaped bytes from a failed earlier attempt\n' >&2
	exit 1
fi
[[ "$(<"${readiness_state}/curl-count")" == "12" ]]
[[ "$(<"${readiness_state}/sleep-count")" == "11" ]]

[[ "$(grep -c 'verify_https_readiness' \
	"${ROOT}/scripts/deployment/reset-stable-demo.sh")" == "1" ]] || {
	printf 'Stable Demo reset no longer reuses the bounded HTTPS verifier\n' >&2
	exit 1
}

deployment_bin="${temporary_directory}/deployment-bin"
deployment_state="${temporary_directory}/deployment-state"
mkdir "${deployment_bin}" "${deployment_state}"
cat > "${deployment_bin}/docker" <<'FAKE_DEPLOY_DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "$1" == "pull" ]]; then
	exit 0
fi
if [[ "$1" == "compose" ]]; then
	for argument in "$@"; do
		case "${argument}" in
			config|up) exit 0 ;;
			ps)
				printf 'backend-id\n'
				exit 0
				;;
		esac
	done
fi
if [[ "$1" == "inspect" && "$2" == "--format" ]]; then
	case "$3" in
		*State.Health*) printf 'healthy\n' ;;
		*Config.Image*) printf '%s\n' "${FAKE_DEPLOY_IMAGE}" ;;
		*) printf 'unexpected docker inspect format: %s\n' "$3" >&2; exit 64 ;;
	esac
	exit 0
fi
if [[ "$1" == "image" && "$2" == "inspect" ]]; then
	printf '["%s"]\n' "${FAKE_DEPLOY_IMAGE}"
	exit 0
fi
printf 'unexpected fake deployment docker invocation:' >&2
printf ' %q' "$@" >&2
printf '\n' >&2
exit 64
FAKE_DEPLOY_DOCKER
cat > "${deployment_bin}/flock" <<'FAKE_FLOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ "$#" == "2" && "$1" == "-n" && "$2" == "9" ]]
FAKE_FLOCK
chmod 700 "${deployment_bin}/docker" "${deployment_bin}/flock"

runtime_env="${temporary_directory}/runtime.env"
(umask 077; printf '%s\n' \
	'CRABIT_ENV=readiness-test' \
	'CRABIT_COMPOSE_PROJECT=crabit-readiness-test' \
	'CRABIT_SPRING_PROFILE=demo' \
	'CRABIT_PUBLIC_HOST=api-staging.example' \
	'CRABIT_DATABASE_NAME=crabit' \
	'CRABIT_DATABASE_USERNAME=crabit' \
	'CRABIT_DATABASE_PASSWORD=verify-secret' \
	'CRABIT_GCP_PROJECT_ID=crabit-verify-project' \
	'CRABIT_GCP_ZONE=asia-northeast3-a' \
	'CRABIT_GCP_INSTANCE=crabit-readiness-test' \
	'CRABIT_GCP_DATA_DISK=crabit-readiness-test-data' > "${runtime_env}")

snapshot_proof="${temporary_directory}/snapshot-proof.env"
(umask 077; printf '%s\n' \
	'CRABIT_GCP_ENV=readiness-test' \
	'CRABIT_GCP_PROJECT_ID=crabit-verify-project' \
	'CRABIT_GCP_ZONE=asia-northeast3-a' \
	'CRABIT_GCP_DATA_DISK=crabit-readiness-test-data' \
	'CRABIT_GCP_SNAPSHOT=crabit-readiness-test-data-deploy-test' \
	'CRABIT_GCP_SNAPSHOT_ID=1234567890' \
	'CRABIT_GCP_SNAPSHOT_STATUS=READY' \
	'CRABIT_GCP_SNAPSHOT_SIZE_GB=100' \
	'CRABIT_GCP_OPERATION_ID=deploy-test' \
	'CRABIT_GCP_SNAPSHOT_CREATED_AT=2026-08-26T00:00:00.000+09:00' > "${snapshot_proof}")

old_current='CRABIT_BACKEND_IMAGE=crabitteam2/crabit-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
old_previous='CRABIT_BACKEND_IMAGE=crabitteam2/crabit-backend@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
printf '%s\n' "${old_current}" > "${deployment_state}/current-image.env"
printf '%s\n' "${old_previous}" > "${deployment_state}/previous-image.env"
chmod 600 "${deployment_state}/current-image.env" "${deployment_state}/previous-image.env"

deployment_digest='sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
deployment_image="crabitteam2/crabit-backend@${deployment_digest}"
reset_readiness_state
if CRABIT_IMAGE_REPOSITORY=crabitteam2/crabit-backend \
	CRABIT_RUNTIME_ENV="${runtime_env}" \
	CRABIT_SNAPSHOT_PROOF="${snapshot_proof}" \
	CRABIT_STATE_DIR="${deployment_state}" \
	FAKE_DEPLOY_IMAGE="${deployment_image}" \
	FAKE_CURL_STATE="${readiness_state}" \
	FAKE_CURL_SCENARIO=exhaustion \
	PATH="${deployment_bin}:${readiness_bin}:${PATH}" \
		"${ROOT}/scripts/deployment/deploy.sh" "${deployment_digest}" demo \
		>"${temporary_directory}/failed-deploy.log" 2>&1; then
	printf 'deployment succeeded after exhausted public HTTPS readiness\n' >&2
	exit 1
fi
[[ -f "${readiness_state}/curl-count" && -f "${readiness_state}/sleep-count" ]] || {
	cat "${temporary_directory}/failed-deploy.log" >&2
	printf 'failed deployment did not reach the public HTTPS readiness boundary\n' >&2
	exit 1
}
[[ "$(<"${readiness_state}/curl-count")" == "12" ]]
[[ "$(<"${readiness_state}/sleep-count")" == "11" ]]
[[ "$(<"${deployment_state}/current-image.env")" == "${old_current}" ]]
[[ "$(<"${deployment_state}/previous-image.env")" == "${old_previous}" ]]
[[ "$(find "${deployment_state}" -maxdepth 1 -name 'next-image.*' -print -quit)" == "" ]]

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
CRABIT_DEMO_BALANCE_PROVIDER_URL=https://demo-console.example/api/provider/balance-lookups \
CRABIT_DEMO_BALANCE_PROVIDER_TOKEN=verify_demo_balance_provider_secret \
	docker compose -f "${ROOT}/deploy/compose.yaml" --profile reset config --format json >"${config_file}"

jq -e '.services.backend.ports == null and .services.postgres.ports == null' "${config_file}" >/dev/null
jq -e '(.services.caddy.ports | map(.published) | sort) == ["443", "80"]' "${config_file}" >/dev/null
jq -e '.networks.database.internal == true' "${config_file}" >/dev/null
jq -e '.services.backend.image | test("^crabitteam2/crabit-backend@sha256:[0-9a-f]{64}$")' "${config_file}" >/dev/null
jq -e '.services.postgres.image | contains("@sha256:")' "${config_file}" >/dev/null
jq -e '.services.caddy.image | contains("@sha256:")' "${config_file}" >/dev/null
jq -e '.services.backend.environment.CRABIT_DEMO_BALANCE_PROVIDER_URL == "https://demo-console.example/api/provider/balance-lookups"' "${config_file}" >/dev/null
jq -e '.services.backend.environment.CRABIT_DEMO_BALANCE_PROVIDER_TOKEN == "verify_demo_balance_provider_secret"' "${config_file}" >/dev/null
jq -e '.services["demo-reset"].environment.CRABIT_DEMO_BALANCE_PROVIDER_URL == "https://demo-console.example/api/provider/balance-lookups"' "${config_file}" >/dev/null
jq -e '.services["demo-reset"].environment.CRABIT_DEMO_BALANCE_PROVIDER_TOKEN == "verify_demo_balance_provider_secret"' "${config_file}" >/dev/null

"${ROOT}/scripts/deployment/google-cloud/verify-plan.sh"

printf 'workflow and Compose invariants verified\n'
