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
grep -q -- 'docker build --provenance=false' "${publish}"
grep -q 'workflow_dispatch' "${staging}"
! grep -Eq '^[[:space:]]+(push|workflow_run):' "${staging}"
grep -q 'origin/develop' "${staging}"
grep -q 'workflow_dispatch' "${reset}"
! grep -Eq '^[[:space:]]+(push|workflow_run|schedule):' "${reset}"
for workflow in "${publish}" "${reset}"; do
	grep -q 'CRABIT_DEMO_BALANCE_PROVIDER_URL' "${workflow}"
	grep -q 'CRABIT_DEMO_BALANCE_PROVIDER_TOKEN' "${workflow}"
done

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
		'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
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

run_publish_step tag-moves >"${temporary_directory}/moved.log" 2>&1
grep -qx 'image_digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
	"${temporary_directory}/tag-moves.output"
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

printf 'workflow and Compose invariants verified\n'
