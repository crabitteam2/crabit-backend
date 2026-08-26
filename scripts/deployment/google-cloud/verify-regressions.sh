#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
for command in jq; do
	command -v "${command}" >/dev/null 2>&1 || { printf 'missing command: %s\n' "${command}" >&2; exit 1; }
done

temporary_directory="$(mktemp -d)"
trap 'rm -rf "${temporary_directory}"' EXIT
fake_bin="${temporary_directory}/bin"
fake_log="${temporary_directory}/gcloud.log"
mkdir "${fake_bin}"

cat > "${fake_bin}/gcloud" <<'FAKE_GCLOUD'
#!/usr/bin/env bash
set -Eeuo pipefail

arguments=" $* "
scenario="${FAKE_GCLOUD_SCENARIO:-success}"
project="crabit-verify-project"
project_number="123456789012"
staging_deployer="serviceAccount:crabit-staging-deployer@${project}.iam.gserviceaccount.com"
stable_deployer="serviceAccount:crabit-stable-demo-deployer@${project}.iam.gserviceaccount.com"
shared_viewer_role="projects/${project}/roles/crabitDeploymentSharedViewer"

snapshot_name_from_arguments() {
	local previous=""
	local argument
	for argument in "$@"; do
		if [[ "${previous}" == "describe" || "${previous}" == "create" ]]; then
			printf '%s\n' "${argument}"
			return 0
		fi
		previous="${argument}"
	done
	return 1
}

write_snapshot_json() {
	local snapshot_name="$1"
	local status="$2"
	local operation="${snapshot_name#crabit-staging-data-}"
	local environment="staging"
	local source_disk="crabit-staging-data"
	local storage_location="asia-northeast3"
	if [[ "${scenario}" == "snapshot-identity-drift" ]]; then
		environment="stable-demo"
	fi
	if [[ "${scenario}" == "snapshot-storage-location-drift" ]]; then
		storage_location="us-central1"
	fi
	printf '{"id":"1234567890","name":"%s","status":"%s","diskSizeGb":"100","sourceDisk":"projects/%s/zones/asia-northeast3-a/disks/%s","storageLocations":["%s"],"labels":{"crabit-environment":"%s","crabit-operation":"%s"},"creationTimestamp":"2026-08-26T00:00:00.000+09:00"}\n' \
		"${snapshot_name}" "${status}" "${project}" "${source_disk}" "${storage_location}" "${environment}" "${operation}"
}

if [[ "${arguments}" == *" compute firewall-rules describe crabit-public-https "* ]]; then
	printf '%s\n' '{"direction":"INGRESS","disabled":false,"sourceRanges":["0.0.0.0/0"],"sourceTags":[],"sourceServiceAccounts":[],"network":"projects/crabit-verify-project/global/networks/crabit-nonprod","targetTags":["crabit-staging","crabit-stable-demo"],"targetServiceAccounts":[],"allowed":[{"IPProtocol":"tcp","ports":["80","443"]}]}'
	exit 0
fi
if [[ "${arguments}" == *" compute firewall-rules describe crabit-iap-ssh "* ]]; then
	printf '%s\n' '{"direction":"INGRESS","disabled":false,"sourceRanges":["35.235.240.0/20"],"sourceTags":[],"sourceServiceAccounts":[],"network":"projects/crabit-verify-project/global/networks/crabit-nonprod","targetTags":["crabit-staging","crabit-stable-demo"],"targetServiceAccounts":[],"allowed":[{"IPProtocol":"tcp","ports":["22"]}]}'
	exit 0
