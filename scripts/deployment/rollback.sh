#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${script_dir}/common.sh"

[[ "$#" == "4" ]] || die "usage: rollback.sh <backend-sha256:digest> <recap-sha256:digest> <e2e|demo> I_VERIFIED_MIGRATION_COMPATIBILITY"
[[ "$4" == "I_VERIFIED_MIGRATION_COMPATIBILITY" ]] \
	|| die "rollback requires an explicit migration-compatibility confirmation"

exec "${script_dir}/deploy.sh" "$1" "$2" "$3"
