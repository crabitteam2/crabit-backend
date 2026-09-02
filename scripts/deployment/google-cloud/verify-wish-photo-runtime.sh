#!/usr/bin/env bash
set -Eeuo pipefail
readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
readonly temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
export GCP_PROJECT_ID=project-9ee29576-dd79-4a1c-a70 GCP_PROJECT_NUMBER=182907578804
export CRABIT_PUBLIC_HOST=api.example.test CRABIT_DATABASE_NAME=crabit CRABIT_DATABASE_USERNAME=crabit
export CRABIT_DATABASE_PASSWORD=synthetic_password_123456 CRABIT_COMPOSE_PROJECT=crabit-test
export CRABIT_DEMO_TOKEN_OWNER=synthetic_owner_123456 CRABIT_DEMO_TOKEN_FRIEND=synthetic_friend_123456
export CRABIT_DEMO_TOKEN_NONFRIEND=synthetic_nonfriend_123456 CRABIT_DEMO_TOKEN_BLOCKED=synthetic_blocked_123456
export CRABIT_DEMO_TOKEN_OTHER_ACADEMY=synthetic_other_123456 CRABIT_DEMO_TOKEN_STAFF=synthetic_staff_123456
export CRABIT_DEMO_BALANCE_PROVIDER_URL=https://console.example.test/api/provider/balance-lookups
export CRABIT_DEMO_BALANCE_PROVIDER_TOKEN=synthetic_provider_token_1234567890123456789
readonly rendered="${temporary_directory}/runtime.env"
validate() { bash -c 'source "$1/scripts/deployment/common.sh"; validate_runtime_env "$2"' _ "${root}" "$1"; }
reject() { if "$@" >"${temporary_directory}/rejected.log" 2>&1; then printf 'unexpected acceptance\n' >&2; exit 1; fi; }
for environment in staging stable-demo; do
    for enabled in absent false true; do
        unset CRABIT_WISH_PHOTO_ENABLED
        if [[ "${enabled}" != absent ]]; then export CRABIT_WISH_PHOTO_ENABLED="${enabled}"; fi
        bash "${root}/scripts/deployment/google-cloud/render-runtime-env.sh" "${environment}" "${rendered}" >/dev/null
        validate "${rendered}"
        grep -qx "CRABIT_WISH_PHOTO_BUCKET=crabit-wish-photo-${environment}-${GCP_PROJECT_NUMBER}" "${rendered}"
        grep -qx "CRABIT_WISH_PHOTO_SERVICE_ACCOUNT=crabit-${environment}-runtime@${GCP_PROJECT_ID}.iam.gserviceaccount.com" "${rendered}"
    done
done
for invalid in '' TRUE 1 yes 'false;exit'; do
    reject env CRABIT_WISH_PHOTO_ENABLED="${invalid}" bash "${root}/scripts/deployment/google-cloud/render-runtime-env.sh" staging "${rendered}"
done
reject env CRABIT_WISH_PHOTO_BUCKET=wrong bash "${root}/scripts/deployment/google-cloud/render-runtime-env.sh" staging "${rendered}"
reject env CRABIT_WISH_PHOTO_SERVICE_ACCOUNT=wrong bash "${root}/scripts/deployment/google-cloud/render-runtime-env.sh" staging "${rendered}"
reject env GCP_PROJECT_NUMBER=123456789012 bash "${root}/scripts/deployment/google-cloud/render-runtime-env.sh" staging "${rendered}"
bash "${root}/scripts/deployment/google-cloud/render-runtime-env.sh" staging "${rendered}" >/dev/null
cp "${rendered}" "${temporary_directory}/duplicate.env"
printf 'CRABIT_WISH_PHOTO_ENABLED=true\n' >> "${temporary_directory}/duplicate.env"
reject validate "${temporary_directory}/duplicate.env"
sed 's/CRABIT_WISH_PHOTO_ENVIRONMENT=staging/CRABIT_WISH_PHOTO_ENVIRONMENT=stable-demo/' "${rendered}" > "${temporary_directory}/cross.env"
chmod 600 "${temporary_directory}/cross.env"
reject validate "${temporary_directory}/cross.env"
cp "${rendered}" "${temporary_directory}/unsafe.env"
printf 'INJECTED=$(exit)\n' >> "${temporary_directory}/unsafe.env"
reject validate "${temporary_directory}/unsafe.env"
chmod 644 "${rendered}"
reject validate "${rendered}"
for workflow in deploy-staging publish-and-deploy-stable-demo reset-stable-demo; do
    grep -q 'CRABIT_WISH_PHOTO_ENABLED:.*vars.CRABIT_WISH_PHOTO_ENABLED' "${root}/.github/workflows/${workflow}.yml"
done
grep -q 'CRABIT_WISH_PHOTO_ENABLED: "false"' "${root}/deploy/compose.yaml"
printf 'Wish photo runtime offline regressions passed\n'