fi
if [[ "${arguments}" == *" compute firewall-rules list "* ]]; then
	case "${scenario}" in
		success) printf '%s\n' '[{"direction":"INGRESS","disabled":false,"sourceRanges":["0.0.0.0/0"],"network":"projects/crabit-verify-project/global/networks/crabit-nonprod","targetTags":["crabit-staging","crabit-stable-demo"],"allowed":[{"IPProtocol":"tcp","ports":["80","443"]}]},{"direction":"INGRESS","disabled":false,"sourceRanges":["35.235.240.0/20"],"network":"projects/crabit-verify-project/global/networks/crabit-nonprod","targetTags":["crabit-staging","crabit-stable-demo"],"allowed":[{"IPProtocol":"tcp","ports":["22"]}]}]' ;;
		range-22) printf '%s\n' '[{"direction":"INGRESS","sourceRanges":["203.0.113.0/24"],"network":"projects/crabit-verify-project/global/networks/crabit-nonprod","targetTags":["crabit-staging"],"allowed":[{"IPProtocol":"tcp","ports":["20-30"]}]}]' ;;
		range-8080) printf '%s\n' '[{"direction":"INGRESS","sourceRanges":["0.0.0.0/0"],"network":"projects/crabit-verify-project/global/networks/crabit-nonprod","targetTags":["crabit-staging"],"allowed":[{"IPProtocol":"tcp","ports":["8000-9000"]}]}]' ;;
		range-5432) printf '%s\n' '[{"direction":"INGRESS","sourceRanges":["::/0"],"network":"projects/crabit-verify-project/global/networks/crabit-nonprod","targetServiceAccounts":["crabit-staging-runtime@crabit-verify-project.iam.gserviceaccount.com"],"allowed":[{"IPProtocol":"tcp","ports":["5000-6000"]}]}]' ;;
		omitted-all-tcp) printf '%s\n' '[{"direction":"INGRESS","sourceRanges":["0.0.0.0/0"],"network":"projects/crabit-verify-project/global/networks/crabit-nonprod","allowed":[{"IPProtocol":"tcp"}]}]' ;;
		mixed-public-sources) printf '%s\n' '[{"direction":"INGRESS","sourceRanges":["10.30.0.0/24","198.51.100.0/24"],"network":"projects/crabit-verify-project/global/networks/crabit-nonprod","targetTags":["crabit-staging"],"allowed":[{"IPProtocol":"tcp","ports":["5432"]}]}]' ;;
		*) printf 'unknown firewall scenario: %s\n' "${scenario}" >&2; exit 64 ;;
	 esac
	exit 0
fi

if [[ "${arguments}" == *" iam workload-identity-pools providers describe "* ]]; then
	printf '%s\n' '{"state":"ACTIVE","oidc":{"issuerUri":"https://token.actions.githubusercontent.com"},"attributeMapping":{"google.subject":"assertion.sub","attribute.repository":"assertion.repository","attribute.repository_id":"assertion.repository_id","attribute.environment":"assertion.environment"},"attributeCondition":"assertion.repository_id=='\''1332782656'\'' && (assertion.environment=='\''staging'\'' || assertion.environment=='\''stable-demo'\'')"}'
	exit 0
fi
if [[ "${arguments}" == *" iam roles describe crabitDeploymentSharedViewer "* ]]; then
	permissions='["compute.firewalls.get","compute.firewalls.list","compute.projects.get"]'
	if [[ "${scenario}" == "expanded-custom-role" ]]; then
		permissions='["compute.firewalls.get","compute.firewalls.list","compute.instances.get","compute.projects.get"]'
	fi
	printf '{"name":"%s","title":"Crabit deployment shared viewer","stage":"GA","includedPermissions":%s}\n' \
		"${shared_viewer_role}" "${permissions}"
	exit 0
