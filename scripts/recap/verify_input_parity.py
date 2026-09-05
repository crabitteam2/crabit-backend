"""Transmit a real PostgreSQL-backed RecapSnapshotService request to a local receiver.

Requires the compatible receiver; absence is an explicit pending integration failure.
Does not modify frozen input bytes. The Java integration test also verifies coordinator persistence and owner retrieval; browser checks remain separate.
"""
import json
import os
from pathlib import Path
import urllib.request
from urllib.parse import urlparse


def main():
    config_path = os.environ.get("CRABIT_RECAP_PARITY_CONFIG", "")
    config = json.loads(Path(config_path).read_text()) if config_path else {}
    base = config.get("url", os.environ.get("CRABIT_RECAP_PARITY_URL", ""))
    token = config.get("token", os.environ.get("CRABIT_RECAP_PARITY_TOKEN", ""))
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
    for kind in ("weekly", "monthly"):
        persisted = json.loads(Path(f"build/recap-input-parity/{kind}-persisted-request.json").read_text())
        owner = json.loads(Path(f"build/recap-input-parity/{kind}-owner-response.json").read_text())
        if persisted["kind"] != kind.upper() or owner["status"] != "SUCCEEDED" or not owner["result"]:
            raise SystemExit("Missing successful persisted owner result: " + kind)
    print("PASS: Java integration verified coordinator persistence and owner retrieval for weekly and monthly; browser checks remain separate")


if __name__ == "__main__":
    main()
