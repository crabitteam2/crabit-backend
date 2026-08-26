#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
for command in gcloud jq; do gcp_require_command "${command}"; done
validate_plan
validate_google_identity
gcp_require_env GCP_BILLING_ACCOUNT
gcp_require_env GCP_BUDGET_NOTIFICATION_CHANNEL
[[ "${GCP_BILLING_ACCOUNT}" =~ ^[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}$ ]] || gcp_die "GCP_BILLING_ACCOUNT is invalid"
[[ "${GCP_BUDGET_NOTIFICATION_CHANNEL}" =~ ^projects/${GCP_PROJECT_ID}/notificationChannels/[0-9]+$ ]] \
	|| gcp_die "GCP_BUDGET_NOTIFICATION_CHANNEL is invalid"

readonly region="$(plan_value '.location.region')"
readonly zone="$(plan_value '.location.zone')"
readonly pool="$(plan_value '.identity.workload_identity_pool')"
readonly provider="$(plan_value '.identity.workload_identity_provider')"
readonly vpc="$(plan_value '.network.vpc')"
readonly subnet="$(plan_value '.network.subnet')"
readonly provider_condition="$(wif_provider_condition)"
readonly repository_principal="$(wif_repository_principal)"
readonly shared_viewer_role_id="$(deployment_shared_viewer_role_id)"
readonly shared_viewer_role="$(deployment_shared_viewer_role_name)"
readonly shared_viewer_permissions="compute.firewalls.get,compute.firewalls.list,compute.projects.get"

gcloud --quiet services enable \
	compute.googleapis.com iam.googleapis.com iamcredentials.googleapis.com \
	iap.googleapis.com sts.googleapis.com cloudbilling.googleapis.com billingbudgets.googleapis.com \
	--project "${GCP_PROJECT_ID}"

if ! gcloud iam workload-identity-pools describe "${pool}" --location=global --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
	gcloud --quiet iam workload-identity-pools create "${pool}" --location=global \
		--display-name="GitHub Actions" --project "${GCP_PROJECT_ID}"
fi
if ! gcloud iam workload-identity-pools providers describe "${provider}" --workload-identity-pool="${pool}" --location=global --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
	gcloud --quiet iam workload-identity-pools providers create-oidc "${provider}" \
		--workload-identity-pool="${pool}" --location=global \
		--issuer-uri="$(plan_value '.identity.oidc_issuer')" \
		--attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_id=assertion.repository_id,attribute.environment=assertion.environment" \
		--attribute-condition="${provider_condition}" \
		--project "${GCP_PROJECT_ID}"
else
	gcloud --quiet iam workload-identity-pools providers update-oidc "${provider}" \
		--workload-identity-pool="${pool}" --location=global \
		--issuer-uri="$(plan_value '.identity.oidc_issuer')" \
		--attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_id=assertion.repository_id,attribute.environment=assertion.environment" \
		--attribute-condition="${provider_condition}" \
		--project "${GCP_PROJECT_ID}"
fi

if ! gcloud compute networks describe "${vpc}" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
	gcloud --quiet compute networks create "${vpc}" --project "${GCP_PROJECT_ID}" --subnet-mode=custom
fi
if ! gcloud compute networks subnets describe "${subnet}" --region "${region}" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
	gcloud --quiet compute networks subnets create "${subnet}" --project "${GCP_PROJECT_ID}" \
		--network="${vpc}" --region="${region}" --range="$(plan_value '.network.subnet_cidr')"
fi

if ! gcloud iam roles describe "${shared_viewer_role_id}" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
	gcloud --quiet iam roles create "${shared_viewer_role_id}" --project "${GCP_PROJECT_ID}" \
		--title="Crabit deployment shared viewer" \
		--description="Read shared deployment firewalls and project metadata; no VM visibility" \
		--permissions="${shared_viewer_permissions}" --stage=GA
else
	gcloud --quiet iam roles update "${shared_viewer_role_id}" --project "${GCP_PROJECT_ID}" \
		--title="Crabit deployment shared viewer" \
		--description="Read shared deployment firewalls and project metadata; no VM visibility" \
		--permissions="${shared_viewer_permissions}" --stage=GA
fi

environment_tags="$(jq -r '[.environments[].network_tag] | join(",")' "${GCP_PLAN}")"
if ! gcloud compute firewall-rules describe crabit-public-https --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
	gcloud --quiet compute firewall-rules create crabit-public-https \
		--project "${GCP_PROJECT_ID}" --network="${vpc}" --direction=INGRESS --priority=1000 \
		--source-ranges=0.0.0.0/0 --target-tags="${environment_tags}" --allow=tcp:80,tcp:443