fi
if [[ "${arguments}" == *" projects get-iam-policy "* ]]; then
	shared_role="${shared_viewer_role}"
	extra_binding=""
	case "${scenario}" in
		broad-network-viewer|broad-compute-viewer|broad-project-viewer|broad-compute-admin)
			case "${scenario}" in
				broad-network-viewer) shared_role="roles/compute.networkViewer" ;;
				broad-compute-viewer) shared_role="roles/compute.viewer" ;;
				broad-project-viewer) shared_role="roles/viewer" ;;
				broad-compute-admin) shared_role="roles/compute.admin" ;;
			esac
			;;
		cross-staging-instance-get|cross-staging-guest-attributes|cross-staging-serial-port|cross-staging-screenshot)
			case "${scenario}" in
				cross-staging-instance-get) permission="compute.instances.get" ;;
				cross-staging-guest-attributes) permission="compute.instances.getGuestAttributes" ;;
				cross-staging-serial-port) permission="compute.instances.getSerialPortOutput" ;;
				cross-staging-screenshot) permission="compute.instances.getScreenshot" ;;
			esac
			extra_binding=",{\"role\":\"projects/${project}/roles/peerInstanceReader\",\"members\":[\"${staging_deployer}\"],\"condition\":{\"title\":\"peer-stable-demo-${permission}\",\"expression\":\"resource.type == 'compute.googleapis.com/Instance' && resource.name == 'projects/${project}/zones/asia-northeast3-a/instances/crabit-stable-demo'\"}}"
			;;
		cross-stable-instance-get)
			extra_binding=",{\"role\":\"projects/${project}/roles/peerInstanceReader\",\"members\":[\"${stable_deployer}\"],\"condition\":{\"title\":\"peer-staging-compute.instances.get\",\"expression\":\"resource.type == 'compute.googleapis.com/Instance' && resource.name == 'projects/${project}/zones/asia-northeast3-a/instances/crabit-staging'\"}}"
			;;
	esac
	cat <<JSON
{"bindings":[
 {"role":"${shared_role}","members":["${staging_deployer}","${stable_deployer}"]},
 {"role":"roles/compute.instanceAdmin.v1","members":["${staging_deployer}"],"condition":{"title":"crabit-staging-instance","expression":"resource.type == 'compute.googleapis.com/Instance' && resource.name == 'projects/${project}/zones/asia-northeast3-a/instances/crabit-staging'"}},
 {"role":"roles/compute.osAdminLogin","members":["${staging_deployer}"],"condition":{"title":"crabit-staging-instance","expression":"resource.type == 'compute.googleapis.com/Instance' && resource.name == 'projects/${project}/zones/asia-northeast3-a/instances/crabit-staging'"}},
 {"role":"roles/iap.tunnelResourceAccessor","members":["${staging_deployer}"],"condition":{"title":"crabit-staging-iap-ssh","expression":"destination.ip == \"10.30.0.10\" && destination.port == 22"}},
 {"role":"roles/compute.storageAdmin","members":["${staging_deployer}"],"condition":{"title":"crabit-staging-snapshots","expression":"resource.type == 'compute.googleapis.com/Snapshot' && resource.name.startsWith('projects/${project}/global/snapshots/crabit-staging-data-')"}},
 {"role":"roles/compute.instanceAdmin.v1","members":["${stable_deployer}"],"condition":{"title":"crabit-stable-demo-instance","expression":"resource.type == 'compute.googleapis.com/Instance' && resource.name == 'projects/${project}/zones/asia-northeast3-a/instances/crabit-stable-demo'"}},
 {"role":"roles/compute.osAdminLogin","members":["${stable_deployer}"],"condition":{"title":"crabit-stable-demo-instance","expression":"resource.type == 'compute.googleapis.com/Instance' && resource.name == 'projects/${project}/zones/asia-northeast3-a/instances/crabit-stable-demo'"}},
 {"role":"roles/iap.tunnelResourceAccessor","members":["${stable_deployer}"],"condition":{"title":"crabit-stable-demo-iap-ssh","expression":"destination.ip == \"10.30.0.20\" && destination.port == 22"}},
 {"role":"roles/compute.storageAdmin","members":["${stable_deployer}"],"condition":{"title":"crabit-stable-demo-snapshots","expression":"resource.type == 'compute.googleapis.com/Snapshot' && resource.name.startsWith('projects/${project}/global/snapshots/crabit-stable-demo-data-')"}}${extra_binding}
]}
JSON
	exit 0
