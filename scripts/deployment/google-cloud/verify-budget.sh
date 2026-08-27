#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
[[ "$#" == "0" ]] || gcp_die "usage: verify-budget.sh"
for command in gcloud jq; do gcp_require_command "${command}"; done
validate_plan
validate_google_identity
gcp_require_env GCP_BILLING_ACCOUNT
gcp_require_env GCP_BUDGET_NOTIFICATION_CHANNEL
[[ "${GCP_BILLING_ACCOUNT}" =~ ^[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}$ ]] \
	|| gcp_die "GCP_BILLING_ACCOUNT is invalid"
[[ "${GCP_BUDGET_NOTIFICATION_CHANNEL}" =~ ^projects/${GCP_PROJECT_ID}/notificationChannels/[0-9]+$ ]] \
	|| gcp_die "GCP_BUDGET_NOTIFICATION_CHANNEL is invalid"

billing_project_json="$(gcloud billing projects describe "${GCP_PROJECT_ID}" --format=json)"
jq -e --arg project "${GCP_PROJECT_ID}" --arg account "billingAccounts/${GCP_BILLING_ACCOUNT}" '
	.projectId == $project
	and .billingEnabled == true
	and .billingAccountName == $account
' <<< "${billing_project_json}" >/dev/null \
	|| gcp_die "project billing linkage does not match GCP_BILLING_ACCOUNT"

budget_json="$(gcloud billing budgets list --billing-account "${GCP_BILLING_ACCOUNT}" --format=json \
	| jq -cer --arg name "$(plan_value '.budget.display_name')" \
		'[.[] | select(.displayName == $name)] | if length == 1 then .[0] else error("budget count") end')" \
	|| gcp_die "exactly one approved budget is required"
jq -e \
	--arg channel "${GCP_BUDGET_NOTIFICATION_CHANNEL}" \
	--arg project "projects/${GCP_PROJECT_NUMBER}" '
	.amount.specifiedAmount.currencyCode == "USD"
	and .amount.specifiedAmount.units == "200"
	and ([.thresholdRules[].thresholdPercent] | sort) == [0.5,0.75,0.9]
	and (.allUpdatesRule.monitoringNotificationChannels // []) == [$channel]
	and (.budgetFilter.projects // []) == [$project]
' <<< "${budget_json}" >/dev/null \
	|| gcp_die "budget amount, thresholds, destination, or exact project filter is invalid"

printf 'Google Cloud budget verified: billing-account=%s project=%s amount=USD200\n' \
	"${GCP_BILLING_ACCOUNT}" "${GCP_PROJECT_NUMBER}"
