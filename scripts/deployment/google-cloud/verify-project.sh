#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
for command in gcloud jq; do gcp_require_command "${command}"; done
validate_plan
validate_google_identity
gcp_require_env GCP_BILLING_ACCOUNT
gcp_require_env GCP_BUDGET_NOTIFICATION_CHANNEL
readonly region="$(plan_value '.location.region')"
readonly zone="$(plan_value '.location.zone')"
readonly vpc="$(plan_value '.network.vpc')"

network_json="$(gcloud_json compute networks describe "${vpc}")"
jq -e '.autoCreateSubnetworks == false' <<< "${network_json}" >/dev/null \
	|| gcp_die "Crabit VPC must use custom subnet mode"
subnet_json="$(gcloud_json compute networks subnets describe "$(plan_value '.network.subnet')" --region "${region}")"
jq -e --arg cidr "$(plan_value '.network.subnet_cidr')" --arg vpc "${vpc}" '
	.ipCidrRange == $cidr and (.network | endswith("/networks/" + $vpc))
' <<< "${subnet_json}" >/dev/null || gcp_die "Crabit Seoul subnet read-back is invalid"

all_tags="$(jq -c '[.environments[].network_tag]' "${GCP_PLAN}")"
public_rule="$(gcloud_json compute firewall-rules describe crabit-public-https)"
jq -e --arg vpc "${vpc}" --argjson tags "${all_tags}" '
	.direction == "INGRESS" and .disabled != true and .sourceRanges == ["0.0.0.0/0"]
	and (.network | endswith("/networks/" + $vpc))
	and ((.targetTags | sort) == ($tags | sort))
	and ([.allowed[] | select(.IPProtocol == "tcp") | .ports[]] | sort) == ["443","80"]
' <<< "${public_rule}" >/dev/null || gcp_die "public firewall read-back differs from TCP 80/443 only"
iap_rule="$(gcloud_json compute firewall-rules describe crabit-iap-ssh)"
jq -e --arg source "$(plan_value '.network.iap_source_range')" --arg vpc "${vpc}" --argjson tags "${all_tags}" '
	.direction == "INGRESS" and .disabled != true and .sourceRanges == [$source]
	and (.network | endswith("/networks/" + $vpc))
	and ((.targetTags | sort) == ($tags | sort))
	and ([.allowed[] | select(.IPProtocol == "tcp") | .ports[]] == ["22"])
' <<< "${iap_rule}" >/dev/null || gcp_die "IAP SSH firewall read-back is invalid"