fi
if [[ "${arguments}" == *" iam service-accounts get-iam-policy "* ]]; then
	if [[ "${arguments}" == *"crabit-staging-deployer@"* ]]; then
		environment="staging"
		[[ "${scenario}" != "cross-wif" ]] || environment="stable-demo"
		printf '{"bindings":[{"role":"roles/iam.workloadIdentityUser","members":["principalSet://iam.googleapis.com/projects/%s/locations/global/workloadIdentityPools/github-actions/attribute.environment/%s"]}]}\n' \
			"${project_number}" "${environment}"
	elif [[ "${arguments}" == *"crabit-stable-demo-deployer@"* ]]; then
		printf '{"bindings":[{"role":"roles/iam.workloadIdentityUser","members":["principalSet://iam.googleapis.com/projects/%s/locations/global/workloadIdentityPools/github-actions/attribute.environment/stable-demo"]}]}\n' \
			"${project_number}"
	elif [[ "${arguments}" == *"crabit-staging-runtime@"* ]]; then
		printf '{"bindings":[{"role":"roles/iam.serviceAccountUser","members":["%s"]}]}\n' "${staging_deployer}"
	elif [[ "${arguments}" == *"crabit-stable-demo-runtime@"* ]]; then
		printf '{"bindings":[{"role":"roles/iam.serviceAccountUser","members":["%s"]}]}\n' "${stable_deployer}"
	else
		printf 'unexpected service-account policy request: %s\n' "${arguments}" >&2
		exit 64
	fi
	exit 0
fi
if [[ "${arguments}" == *" compute instances get-iam-policy "* ]]; then
	member="${staging_deployer}"
	[[ "${arguments}" != *"crabit-stable-demo"* ]] || member="${stable_deployer}"
	members="\"${member}\""
	if [[ "${scenario}" == "cross-resource" && "${arguments}" == *"crabit-staging"* ]]; then
		members="\"${staging_deployer}\",\"${stable_deployer}\""
	fi
	printf '{"bindings":[{"role":"roles/compute.instanceAdmin.v1","members":[%s]},{"role":"roles/compute.osAdminLogin","members":[%s]},{"role":"roles/iap.tunnelResourceAccessor","members":[%s]}]}\n' \
		"${members}" "${members}" "${members}"
	exit 0
fi
if [[ "${arguments}" == *" compute disks get-iam-policy "* ]]; then
	member="${staging_deployer}"
	[[ "${arguments}" != *"crabit-stable-demo-data"* ]] || member="${stable_deployer}"
	members="\"${member}\""
	if [[ "${scenario}" == "cross-resource" && "${arguments}" == *"crabit-staging-data"* ]]; then
		members="\"${staging_deployer}\",\"${stable_deployer}\""
	fi
	printf '{"bindings":[{"role":"roles/compute.storageAdmin","members":[%s]}]}\n' "${members}"
	exit 0
fi

if [[ "${arguments}" == *" billing projects describe "* ]]; then
	account="billingAccounts/AAAAAA-BBBBBB-CCCCCC"
	[[ "${scenario}" != "wrong-billing-account" ]] || account="billingAccounts/XXXXXX-YYYYYY-ZZZZZZ"
	printf '{"projectId":"%s","billingEnabled":true,"billingAccountName":"%s"}\n' "${project}" "${account}"
	exit 0
fi
if [[ "${arguments}" == *" billing budgets list "* ]]; then
	filtered_project="projects/${project_number}"
	[[ "${scenario}" != "wrong-project-filter" ]] || filtered_project="projects/999999999999"
	printf '[{"displayName":"crabit-non-production-google-cloud","amount":{"specifiedAmount":{"currencyCode":"USD","units":"200"}},"thresholdRules":[{"thresholdPercent":0.5},{"thresholdPercent":0.75},{"thresholdPercent":0.9}],"allUpdatesRule":{"monitoringNotificationChannels":["projects/%s/notificationChannels/123"]},"budgetFilter":{"projects":["%s"]}}]\n' \
		"${project}" "${filtered_project}"
	exit 0
