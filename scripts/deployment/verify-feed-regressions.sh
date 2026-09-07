#!/usr/bin/env bash
set -Eeuo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
mkdir "${temporary_directory}/bin" "${temporary_directory}/state"
export TEST_STATE="${temporary_directory}/state"
export TEST_VERSION="wish-category-v1@sha256:$(printf 'a%.0s' {1..64})"
export TEST_BACKEND="sha256:$(printf 'b%.0s' {1..64})"
export TEST_DATA="sha256:$(printf 'c%.0s' {1..64})"
export CRABIT_RUNTIME_ENV="${temporary_directory}/runtime.env"
export CRABIT_SNAPSHOT_PROOF="${temporary_directory}/snapshot.env"
export CRABIT_STATE_DIR="${TEST_STATE}"
umask 077
cat > "${CRABIT_RUNTIME_ENV}" <<EOF
CRABIT_ENV=staging
CRABIT_COMPOSE_PROJECT=crabit-staging
CRABIT_SPRING_PROFILE=e2e
CRABIT_PUBLIC_HOST=example.invalid
CRABIT_DATABASE_NAME=crabit
CRABIT_DATABASE_USERNAME=crabit
CRABIT_DATABASE_PASSWORD=test-database
CRABIT_RECAP_GENERATION_CREDENTIAL=test-recap
CRABIT_GCP_PROJECT_ID=test-project
CRABIT_GCP_ZONE=asia-northeast3-a
CRABIT_GCP_INSTANCE=crabit-staging
CRABIT_GCP_DATA_DISK=crabit-staging-data
CRABIT_FEED_RANKING_ENABLED=true
CRABIT_FEED_RANKING_CREDENTIAL=test-feed-secret
CRABIT_FEED_CLASSIFIER_VERSION=${TEST_VERSION}
EOF
cat > "${CRABIT_SNAPSHOT_PROOF}" <<EOF
CRABIT_GCP_ENV=staging
CRABIT_GCP_PROJECT_ID=test-project
CRABIT_GCP_ZONE=asia-northeast3-a
CRABIT_GCP_INSTANCE=crabit-staging
CRABIT_GCP_DATA_DISK=crabit-staging-data
CRABIT_GCP_SNAPSHOT=crabit-staging-data-deploy-test
CRABIT_GCP_SNAPSHOT_ID=123
CRABIT_GCP_SNAPSHOT_STATUS=READY
CRABIT_GCP_SNAPSHOT_SIZE_GB=100
CRABIT_GCP_OPERATION_ID=deploy-test
CRABIT_GCP_SNAPSHOT_CREATED_AT=2026-09-07T00:00:00Z
EOF
cat > "${temporary_directory}/bin/docker" <<'SH'
#!/usr/bin/env bash
set -Eeuo pipefail
case "$1" in
pull) exit 0 ;;
create) echo verifier-backend; exit 0 ;;
cp) touch "$3"; exit 0 ;;
rm) exit 0 ;;
run)
    if [[ " $* " == *' --entrypoint python '* ]]; then
        cat >/dev/null
        [[ "${TEST_FAILURE:-}" != preflight ]] || exit 1
        echo "${TEST_VERSION}"
    else echo verifier-feed; fi
    exit 0 ;;
exec) cat >/dev/null; exit 0 ;;
image) printf '["%s"]\n' "$3"; exit 0 ;;
compose)
    args=("$@"); release=""; operation=""
    for ((i=1;i<${#args[@]};i++)); do
        if [[ "${args[i]}" == --env-file ]]; then i=$((i+1)); release="${args[i]}"; fi
        case "${args[i]}" in up|rm|stop|ps|config) operation="${args[i]}";; esac
    done
    service="${args[${#args[@]}-1]}"
    if [[ "${operation}" != ps && "${operation}" != config ]]; then
        printf '%s:%s:%s\n' "${operation}" "${service}" "${CRABIT_FEED_RANKING_ENABLED}" >> "${TEST_STATE}/events"
    fi
    if [[ "${CRABIT_FEED_RANKING_ENABLED}" == true ]]; then
        [[ "${CRABIT_FEED_RANKING_CREDENTIAL}" == test-feed-secret ]] || exit 71
    else [[ -z "${CRABIT_FEED_RANKING_CREDENTIAL}" ]] || exit 72; fi
    case "${operation}" in
    up) cp "${release}" "${TEST_STATE}/active" ;;
    ps) echo "${service}-id" ;;
    esac
    exit 0 ;;
inspect)
    case "$3" in
    *State.Health*)
        if [[ "$4" == feed-id && "${TEST_FAILURE:-}" == feed-health ]]; then echo unhealthy;
        elif [[ "$4" == feed-id && "${TEST_FAILURE:-}" == final-health && -f "${TEST_STATE}/https" ]]; then echo unhealthy;
        else echo healthy; fi ;;
    *Config.Image*)
        if [[ "$4" == feed-id && "${TEST_FAILURE:-}" == image ]]; then echo wrong-image;
        elif [[ "$4" == backend-id ]]; then sed -n 's/^CRABIT_BACKEND_IMAGE=//p' "${TEST_STATE}/active";
        else sed -n 's/^CRABIT_RECAP_IMAGE=//p' "${TEST_STATE}/active"; fi ;;
    esac
    exit 0 ;;