all_rules="$(gcloud_json compute firewall-rules list)"
jq -e --argjson tags "${all_tags}" --arg vpc "${vpc}" '
	all(.[];
		((.network | endswith("/networks/" + $vpc)) | not)
		or
		(.direction != "INGRESS" or .disabled == true)
		or ((.sourceRanges // []) | all(. != "0.0.0.0/0" and . != "::/0"))
		or (((.targetTags // []) | length > 0) and (((.targetTags // []) - $tags) | length == ((.targetTags // []) | length)))
		or all(.allowed[]?;
			.IPProtocol != "all"
			and (.ports != null)
			and all(.ports[]; . != "22" and . != "8080" and . != "5432")
		)
	)
' <<< "${all_rules}" >/dev/null || gcp_die "a public firewall rule exposes TCP 22, 8080, or 5432 to a Crabit instance"

project_policy="$(gcloud_json projects get-iam-policy "${GCP_PROJECT_ID}")"
readonly pool="$(plan_value '.identity.workload_identity_pool')"
readonly repository="$(plan_value '.github_repository')"
readonly wif_principal="principalSet://iam.googleapis.com/projects/${GCP_PROJECT_NUMBER}/locations/global/workloadIdentityPools/${pool}/attribute.repository/${repository}"
readonly allowed_deployer_roles='["roles/compute.instanceAdmin.v1","roles/compute.osAdminLogin","roles/compute.storageAdmin","roles/iap.tunnelResourceAccessor"]'

while IFS= read -r environment; do
	config="$(environment_json "${environment}")"
	instance="$(jq -r '.instance' <<< "${config}")"
	disk="$(jq -r '.data_disk' <<< "${config}")"
	address="$(jq -r '.address' <<< "${config}")"
	policy="$(jq -r '.snapshot_policy' <<< "${config}")"
	tag="$(jq -r '.network_tag' <<< "${config}")"
	runtime="$(runtime_email "${environment}")"
	instance_json="$(gcloud_json compute instances describe "${instance}" --zone "${zone}")"
	jq -e --arg machine "$(jq -r '.machine_type' <<< "${config}")" --arg disk "${disk}" --arg runtime "${runtime}" --arg tag "${tag}" --arg vpc "${vpc}" '
		.status == "RUNNING"
		and (.machineType | endswith("/machineTypes/" + $machine))
		and any(.disks[]; .boot == false and .mode == "READ_WRITE" and .deviceName == "crabit-data" and (.source | endswith("/disks/" + $disk)))
		and any(.serviceAccounts[]; .email == $runtime)
		and (.tags.items | index($tag) != null)
		and all(.networkInterfaces[]; (.network | endswith("/networks/" + $vpc)))
		and any(.metadata.items[]; .key == "enable-oslogin" and .value == "TRUE")
		and any(.metadata.items[]; .key == "block-project-ssh-keys" and .value == "TRUE")
	' <<< "${instance_json}" >/dev/null || gcp_die "instance read-back is invalid for ${environment}"
	boot_disk="$(jq -r '.disks[] | select(.boot == true) | .source | split("/")[-1]' <<< "${instance_json}")"
	boot_json="$(gcloud_json compute disks describe "${boot_disk}" --zone "${zone}")"
	jq -e --argjson size "$(jq '.boot_disk_gb' <<< "${config}")" '
		.sizeGb == ($size | tostring) and (.type | endswith("/diskTypes/pd-balanced"))
	' <<< "${boot_json}" >/dev/null || gcp_die "boot disk read-back is invalid for ${environment}"
	data_json="$(gcloud_json compute disks describe "${disk}" --zone "${zone}")"
	jq -e --argjson size "$(jq '.data_disk_gb' <<< "${config}")" --arg policy "${policy}" '
		.sizeGb == ($size | tostring)
		and (.type | endswith("/diskTypes/pd-balanced"))
		and any(.resourcePolicies[]; endswith("/resourcePolicies/" + $policy))
		and ([.users[]?] | length == 1)
	' <<< "${data_json}" >/dev/null || gcp_die "data disk or single-writer read-back is invalid for ${environment}"
	address_json="$(gcloud_json compute addresses describe "${address}" --region "${region}")"
	jq -e '.status == "IN_USE" and (.address | test("^[0-9]+(\\.[0-9]+){3}$"))' <<< "${address_json}" >/dev/null \
		|| gcp_die "reserved address read-back is invalid for ${environment}"
	describe_policy="$(gcloud_json compute resource-policies describe "${policy}" --region "${region}")"
	jq -e --argjson retention "$(plan_value '.snapshots.retention_days')" '
		.snapshotSchedulePolicy.schedule.dailySchedule.daysInCycle == 1
		and .snapshotSchedulePolicy.retentionPolicy.maxRetentionDays == $retention
	' <<< "${describe_policy}" >/dev/null || gcp_die "snapshot policy read-back is invalid for ${environment}"
	describe_deployer="$(gcloud_json iam service-accounts describe "$(deployer_email "${environment}")")"
	describe_runtime="$(gcloud_json iam service-accounts describe "${runtime}")"
	jq -e '.disabled != true' <<< "${describe_deployer}" >/dev/null
	jq -e '.disabled != true' <<< "${describe_runtime}" >/dev/null
	deployer="$(deployer_email "${environment}")"
	jq -e --arg member "serviceAccount:${deployer}" --argjson allowed "${allowed_deployer_roles}" '
		[.bindings[] | select((.members // []) | index($member) != null) | .role] | sort == ($allowed | sort)
	' <<< "${project_policy}" >/dev/null || gcp_die "deployer project roles are missing or broader than approved for ${environment}"
	deployer_policy="$(gcloud_json iam service-accounts get-iam-policy "${deployer}")"
	jq -e --arg member "${wif_principal}" '
		any(.bindings[]; .role == "roles/iam.workloadIdentityUser" and ((.members // []) | index($member) != null))
	' <<< "${deployer_policy}" >/dev/null || gcp_die "repository WIF principal is not bound to ${environment} deployer"
	runtime_policy="$(gcloud_json iam service-accounts get-iam-policy "${runtime}")"
	jq -e --arg member "serviceAccount:${deployer}" '
		any(.bindings[]; .role == "roles/iam.serviceAccountUser" and ((.members // []) | index($member) != null))
	' <<< "${runtime_policy}" >/dev/null || gcp_die "deployer cannot attach the exact ${environment} runtime identity"
	for service_account in "${deployer}" "${runtime}"; do
		keys_json="$(gcloud_json iam service-accounts keys list --iam-account "${service_account}")"
		jq -e 'all(.[]; .keyType != "USER_MANAGED")' <<< "${keys_json}" >/dev/null \
			|| gcp_die "long-lived user-managed service-account key exists for ${service_account}"
	done
done < <(jq -r '.environments[].name' "${GCP_PLAN}")

provider_json="$(gcloud_json iam workload-identity-pools providers describe "$(plan_value '.identity.workload_identity_provider')" \
	--workload-identity-pool "$(plan_value '.identity.workload_identity_pool')" --location global)"
jq -e --arg issuer "$(plan_value '.identity.oidc_issuer')" --arg repository "$(plan_value '.github_repository')" '
	.state == "ACTIVE"
	and .oidc.issuerUri == $issuer
	and .attributeMapping."attribute.repository" == "assertion.repository"
	and (.attributeCondition | contains($repository))
' <<< "${provider_json}" >/dev/null || gcp_die "Workload Identity provider read-back is invalid"

budget_json="$(gcloud billing budgets list --billing-account "${GCP_BILLING_ACCOUNT}" --format=json \
	| jq -cer --arg name "$(plan_value '.budget.display_name')" '[.[] | select(.displayName == $name)] | if length == 1 then .[0] else error("budget count") end')" \
	|| gcp_die "exactly one approved budget is required"
jq -e --arg channel "${GCP_BUDGET_NOTIFICATION_CHANNEL}" '
	.amount.specifiedAmount.currencyCode == "USD"
	and .amount.specifiedAmount.units == "200"
	and ([.thresholdRules[].thresholdPercent] | sort) == [0.5,0.75,0.9]
	and ((.allUpdatesRule.monitoringNotificationChannels // []) | index($channel) != null)
' <<< "${budget_json}" >/dev/null || gcp_die "budget amount, thresholds, or notification destination read-back is invalid"

printf 'Google Cloud project verified: environments=2 public=80/443 iap=22 disks=single-writer budget=USD200\n'