fi

if [[ "${arguments}" == *" auth list "* ]]; then
	printf 'crabit-staging-deployer@%s.iam.gserviceaccount.com\n' "${project}"
	exit 0
fi
if [[ "${arguments}" == *" compute instances describe crabit-staging "* ]]; then
	printf '%s\n' '{"id":"1861046651349858115","name":"crabit-staging","zone":"projects/crabit-verify-project/zones/asia-northeast3-a","status":"RUNNING","networkInterfaces":[{"networkIP":"10.30.0.10"}],"disks":[{"boot":false,"deviceName":"crabit-data","source":"projects/crabit-verify-project/zones/asia-northeast3-a/disks/crabit-staging-data"}],"serviceAccounts":[{"email":"crabit-staging-runtime@crabit-verify-project.iam.gserviceaccount.com"}]}'
	exit 0
fi
if [[ "${arguments}" == *" compute instances describe crabit-stable-demo "* ]]; then
	printf '%s\n' '{"name":"crabit-stable-demo","zone":"projects/crabit-verify-project/zones/asia-northeast3-a","status":"RUNNING","networkInterfaces":[{"networkIP":"10.30.0.20"}],"disks":[{"boot":false,"deviceName":"crabit-data","source":"projects/crabit-verify-project/zones/asia-northeast3-a/disks/crabit-stable-demo-data"}],"serviceAccounts":[{"email":"crabit-stable-demo-runtime@crabit-verify-project.iam.gserviceaccount.com"}]}'
	exit 0
fi
if [[ "${arguments}" == *" compute disks describe crabit-staging-data "* ]]; then
	printf '%s\n' '{"sizeGb":"100","type":"projects/crabit-verify-project/zones/asia-northeast3-a/diskTypes/pd-balanced","users":["projects/crabit-verify-project/zones/asia-northeast3-a/instances/crabit-staging"]}'
	exit 0
fi
if [[ "${arguments}" == *" compute snapshots describe "* ]]; then
	state_directory="${FAKE_GCLOUD_STATE_DIR:?}"
	mkdir -p "${state_directory}"
	snapshot_name="$(snapshot_name_from_arguments "$@")"
	[[ -z "${FAKE_GCLOUD_LOG:-}" ]] || printf '%s\n' "$*" >> "${FAKE_GCLOUD_LOG}"
	case "${scenario}" in
		snapshot-transition|snapshot-create-failure-ready)
			[[ -f "${state_directory}/created" ]] || exit 1
			poll_count="$(<"${state_directory}/poll-count")"
			poll_count="$((poll_count + 1))"
			printf '%s\n' "${poll_count}" > "${state_directory}/poll-count"
			if (( poll_count == 1 )); then
				write_snapshot_json "${snapshot_name}" CREATING
			else
				write_snapshot_json "${snapshot_name}" READY
			fi
			;;
		snapshot-existing-ready) write_snapshot_json "${snapshot_name}" READY ;;
		snapshot-identity-drift|snapshot-storage-location-drift) write_snapshot_json "${snapshot_name}" CREATING ;;
		snapshot-terminal-failure) write_snapshot_json "${snapshot_name}" FAILED ;;
		snapshot-unknown-state) write_snapshot_json "${snapshot_name}" UNKNOWN_STATE ;;
		snapshot-timeout) write_snapshot_json "${snapshot_name}" CREATING ;;
		*) printf 'unexpected snapshot scenario: %s\n' "${scenario}" >&2; exit 64 ;;
	esac
	exit 0
fi
if [[ "${arguments}" == *" compute snapshots create "* ]]; then
	state_directory="${FAKE_GCLOUD_STATE_DIR:?}"
	mkdir -p "${state_directory}"
	[[ -z "${FAKE_GCLOUD_LOG:-}" ]] || printf '%s\n' "$*" >> "${FAKE_GCLOUD_LOG}"
	: > "${state_directory}/created"
	printf '0\n' > "${state_directory}/poll-count"
	[[ "${scenario}" != "snapshot-create-failure-ready" ]] || exit 1
	printf '{}\n'
	exit 0
