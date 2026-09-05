"""Transmit a real PostgreSQL-backed RecapSnapshotService request to a local receiver.

Requires the compatible receiver; absence is an explicit pending integration failure.
Does not modify frozen input bytes or claim coordinator/browser verification.
"""
import json
import os
from pathlib import Path
import urllib.request
from urllib.parse import urlparse


def main():
    base = os.environ.get("CRABIT_RECAP_PARITY_URL", "")
    token = os.environ.get("CRABIT_RECAP_PARITY_TOKEN", "")
    if not base or not token:
        raise SystemExit("PENDING: set CRABIT_RECAP_PARITY_URL and CRABIT_RECAP_PARITY_TOKEN for the compatible local Python receiver")
    if urlparse(base).hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise SystemExit("Parity harness requires a local receiver")
    for kind in ("weekly", "monthly"):
        path = Path(f"build/recap-input-parity/{kind}-request.json")
        raw = path.read_bytes()
        request = json.loads(raw)
        call = urllib.request.Request(base.rstrip("/") + "/internal/v1/recap-generations", raw,
            {"Content-Type": "application/json", "Authorization": "Bearer " + token,
             "Idempotency-Key": request["generation_id"]}, method="POST")
        with urllib.request.urlopen(call, timeout=30) as response:
            result = json.load(response)
        for field in ("generation_id", "input_digest", "schema_version", "algorithm_version", "kind", "student_id", "card_balance_account_id", "academy_id", "period"):
            if result[field] != request[field]:
                raise SystemExit("Identity mismatch: " + field)
        if not result.get("view"):
            raise SystemExit("Receiver returned no view")
        Path(f"build/recap-input-parity/{kind}-result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print("PASS: actual database snapshot -> Python HTTP result identity and view")
    print("Coordinator persistence and owner/browser retrieval require the integration gate checks")


if __name__ == "__main__":
    main()
