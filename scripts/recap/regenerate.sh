#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "$#" != 5 ]]; then
  printf 'usage: regenerate.sh <backend-boot.jar> <account-uuid> <WEEKLY|MONTHLY> <completed-week-start|month> <request-uuid>\n' >&2
  exit 2
fi
[[ -f "$1" ]] || { printf 'backend boot jar was not found\n' >&2; exit 2; }
exec java -Dloader.main=com.crabit.backend.recap.RecapRegenerationCommand \
  -cp "$1" org.springframework.boot.loader.launch.PropertiesLauncher \
  "--account=$2" "--kind=$3" "--period=$4" "--request-key=$5"