fi
if [[ "${arguments}" == *" compute ssh "* || "${arguments}" == *" compute scp "* ]]; then
	[[ -z "${FAKE_GCLOUD_LOG:-}" ]] || printf '%s\n' "$*" >> "${FAKE_GCLOUD_LOG}"
	exit 0
fi

printf 'unexpected fake gcloud invocation: %s\n' "$*" >&2
exit 64
FAKE_GCLOUD

cat > "${fake_bin}/ssh-keygen" <<'FAKE_SSH_KEYGEN'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ "$#" == "4"
	&& "$1" == "-F"
	&& ("$2" == "gce-crabit-staging" || "$2" == "compute.1861046651349858115")
	&& "$3" == "-f" && -f "$4" ]]
FAKE_SSH_KEYGEN
chmod 700 "${fake_bin}/gcloud" "${fake_bin}/ssh-keygen"

expect_rejected() {
	local name="$1"
	shift
	if "$@" >"${temporary_directory}/${name}.log" 2>&1; then
		printf 'regression scenario was accepted: %s\n' "${name}" >&2
		exit 1
	fi
}

run_firewall() {
	local scenario="$1"
	PATH="${fake_bin}:${PATH}" \
	FAKE_GCLOUD_SCENARIO="${scenario}" \
	GCP_PROJECT_ID=crabit-verify-project \
	GCP_PROJECT_NUMBER=123456789012 \
		"${SCRIPT_DIR}/verify-firewall.sh" staging
}

run_firewall success >/dev/null
for scenario in range-22 range-8080 range-5432 omitted-all-tcp mixed-public-sources; do
	expect_rejected "firewall-${scenario}" run_firewall "${scenario}"
done

run_authorization() {
	local scenario="$1"
	PATH="${fake_bin}:${PATH}" \
	FAKE_GCLOUD_SCENARIO="${scenario}" \
	GCP_PROJECT_ID=crabit-verify-project \
	GCP_PROJECT_NUMBER=123456789012 \
		"${SCRIPT_DIR}/verify-authorization.sh"
}

run_authorization success >/dev/null
expect_rejected authorization-cross-wif run_authorization cross-wif
expect_rejected authorization-cross-resource run_authorization cross-resource
expect_rejected authorization-expanded-custom-role run_authorization expanded-custom-role
for scenario in broad-network-viewer broad-compute-viewer broad-project-viewer broad-compute-admin; do
	expect_rejected "authorization-${scenario}" run_authorization "${scenario}"
done
for scenario in cross-staging-instance-get cross-staging-guest-attributes \
		cross-staging-serial-port cross-staging-screenshot cross-stable-instance-get; do
	expect_rejected "authorization-${scenario}" run_authorization "${scenario}"
done

run_budget() {
	local scenario="$1"
	PATH="${fake_bin}:${PATH}" \
	FAKE_GCLOUD_SCENARIO="${scenario}" \
	GCP_PROJECT_ID=crabit-verify-project \
	GCP_PROJECT_NUMBER=123456789012 \
	GCP_BILLING_ACCOUNT=AAAAAA-BBBBBB-CCCCCC \
	GCP_BUDGET_NOTIFICATION_CHANNEL=projects/crabit-verify-project/notificationChannels/123 \
		"${SCRIPT_DIR}/verify-budget.sh"
}

run_budget success >/dev/null
expect_rejected budget-wrong-billing-account run_budget wrong-billing-account
expect_rejected budget-wrong-project-filter run_budget wrong-project-filter

snapshot_directory="${temporary_directory}/snapshots"
snapshot_state="${snapshot_directory}/state"
snapshot_log="${snapshot_directory}/gcloud.log"
snapshot_proof="${snapshot_directory}/proof.env"
mkdir "${snapshot_directory}"