fi
if ! gcloud compute firewall-rules describe crabit-iap-ssh --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
	gcloud --quiet compute firewall-rules create crabit-iap-ssh \
		--project "${GCP_PROJECT_ID}" --network="${vpc}" --direction=INGRESS --priority=1000 \
		--source-ranges="$(plan_value '.network.iap_source_range')" \
		--target-tags="${environment_tags}" --allow=tcp:22
fi

while IFS= read -r environment; do
	config="$(environment_json "${environment}")"
	deployer_account="$(jq -r '.deployer_service_account' <<< "${config}")"
	runtime_account="$(jq -r '.runtime_service_account' <<< "${config}")"
	deployer="$(deployer_email "${environment}")"
	runtime="$(runtime_email "${environment}")"
	wif_principal="$(wif_environment_principal "${environment}")"
	for account in "${deployer_account}" "${runtime_account}"; do
		if ! gcloud iam service-accounts describe "${account}@${GCP_PROJECT_ID}.iam.gserviceaccount.com" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
			gcloud --quiet iam service-accounts create "${account}" --project "${GCP_PROJECT_ID}" \
				--display-name="Crabit ${environment} ${account##*-}"
		fi
	done
	deployer_policy="$(gcloud_json iam service-accounts get-iam-policy "${deployer}")"
	if jq -e --arg principal "${repository_principal}" '
		any(.bindings[]?; .role == "roles/iam.workloadIdentityUser" and ((.members // []) | index($principal) != null))
	' <<< "${deployer_policy}" >/dev/null; then
		gcloud --quiet iam service-accounts remove-iam-policy-binding "${deployer}" \
			--project "${GCP_PROJECT_ID}" --member="${repository_principal}" \
			--role=roles/iam.workloadIdentityUser >/dev/null
	fi
	gcloud --quiet iam service-accounts add-iam-policy-binding "${deployer}" \
		--project "${GCP_PROJECT_ID}" --member="${wif_principal}" --role=roles/iam.workloadIdentityUser >/dev/null
	gcloud --quiet iam service-accounts add-iam-policy-binding "${runtime}" \
		--project "${GCP_PROJECT_ID}" --member="serviceAccount:${deployer}" --role=roles/iam.serviceAccountUser >/dev/null

	address="$(jq -r '.address' <<< "${config}")"
	disk="$(jq -r '.data_disk' <<< "${config}")"
	policy="$(jq -r '.snapshot_policy' <<< "${config}")"
	instance="$(jq -r '.instance' <<< "${config}")"
	tag="$(jq -r '.network_tag' <<< "${config}")"
	if ! gcloud compute addresses describe "${address}" --region "${region}" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
		gcloud --quiet compute addresses create "${address}" --region "${region}" --project "${GCP_PROJECT_ID}"
	fi
	if ! gcloud compute disks describe "${disk}" --zone "${zone}" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
		gcloud --quiet compute disks create "${disk}" --zone "${zone}" --project "${GCP_PROJECT_ID}" \
			--size="$(jq -r '.data_disk_gb' <<< "${config}")GB" --type="$(jq -r '.data_disk_type' <<< "${config}")"
	fi
	if ! gcloud compute resource-policies describe "${policy}" --region "${region}" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
		gcloud --quiet compute resource-policies create snapshot-schedule "${policy}" \
			--region "${region}" --project "${GCP_PROJECT_ID}" --daily-schedule --start-time=03:00 \
			--max-retention-days="$(plan_value '.snapshots.retention_days')" --on-source-disk-delete=keep-auto-snapshots \
			--storage-location="${region}"
	fi
	disk_json="$(gcloud_json compute disks describe "${disk}" --zone "${zone}")"
	if ! jq -e --arg policy "${policy}" 'any(.resourcePolicies[]?; endswith("/resourcePolicies/" + $policy))' <<< "${disk_json}" >/dev/null; then
		gcloud --quiet compute disks add-resource-policies "${disk}" --zone "${zone}" \
			--resource-policies="${policy}" --project "${GCP_PROJECT_ID}"
	fi
	if ! gcloud compute instances describe "${instance}" --zone "${zone}" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
		gcloud --quiet compute instances create "${instance}" --zone "${zone}" --project "${GCP_PROJECT_ID}" \
			--machine-type="$(jq -r '.machine_type' <<< "${config}")" \
			--image-family=ubuntu-2404-lts-amd64 --image-project=ubuntu-os-cloud \
			--boot-disk-size="$(jq -r '.boot_disk_gb' <<< "${config}")GB" --boot-disk-type=pd-balanced \
			--disk="name=${disk},device-name=crabit-data,mode=rw,boot=no,auto-delete=no" \
			--subnet="${subnet}" --address="${address}" --tags="${tag}" \
			--metadata=enable-oslogin=TRUE,block-project-ssh-keys=TRUE \
			--service-account="${runtime}" --scopes=https://www.googleapis.com/auth/cloud-platform
	fi
	instance_json="$(gcloud_json compute instances describe "${instance}" --zone "${zone}")"
	internal_ip="$(jq -er '.networkInterfaces[0].networkIP | select(test("^[0-9]+(\\.[0-9]+){3}$"))' <<< "${instance_json}")" \
		|| gcp_die "${environment} instance internal IP is unavailable for IAP binding"
	gcloud --quiet compute disks add-iam-policy-binding "${disk}" \
		--zone "${zone}" --project "${GCP_PROJECT_ID}" \
		--member="serviceAccount:${deployer}" --role=roles/compute.storageAdmin >/dev/null
	gcloud --quiet projects add-iam-policy-binding "${GCP_PROJECT_ID}" \
		--member="serviceAccount:${deployer}" --role="${shared_viewer_role}" >/dev/null
	instance_title="$(instance_condition_title "${environment}")"
	instance_expression="$(instance_condition_expression "${environment}")"
	for role in roles/compute.instanceAdmin.v1 roles/compute.osAdminLogin; do
		gcloud --quiet projects add-iam-policy-binding "${GCP_PROJECT_ID}" \
			--member="serviceAccount:${deployer}" --role="${role}" \
			--condition="title=${instance_title},expression=${instance_expression},description=Only ${environment} VM" >/dev/null
	done
	iap_title="$(iap_condition_title "${environment}")"
	iap_expression="$(iap_condition_expression "${internal_ip}")"
	gcloud --quiet projects add-iam-policy-binding "${GCP_PROJECT_ID}" \
		--member="serviceAccount:${deployer}" --role=roles/iap.tunnelResourceAccessor \
		--condition="title=${iap_title},expression=${iap_expression},description=Only SSH through the ${environment} VM" >/dev/null
	snapshot_title="$(snapshot_condition_title "${environment}")"
	snapshot_expression="$(snapshot_condition_expression "${environment}")"
	gcloud --quiet projects add-iam-policy-binding "${GCP_PROJECT_ID}" \
		--member="serviceAccount:${deployer}" --role=roles/compute.storageAdmin \
		--condition="title=${snapshot_title},expression=${snapshot_expression},description=Only ${environment} snapshot names" >/dev/null

	project_policy="$(gcloud_json projects get-iam-policy "${GCP_PROJECT_ID}")"
	for role in roles/compute.networkViewer roles/compute.instanceAdmin.v1 roles/compute.storageAdmin \
			roles/compute.osAdminLogin roles/iap.tunnelResourceAccessor; do
		if jq -e --arg member "serviceAccount:${deployer}" --arg role "${role}" '
			any(.bindings[]?;
				.role == $role
				and (.condition // null) == null
				and ((.members // []) | index($member) != null))
		' <<< "${project_policy}" >/dev/null; then
			gcloud --quiet projects remove-iam-policy-binding "${GCP_PROJECT_ID}" \
				--member="serviceAccount:${deployer}" --role="${role}" --condition=None >/dev/null
		fi
	done
done < <(jq -r '.environments[].name' "${GCP_PLAN}")

budget_name="$(plan_value '.budget.display_name')"
budget_json="$(gcloud billing budgets list --billing-account "${GCP_BILLING_ACCOUNT}" --format=json \
	| jq -c --arg name "${budget_name}" '[.[] | select(.displayName == $name)]')"
budget_count="$(jq 'length' <<< "${budget_json}")"
[[ "${budget_count}" -le 1 ]] || gcp_die "multiple budgets have the approved display name"
if [[ "${budget_count}" == "0" ]]; then
	gcloud --quiet billing budgets create --billing-account "${GCP_BILLING_ACCOUNT}" \
		--display-name="${budget_name}" --budget-amount=200USD \
		--filter-projects="projects/${GCP_PROJECT_NUMBER}" \
		--threshold-rule=percent=0.5,basis=current-spend \
		--threshold-rule=percent=0.75,basis=current-spend \
		--threshold-rule=percent=0.9,basis=current-spend \
		--notifications-rule-monitoring-notification-channels="${GCP_BUDGET_NOTIFICATION_CHANNEL}"
fi

"${GCP_SCRIPT_DIR}/verify-project.sh"
printf 'Google Cloud provisioning verified; run bootstrap-host.sh once per instance through pinned IAP/OS Login\n'
