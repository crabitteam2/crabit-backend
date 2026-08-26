#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
[[ "$#" == "0" ]] || gcp_die "usage: verify-authorization.sh"
for command in gcloud jq; do gcp_require_command "${command}"; done
validate_plan
validate_google_identity

readonly pool="$(plan_value '.identity.workload_identity_pool')"
readonly provider="$(plan_value '.identity.workload_identity_provider')"
readonly provider_condition="$(wif_provider_condition)"
readonly zone="$(plan_value '.location.zone')"
readonly repository_principal="$(wif_repository_principal)"
readonly shared_viewer_role="$(deployment_shared_viewer_role_name)"

provider_json="$(gcloud_json iam workload-identity-pools providers describe "${provider}" \
	--workload-identity-pool "${pool}" --location global)"
jq -e \
	--arg issuer "$(plan_value '.identity.oidc_issuer')" \
	--arg condition "${provider_condition}" '
	.state == "ACTIVE"
	and .oidc.issuerUri == $issuer
	and .attributeMapping == {
		"google.subject":"assertion.sub",
		"attribute.repository":"assertion.repository",
		"attribute.repository_id":"assertion.repository_id",
		"attribute.environment":"assertion.environment"
	}
	and .attributeCondition == $condition
' <<< "${provider_json}" >/dev/null \
	|| gcp_die "Workload Identity provider is not bound to immutable repository ID and approved environments"

project_policy="$(gcloud_json projects get-iam-policy "${GCP_PROJECT_ID}")"
shared_viewer_role_json="$(gcloud_json iam roles describe "$(deployment_shared_viewer_role_id)")"
jq -e --arg name "${shared_viewer_role}" '
	.name == $name
	and .stage == "GA"
	and (.includedPermissions | sort) == ["compute.firewalls.get","compute.firewalls.list","compute.projects.get"]
' <<< "${shared_viewer_role_json}" >/dev/null \
	|| gcp_die "deployment shared viewer custom role is missing or grants peer-resource permissions"
all_deployers="$(jq -c --arg project "${GCP_PROJECT_ID}" \
	'[.environments[].deployer_service_account + "@" + $project + ".iam.gserviceaccount.com" | "serviceAccount:" + .]' \
	"${GCP_PLAN}")"

while IFS= read -r environment; do
	config="$(environment_json "${environment}")"
	instance="$(jq -r '.instance' <<< "${config}")"
	disk="$(jq -r '.data_disk' <<< "${config}")"
	deployer="$(deployer_email "${environment}")"
	runtime="$(runtime_email "${environment}")"
	member="serviceAccount:${deployer}"
	wif_principal="$(wif_environment_principal "${environment}")"

	deployer_policy="$(gcloud_json iam service-accounts get-iam-policy "${deployer}")"
	jq -e --arg principal "${wif_principal}" --arg legacy "${repository_principal}" '
		([.bindings[]? | select(.role == "roles/iam.workloadIdentityUser")] | length) == 1
		and ([.bindings[]? | select(.role == "roles/iam.workloadIdentityUser") | .members[]?] | sort) == [$principal]
		and all(.bindings[]?; ((.members // []) | index($legacy)) == null)
	' <<< "${deployer_policy}" >/dev/null \
		|| gcp_die "${environment} deployer impersonation is not environment-isolated"

	runtime_policy="$(gcloud_json iam service-accounts get-iam-policy "${runtime}")"
	jq -e --arg member "${member}" --argjson deployers "${all_deployers}" '
		([.bindings[]? | select(.role == "roles/iam.serviceAccountUser")] | length) == 1
		and ([.bindings[]? | select(.role == "roles/iam.serviceAccountUser") | .members[]?] | sort) == [$member]
		and all(.bindings[]?; all(.members[]?;
			. as $candidate | ($deployers | index($candidate)) == null or $candidate == $member))
	' <<< "${runtime_policy}" >/dev/null \
		|| gcp_die "${environment} runtime service account permits cross-environment attachment"

	instance_json="$(gcloud_json compute instances describe "${instance}" --zone "${zone}")"
	internal_ip="$(jq -er '.networkInterfaces[0].networkIP | select(test("^[0-9]+(\\.[0-9]+){3}$"))' <<< "${instance_json}")" \
		|| gcp_die "${environment} internal IP is unavailable for IAP verification"

	disk_policy="$(gcloud_json compute disks get-iam-policy "${disk}" --zone "${zone}")"
	jq -e --arg member "${member}" --argjson deployers "${all_deployers}" '
		([.bindings[]? | select(.role == "roles/compute.storageAdmin")] | length) == 1
		and ([.bindings[]? | select(.role == "roles/compute.storageAdmin") | .members[]?] | sort) == [$member]
		and all(.bindings[]?; all(.members[]?;
			. as $candidate | ($deployers | index($candidate)) == null or $candidate == $member))
	' <<< "${disk_policy}" >/dev/null \
		|| gcp_die "${environment} deployer is not isolated to its own data disk"

	snapshot_title="$(snapshot_condition_title "${environment}")"
	snapshot_expression="$(snapshot_condition_expression "${environment}")"
	instance_title="$(instance_condition_title "${environment}")"
	instance_expression="$(instance_condition_expression "${environment}")"
	iap_title="$(iap_condition_title "${environment}")"
	iap_expression="$(iap_condition_expression "${internal_ip}")"
	jq -e \
		--arg member "${member}" \
		--arg snapshot_title "${snapshot_title}" \
		--arg snapshot_expression "${snapshot_expression}" \
		--arg instance_title "${instance_title}" \
		--arg instance_expression "${instance_expression}" \
		--arg iap_title "${iap_title}" \
		--arg iap_expression "${iap_expression}" \
		--arg shared_viewer_role "${shared_viewer_role}" \
		--argjson deployers "${all_deployers}" '
		[.bindings[]? | select((.members // []) | index($member) != null)] as $bindings
		| ($bindings | length) == 5
		and any($bindings[];
			.role == $shared_viewer_role
			and (.condition // null) == null
			and ((.members | sort) == ($deployers | sort)))
		and all(["roles/compute.instanceAdmin.v1","roles/compute.osAdminLogin"][]; . as $role |
			any($bindings[];
				.role == $role
				and .members == [$member]
				and .condition.title == $instance_title
				and .condition.expression == $instance_expression))
		and any($bindings[];
			.role == "roles/iap.tunnelResourceAccessor"
			and .members == [$member]
			and .condition.title == $iap_title
			and .condition.expression == $iap_expression)
		and any($bindings[];
			.role == "roles/compute.storageAdmin"
			and .members == [$member]
			and .condition.title == $snapshot_title
			and .condition.expression == $snapshot_expression)
	' <<< "${project_policy}" >/dev/null \
		|| gcp_die "${environment} deployer has missing or overbroad VM, snapshot, network, or IAP authorization"
done < <(jq -r '.environments[].name' "${GCP_PLAN}")

printf 'Google Cloud authorization verified: repository-id=%s environments=staging,stable-demo shared-read-only=true cross-access=denied\n' \
	"$(plan_value '.github_repository_id')"