run_snapshot() {
	local scenario="$1"
	local operation_id="verify-${scenario#snapshot-}"
	rm -rf "${snapshot_state}"
	rm -f "${snapshot_log}" "${snapshot_proof}"
	mkdir "${snapshot_state}"
	PATH="${fake_bin}:${PATH}" \
	FAKE_GCLOUD_SCENARIO="${scenario}" \
	FAKE_GCLOUD_STATE_DIR="${snapshot_state}" \
	FAKE_GCLOUD_LOG="${snapshot_log}" \
	GCP_PROJECT_ID=crabit-verify-project \
	GCP_PROJECT_NUMBER=123456789012 \
	CRABIT_GCP_SNAPSHOT_READY_TIMEOUT_SECONDS=1 \
	CRABIT_GCP_SNAPSHOT_POLL_INTERVAL_SECONDS=1 \
		"${SCRIPT_DIR}/create-snapshot.sh" staging "${operation_id}" "${snapshot_proof}"
}

snapshot_create_count() {
	grep -c 'compute snapshots create' "${snapshot_log}" 2>/dev/null || true
}

assert_snapshot_proof() {
	local operation_id="$1"
	[[ -f "${snapshot_proof}" ]]
	grep -qx 'CRABIT_GCP_ENV=staging' "${snapshot_proof}"
	grep -qx 'CRABIT_GCP_PROJECT_ID=crabit-verify-project' "${snapshot_proof}"
	grep -qx 'CRABIT_GCP_ZONE=asia-northeast3-a' "${snapshot_proof}"
	grep -qx 'CRABIT_GCP_INSTANCE=crabit-staging' "${snapshot_proof}"
	grep -qx 'CRABIT_GCP_DATA_DISK=crabit-staging-data' "${snapshot_proof}"
	grep -qx 'CRABIT_GCP_SNAPSHOT_STATUS=READY' "${snapshot_proof}"
	grep -qx 'CRABIT_GCP_SNAPSHOT_ID=1234567890' "${snapshot_proof}"
	grep -qx 'CRABIT_GCP_SNAPSHOT_SIZE_GB=100' "${snapshot_proof}"
	grep -qx 'CRABIT_GCP_SNAPSHOT_CREATED_AT=2026-08-26T00:00:00.000+09:00' "${snapshot_proof}"
	grep -qx "CRABIT_GCP_OPERATION_ID=${operation_id}" "${snapshot_proof}"
	grep -qx "CRABIT_GCP_SNAPSHOT=crabit-staging-data-${operation_id}" "${snapshot_proof}"
	local mode
	mode="$(stat -c '%a' "${snapshot_proof}" 2>/dev/null || stat -f '%Lp' "${snapshot_proof}")"
	[[ "${mode}" == "600" ]]
}

run_snapshot snapshot-transition >/dev/null
[[ "$(snapshot_create_count)" == "1" ]]
assert_snapshot_proof verify-transition

run_snapshot snapshot-existing-ready >/dev/null
[[ "$(snapshot_create_count)" == "0" ]]
assert_snapshot_proof verify-existing-ready

run_snapshot snapshot-create-failure-ready >/dev/null
[[ "$(snapshot_create_count)" == "1" ]]
assert_snapshot_proof verify-create-failure-ready

for scenario in snapshot-identity-drift snapshot-storage-location-drift \
		snapshot-terminal-failure snapshot-unknown-state snapshot-timeout; do
	expect_rejected "${scenario}" run_snapshot "${scenario}"
	[[ "$(snapshot_create_count)" == "0" ]]
	[[ ! -e "${snapshot_proof}" ]] || {
		printf 'rejected snapshot scenario wrote proof: %s\n' "${scenario}" >&2
		exit 1
	}
done

transport_directory="${temporary_directory}/transport"
mkdir "${transport_directory}"
archive="${transport_directory}/deployment.tgz"
known_hosts="${transport_directory}/known-hosts"
touch "${archive}" "${known_hosts}"
chmod 600 "${known_hosts}"

