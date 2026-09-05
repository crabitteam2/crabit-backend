#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${CRABIT_RECAP_PARITY_CONFIG:?Set CRABIT_RECAP_PARITY_CONFIG to a local receiver JSON file containing url and token}"
test -r "$CRABIT_RECAP_PARITY_CONFIG"
./gradlew test --tests '*RecapSnapshotServiceTest' --tests '*RecapAuthorMetricsTest' --tests '*RecapQueryServicePrivacyTest' --tests '*RecapInputParityIntegrationTest' --rerun-tasks --console=plain
python3 scripts/recap/verify_input_parity.py
