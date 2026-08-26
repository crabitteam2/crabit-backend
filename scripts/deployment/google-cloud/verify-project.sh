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

while IFS= read -r environment; do
	config="$(environment_json "${environment}")"
	instance="$(jq -r '.instance' <<< "${config}")"
	disk="$(jq -r '.data_disk' <<< "${config}")"
	address="$(jq -r '.address' <<< "${config}")"
	policy="$(jq -r '.snapshot_policy' <<< "${config}")"
	tag="$(jq -r '.network_tag' <<< "${config}")"
	runtime="$(runtime_email "${environment}")"
	"${GCP_SCRIPT_DIR}/verify-firewall.sh" "${environment}"
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
	for service_account in "${deployer}" "${runtime}"; do
		keys_json="$(gcloud_json iam service-accounts keys list --iam-account "${service_account}")"
		jq -e 'all(.[]; .keyType != "USER_MANAGED")' <<< "${keys_json}" >/dev/null \
			|| gcp_die "long-lived user-managed service-account key exists for ${service_account}"
	done
done < <(jq -r '.environments[].name' "${GCP_PLAN}")

"${GCP_SCRIPT_DIR}/verify-authorization.sh"
"${GCP_SCRIPT_DIR}/verify-budget.sh"

printf 'Google Cloud project verified: environments=2 public=80/443 iap=22 disks=single-writer budget=USD200\n'
