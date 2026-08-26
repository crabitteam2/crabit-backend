#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
gcp_require_command jq
validate_plan

temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT

expect_rejected() {
	local name="$1"
	local filter="$2"
	local candidate="${temporary_directory}/${name}.json"
	jq "${filter}" "${GCP_PLAN}" > "${candidate}"
	if (validate_plan "${candidate}") >/dev/null 2>&1; then
		gcp_die "plan verification accepted forbidden scenario: ${name}"
	fi
}

expect_rejected wrong-region '.location.region = "us-central1"'
expect_rejected wrong-zone '.location.zone = "asia-northeast3-b"'
expect_rejected wrong-disk-type '.environments[0].data_disk_type = "pd-standard"'
expect_rejected wrong-disk-size '.environments[1].data_disk_gb = 99'
expect_rejected public-ssh '.network.public_tcp_ports += [22]'
expect_rejected public-database '.network.public_tcp_ports += [5432]'
expect_rejected missing-wif '.identity.oidc_issuer = null'
expect_rejected missing-os-login '.network.os_login = false'
expect_rejected long-lived-key '.identity.service_account_keys_allowed = true'
expect_rejected unverified-snapshot '.snapshots.required_status = "CREATING"'
expect_rejected duplicate-writer '.snapshots.single_writer = false'
expect_rejected mutable-image '.image.mutable_tags_allowed = true'
expect_rejected missing-budget-threshold '.budget.alert_thresholds = [100,150]'
expect_rejected cross-environment-instance '.environments[1].instance = .environments[0].instance'
expect_rejected vultr-fallback '.migration.vultr_access_allowed = true'

if rg -n -g '!verify-plan.sh' 'service-accounts[[:space:]]+keys[[:space:]]+create|GOOGLE_APPLICATION_CREDENTIALS|credentials_json|ssh-keyscan|StrictHostKeyChecking[= ]no' \
		"${GCP_REPOSITORY_ROOT}/.github/workflows/deploy-staging.yml" \
		"${GCP_REPOSITORY_ROOT}/.github/workflows/publish-and-deploy-stable-demo.yml" \
		"${GCP_REPOSITORY_ROOT}/.github/workflows/reset-stable-demo.yml" \
		"${GCP_REPOSITORY_ROOT}/scripts/deployment/google-cloud" \
		"${GCP_REPOSITORY_ROOT}/deploy/google-cloud"; then
	gcp_die "forbidden long-lived key, weak SSH, or removed-provider path found"
fi

printf 'Google Cloud plan verified: environments=2 zone=asia-northeast3-a disks=30/100GB budget=USD200 thresholds=100,150,180\n'