write_transport_pair() {
	local environment="$1"
	local runtime_file="$2"
	local proof_file="$3"
	local instance="crabit-${environment}"
	local disk="${instance}-data"
	(umask 077; printf '%s\n' \
		"CRABIT_ENV=${environment}" \
		'CRABIT_COMPOSE_PROJECT=crabit-verify' \
		'CRABIT_SPRING_PROFILE=e2e' \
		'CRABIT_PUBLIC_HOST=api-staging.example' \
		'CRABIT_DATABASE_NAME=crabit' \
		'CRABIT_DATABASE_USERNAME=crabit' \
		'CRABIT_DATABASE_PASSWORD=verify-secret' \
		'CRABIT_GCP_PROJECT_ID=crabit-verify-project' \
		'CRABIT_GCP_ZONE=asia-northeast3-a' \
		"CRABIT_GCP_INSTANCE=${instance}" \
		"CRABIT_GCP_DATA_DISK=${disk}" > "${runtime_file}")
	(umask 077; printf '%s\n' \
		"CRABIT_GCP_ENV=${environment}" \
		'CRABIT_GCP_PROJECT_ID=crabit-verify-project' \
		'CRABIT_GCP_ZONE=asia-northeast3-a' \
		"CRABIT_GCP_INSTANCE=${instance}" \
		"CRABIT_GCP_DATA_DISK=${disk}" \
		"CRABIT_GCP_SNAPSHOT=${disk}-deploy-test" \
		'CRABIT_GCP_SNAPSHOT_ID=1234567890' \
		'CRABIT_GCP_SNAPSHOT_STATUS=READY' \
		'CRABIT_GCP_SNAPSHOT_SIZE_GB=100' \
		'CRABIT_GCP_OPERATION_ID=deploy-test' \
		'CRABIT_GCP_SNAPSHOT_CREATED_AT=2026-08-26T00:00:00.000+09:00' > "${proof_file}")
}

staging_runtime="${transport_directory}/staging-runtime.env"
staging_proof="${transport_directory}/staging-proof.env"
stable_runtime="${transport_directory}/stable-runtime.env"
stable_proof="${transport_directory}/stable-proof.env"
write_transport_pair staging "${staging_runtime}" "${staging_proof}"
write_transport_pair stable-demo "${stable_runtime}" "${stable_proof}"

run_transport() {
	local runtime_file="$1"
	local proof_file="$2"
	PATH="${fake_bin}:${PATH}" \
	FAKE_GCLOUD_SCENARIO=transport \
	HOME="${transport_directory}/runner-home" \
	FAKE_GCLOUD_LOG="${fake_log}" \
	GCP_PROJECT_ID=crabit-verify-project \
	GCP_PROJECT_NUMBER=123456789012 \
	CRABIT_GCP_KNOWN_HOSTS="${known_hosts}" \
	GITHUB_SHA=0123456789abcdef0123456789abcdef01234567 \
		"${SCRIPT_DIR}/run-over-iap.sh" staging "${archive}" "${runtime_file}" "${proof_file}" \
			deploy.sh sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa e2e
}

rm -f "${fake_log}"
run_transport "${staging_runtime}" "${staging_proof}" >/dev/null
grep -q 'compute ssh crabit-staging' "${fake_log}"
grep -q 'compute scp.*crabit-staging:' "${fake_log}"
cmp -s "${known_hosts}" "${transport_directory}/runner-home/.ssh/google_compute_known_hosts"

rm -f "${fake_log}"
expect_rejected transport-swapped-environment run_transport "${stable_runtime}" "${stable_proof}"
[[ ! -e "${fake_log}" ]] || {
	printf 'swapped transport reached gcloud before environment binding failed\n' >&2
	exit 1
}

printf 'Google Cloud regressions verified: wif=isolated authorization=least-privilege firewall=ranges snapshot=ready-bounded transport=bound budget=exact\n'
