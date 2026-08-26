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
readonly repository="$(plan_value '.github_repository')"
readonly pool="$(plan_value '.identity.workload_identity_pool')"
readonly provider="$(plan_value '.identity.workload_identity_provider')"
readonly vpc="$(plan_value '.network.vpc')"
readonly subnet="$(plan_value '.network.subnet')"
readonly principal="principalSet://iam.googleapis.com/projects/${GCP_PROJECT_NUMBER}/locations/global/workloadIdentityPools/${pool}/attribute.repository/${repository}"

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
		--attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" \
		--attribute-condition="assertion.repository=='${repository}'" \
		--project "${GCP_PROJECT_ID}"
fi

if ! gcloud compute networks describe "${vpc}" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
	gcloud --quiet compute networks create "${vpc}" --project "${GCP_PROJECT_ID}" --subnet-mode=custom
fi
if ! gcloud compute networks subnets describe "${subnet}" --region "${region}" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
	gcloud --quiet compute networks subnets create "${subnet}" --project "${GCP_PROJECT_ID}" \
		--network="${vpc}" --region="${region}" --range="$(plan_value '.network.subnet_cidr')"
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
	for account in "${deployer_account}" "${runtime_account}"; do
		if ! gcloud iam service-accounts describe "${account}@${GCP_PROJECT_ID}.iam.gserviceaccount.com" --project "${GCP_PROJECT_ID}" >/dev/null 2>&1; then
			gcloud --quiet iam service-accounts create "${account}" --project "${GCP_PROJECT_ID}" \
				--display-name="Crabit ${environment} ${account##*-}"
		fi
	done
	gcloud --quiet iam service-accounts add-iam-policy-binding "${deployer}" \
		--project "${GCP_PROJECT_ID}" --member="${principal}" --role=roles/iam.workloadIdentityUser >/dev/null
	gcloud --quiet iam service-accounts add-iam-policy-binding "${runtime}" \
		--project "${GCP_PROJECT_ID}" --member="serviceAccount:${deployer}" --role=roles/iam.serviceAccountUser >/dev/null
	for role in roles/compute.instanceAdmin.v1 roles/compute.storageAdmin roles/compute.osAdminLogin roles/iap.tunnelResourceAccessor; do
		gcloud --quiet projects add-iam-policy-binding "${GCP_PROJECT_ID}" \
			--member="serviceAccount:${deployer}" --role="${role}" >/dev/null
	done

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
