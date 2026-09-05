#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
./gradlew test --tests '*RecapSnapshotServiceTest' --tests '*RecapAuthorMetricsTest' --tests '*RecapQueryServicePrivacyTest' --rerun-tasks --console=plain
python3 scripts/recap/verify_input_parity.py