esac
exit 64
SH
cat > "${temporary_directory}/bin/curl" <<'SH'
#!/usr/bin/env bash
touch "${TEST_STATE}/https"
echo '{"status":"UP"}'
SH
printf '#!/usr/bin/env bash\nexit 0\n' > "${temporary_directory}/bin/flock"
chmod 700 "${temporary_directory}"/bin/*
export PATH="${temporary_directory}/bin:${PATH}"
export CRABIT_FEED_RANKING_CREDENTIAL=ambient-contamination
run_deploy() { bash "${root}/scripts/deployment/deploy.sh" "${TEST_BACKEND}" "${TEST_DATA}" e2e > "${temporary_directory}/result" 2>&1; }
expect_failure() { if "$@"; then echo 'Expected failure' >&2; exit 1; fi; }
cp "${CRABIT_RUNTIME_ENV}" "${temporary_directory}/valid.env"
for invalid in missing reused boolean version url; do
    cp "${temporary_directory}/valid.env" "${CRABIT_RUNTIME_ENV}"
    case "${invalid}" in
    missing) sed '/^CRABIT_FEED_RANKING_CREDENTIAL=/d' "${CRABIT_RUNTIME_ENV}" ;;
    reused) sed 's/test-feed-secret/test-recap/' "${CRABIT_RUNTIME_ENV}" ;;
    boolean) sed 's/CRABIT_FEED_RANKING_ENABLED=true/CRABIT_FEED_RANKING_ENABLED=TRUE/' "${CRABIT_RUNTIME_ENV}" ;;
    version) sed 's/wish-category-v1@sha256:/invalid:/' "${CRABIT_RUNTIME_ENV}" ;;
    url) cat "${CRABIT_RUNTIME_ENV}"; echo 'CRABIT_FEED_RANKING_URL=http://other:8081/wrong' ;;
    esac > "${temporary_directory}/invalid.env"
    cp "${temporary_directory}/invalid.env" "${CRABIT_RUNTIME_ENV}"
    expect_failure run_deploy
    [[ ! -f "${TEST_STATE}/events" ]]
    ! grep -q 'test-feed-secret' "${temporary_directory}/result"
done
cp "${temporary_directory}/valid.env" "${CRABIT_RUNTIME_ENV}"
TEST_FAILURE=preflight expect_failure run_deploy
[[ ! -f "${TEST_STATE}/events" ]]
# First failed activation stops all serving services, including feed.
TEST_FAILURE=feed-health expect_failure run_deploy
grep -q '^stop:feed:true$' "${TEST_STATE}/events"
[[ ! -f "${TEST_STATE}/current-release.env" ]]
# Legacy rollback restores a disabled release even when runtime.env enables feed.
printf 'CRABIT_BACKEND_IMAGE=crabitteam2/crabit-backend@%s\nCRABIT_RECAP_IMAGE=crabitteam2/crabit-data@%s\n' "${TEST_BACKEND}" "${TEST_DATA}" > "${TEST_STATE}/current-release.env"
for failure in feed-health final-health image; do
    TEST_FAILURE="${failure}" expect_failure run_deploy
    grep -q '^rm:feed:false$' "${TEST_STATE}/events"
    [[ "$(wc -l < "${TEST_STATE}/current-release.env" | tr -d ' ')" == 2 ]]
done
run_deploy
grep -q '^CRABIT_FEED_RANKING_ENABLED=true$' "${TEST_STATE}/current-release.env"
! grep -q 'CREDENTIAL' "${TEST_STATE}/current-release.env"
# Explicit rollback is bound to previous image pair and its disabled feed setting.
sed '/^CRABIT_FEED_/d' "${CRABIT_RUNTIME_ENV}" > "${temporary_directory}/disabled.env"
cp "${temporary_directory}/disabled.env" "${CRABIT_RUNTIME_ENV}"
bash "${root}/scripts/deployment/rollback.sh" "${TEST_BACKEND}" "${TEST_DATA}" e2e I_VERIFIED_MIGRATION_COMPATIBILITY > "${temporary_directory}/result" 2>&1
grep -q '^CRABIT_FEED_RANKING_ENABLED=false$' "${TEST_STATE}/current-release.env"
# Release metadata must not accept unknown fields or credentials.
printf 'CRABIT_FEED_RANKING_CREDENTIAL=hidden\n' >> "${TEST_STATE}/current-release.env"
expect_failure bash -c 'source "$1/scripts/deployment/common.sh"; validate_release_env "$TEST_STATE/current-release.env"' _ "${root}" > "${temporary_directory}/invalid" 2>&1
! grep -q 'test-feed-secret\|ambient-contamination' "${temporary_directory}/result"
echo 'feed lifecycle regressions passed: first failure, health/final/image recovery, ambient binding, legacy rollback, release redaction'
