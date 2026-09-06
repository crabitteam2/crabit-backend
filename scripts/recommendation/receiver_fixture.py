#!/usr/bin/env python3
"""Strict, dependency-free v3 contract validator and local receiver fixture.

The schema walker supports only the keywords used by the checked-in schemas.
It is not a general-purpose JSON Schema implementation. Semantic checks below
also enforce relationships that JSON Schema cannot express.
"""
from __future__ import annotations

import argparse
import json
import re
from datetime import date, datetime, time, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "api/recommendation"
FIXTURES = ROOT / "src/test/resources/recommendation"
MAX_SAFE = 9007199254740991
MAX_BODY = 262144
TYPES = ("WISH_DEPOSIT", "WISH_WITHDRAWAL", "WISH_TRANSFER",
         "WISH_COMPLETION_RETURN", "WISH_ABANDONMENT_RETURN", "WISH_DELETION_RETURN")
METRICS = ("deposits", "withdrawals", "transfers", "completion_returns",
           "abandonment_returns", "deletion_returns")
GROUPS = ("latest", "recently_completed", "interest")
QUOTAS = dict(zip(GROUPS, (50, 25, 25)))
KST = ZoneInfo("Asia/Seoul")
EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
INSTANT = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$")


class InvalidContract(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidContract(message)


def strict_loads(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "duplicate JSON property")
            result[key] = value
        return result

    def constant(_):
        raise InvalidContract("non-finite JSON number")

    try:
        return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)
    except (ValueError, UnicodeError) as error:
        raise InvalidContract("invalid JSON document") from error


def schema_check(value, schema, root=None, path="$"):
    root = schema if root is None else root
    if "$ref" in schema:
        require(schema["$ref"].startswith("#/$defs/"), "unsupported schema reference")
        return schema_check(value, root["$defs"][schema["$ref"].split("/")[-1]], root, path)
    if "anyOf" in schema:
        for option in schema["anyOf"]:
            try:
                schema_check(value, option, root, path)
                return
            except InvalidContract:
                pass
        raise InvalidContract(path + ": no allowed shape")
    kinds = schema.get("type", [])
    kinds = [kinds] if isinstance(kinds, str) else kinds
    predicates = {"object": lambda x: type(x) is dict, "array": lambda x: type(x) is list,
                  "string": lambda x: type(x) is str, "integer": lambda x: type(x) is int,
                  "boolean": lambda x: type(x) is bool, "null": lambda x: x is None}
    require(not kinds or any(predicates[kind](value) for kind in kinds), path + ": wrong type")
    if "const" in schema:
        require(type(value) is type(schema["const"]) and value == schema["const"], path + ": wrong constant")
    if "enum" in schema:
        require(any(type(value) is type(item) and value == item for item in schema["enum"]), path + ": wrong enum")
    if type(value) is dict:
        properties = schema.get("properties", {})
        require(set(schema.get("required", [])) <= value.keys(), path + ": missing property")
        if schema.get("additionalProperties") is False:
            require(value.keys() <= properties.keys(), path + ": unknown property")
        for key, item in value.items():
            if key in properties:
                schema_check(item, properties[key], root, path + "." + key)
    elif type(value) is list:
        require(len(value) >= schema.get("minItems", 0), path + ": too few items")
        require(len(value) <= schema.get("maxItems", len(value)), path + ": too many items")
        if schema.get("uniqueItems"):
            require(len({json.dumps(v, sort_keys=True) for v in value}) == len(value), path + ": duplicate item")
        for i, item in enumerate(value):
            if "items" in schema:
                schema_check(item, schema["items"], root, f"{path}[{i}]")
    elif type(value) is str:
        require(len(value) >= schema.get("minLength", 0), path + ": short text")
        require(len(value) <= schema.get("maxLength", len(value)), path + ": long text")
        if "pattern" in schema:
            require(re.fullmatch(schema["pattern"], value) is not None, path + ": pattern mismatch")
    elif type(value) is int:
        require(value >= schema.get("minimum", value), path + ": below minimum")
        require(value <= schema.get("maximum", value), path + ": above maximum")


def check_document(value, name):
    schema_check(value, strict_loads((SCHEMAS / name).read_bytes()))


def calendar_date(value):
    require(re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) is not None, "invalid calendar date")
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise InvalidContract("invalid calendar date") from error


def instant(value, output=False):
    match = INSTANT.fullmatch(value)
    require(match is not None, "invalid timestamp")
    require(not output or value.endswith("Z"), "output timestamp must be UTC")
    try:
        whole = datetime.fromisoformat(match[1] + ("+00:00" if match[3] == "Z" else match[3]))
        offset = whole.utcoffset()
        require(offset is not None and abs(offset) < timedelta(hours=24), "invalid offset")
        delta = whole.astimezone(timezone.utc) - EPOCH
        seconds = delta.days * 86400 + delta.seconds
        return seconds * 1000000000 + int((match[2] or "").ljust(9, "0"))
    except ValueError as error:
        raise InvalidContract("invalid timestamp") from error


def midnight(day):
    value = datetime.combine(day, time(), KST).astimezone(timezone.utc) - EPOCH
    return (value.days * 86400 + value.seconds) * 1000000000


def validate_request(value, snapshot_at=None):
    check_document(value, "handoff-request-v3.schema.json")
    if "period" in value:
        period = value["period"]
        days = (calendar_date(period["end_date_exclusive"]) - calendar_date(period["start_date"])).days
        require(1 <= days <= 366, "period must contain 1 through 366 dates")
    context = value.get("interest_context")
    if context is not None:
        require(context["card_balance_account_id"].lower() == value["card_balance_account_id"].lower(), "context account mismatch")
        classified = instant(context["classified_at"])
        if snapshot_at is not None:
            require(classified <= instant(snapshot_at), "future classification")
        ids = [item["wish_id"].lower() for item in context["wish_classifications"]]
        require(len(ids) == len(set(ids)), "duplicate classified wish")


def validate_request_document(raw, snapshot_at=None):
    encoded = raw.encode("utf-8") if isinstance(raw, str) else raw
    require(0 < len(encoded) <= MAX_BODY, "request body size")
    value = strict_loads(encoded)
    validate_request(value, snapshot_at)
    return value


def validate_ack(value, handoff_id):
    check_document(value, "receiver-ack-v3.schema.json")
    require(value["handoff_id"].lower() == handoff_id.lower(), "acknowledgment identity mismatch")


def coverage(value, start, end, opened, snapshot):
    left, right = max(start, opened), min(end, snapshot)
    reasons = (["before_account_opened"] if start < opened else []) + (["after_snapshot"] if end > snapshot else [])
    require(value["reasons"] == reasons, "coverage reasons mismatch")
    if left >= right:
        require(value["status"] == "unobserved", "empty coverage status")
        require(value["observed_start_at"] is None and value["observed_end_at_exclusive"] is None, "empty coverage bounds")
    else:
        expected = "fully_observed" if left == start and right == end else "partially_observed"
        require(value["status"] == expected, "coverage status mismatch")
        require(value["observed_start_at"] is not None and value["observed_end_at_exclusive"] is not None, "missing coverage bounds")
        require(instant(value["observed_start_at"], True) == left and instant(value["observed_end_at_exclusive"], True) == right, "coverage bounds mismatch")


def flattened(metrics):
    result = {"abandonment_count": metrics["abandonment_count"]}
    for name in METRICS:
        for key, value in metrics[name].items():
            result[name + "." + key] = value
        pair = metrics[name]
        require((pair["count"] == 0) == (pair["amount"] == 0), "ordinary count and amount disagree")
    for kind in TYPES:
        for key, value in metrics["corrections"][kind].items():
            result["corrections." + kind + "." + key] = value
        correction = metrics["corrections"][kind]
        require(correction["count"] != 0 or correction["positive_amount"] == correction["negative_amount"] == 0, "empty correction has amount")
    return result


def has_activity(metrics):
    return metrics["abandonment_count"] > 0 or any(metrics[key]["count"] > 0 for key in METRICS) or any(item["count"] > 0 for item in metrics["corrections"].values())


def validate_snapshot(value):
    check_document(value, "snapshot-v3.schema.json")
    snap = instant(value["snapshot_at"], True)
    account, viewer, academy = value["card_account"], value["viewer"], value["academy"]
    require(account["user_id"] == viewer["user_id"] and account["academy_id"] == academy["academy_id"], "viewer account mismatch")
    opened = instant(account["created_at"], True)
    require(opened <= snap, "account opens after snapshot")
    wishes = value["viewer_wishes"]
    own_ids = [item["wish"]["wish_id"] for item in wishes]
    require(len(own_ids) == len(set(own_ids)), "duplicate viewer wish")
    representatives = [i for i, item in enumerate(wishes) if item["wish"]["is_representative"]]
    require(len(representatives) <= 1 and (not representatives or representatives == [0]), "invalid representative ordering")
    if value["viewer_wishes_truncated"]:
        require(len(wishes) == 100, "truncated viewer array must be full")

    def wish_check(item, acct, own):
        wish, summary = item["wish"], item["savings_summary"]
        require(wish["account_id"] == acct["account_id"] and wish["academy_id"] == acct["academy_id"], "wish account mismatch")
        require(wish["saved_amount"] <= wish["target_amount"], "wish exceeds target")
        created = instant(wish["created_at"], True)
        require(created <= snap, "wish created after snapshot")
        for field in ("start_date", "target_date"):
            if wish[field] is not None:
                calendar_date(wish[field])
        if wish["start_date"] is not None and wish["target_date"] is not None:
            require(wish["start_date"] <= wish["target_date"], "wish date range")
        if wish["status"] in ("COMPLETED", "ABANDONED"):
            require(wish["closed_at"] is not None and created <= instant(wish["closed_at"], True) <= snap, "terminal wish time")
        else:
            require(wish["closed_at"] is None, "active wish closure")
        if own:
            amount = wish["abandonment_amount"]
            if wish["status"] == "ABANDONED":
                require(amount is not None and amount <= wish["target_amount"] and wish["saved_amount"] == 0, "abandonment amount")
            else:
                require(amount is None, "non-abandoned history amount")
            if wish["is_representative"]:
                require(wish["status"] in ("IN_PROGRESS", "AMOUNT_REACHED"), "terminal representative")
        require((summary["transaction_count"] == 0) == (summary["last_transaction_at"] is None), "lifetime transaction time")
        if summary["last_transaction_at"] is not None:
            require(instant(summary["last_transaction_at"], True) <= snap, "future lifetime transaction")

    for item in wishes:
        wish_check(item, account, True)
    candidates = value["candidates"]
    candidate_ids = []
    for item in candidates:
        acct, owner, card = item["card_account"], item["owner"], item["shared_card"]
        require(acct["user_id"] == owner["user_id"] != viewer["user_id"], "candidate owner mismatch")
        require(acct["academy_id"] == academy["academy_id"], "candidate academy mismatch")
        require(instant(acct["created_at"], True) <= snap, "future candidate account")
        require(card["account_id"] == acct["account_id"] and card["wish_id"] == item["wish"]["wish_id"], "card identity mismatch")
        require((card["kind"] == "COMPLETION") == (item["wish"]["status"] == "COMPLETED"), "card lifecycle mismatch")
        require(instant(card["updated_at"], True) <= snap, "future card")
        wish_check(item, acct, False)
        candidate_ids.append(card["feed_id"])
    require(len(candidate_ids) == len(set(candidate_ids)), "duplicate candidate")
    if value["candidates_truncated"]:
        require(len(candidates) == 100, "truncated candidate array must be full")
    selection = value["candidate_selection"]
    require(selection["quotas"] == QUOTAS and selection["group_order"] == list(GROUPS), "selection policy mismatch")
    require([item["feed_id"] for item in selection["provenance"]] == candidate_ids, "selection provenance mismatch")
    groups = [item["group"] for item in selection["provenance"]]
    require(groups == sorted(groups, key=GROUPS.index), "selection group ordering")
    require(selection["selected_counts"] == {group: groups.count(group) for group in GROUPS}, "selection counts mismatch")
    require(instant(selection["recent_completed_end_at_exclusive"], True) == snap and instant(selection["recent_completed_start_at"], True) == snap - 30 * 86400 * 1000000000, "completion interval mismatch")
    evidence = value["interest_evidence"]
    categories = evidence["usable_viewer_category_ids"]
    require(categories == sorted(set(categories)), "interest categories ordering")
    metadata = ("source", "taxonomy_version", "classifier_version", "classified_at")
    if evidence["status"] == "absent":
        require(all(evidence[key] is None for key in metadata) and not categories, "absent interest metadata")
    else:
        require(all(evidence[key] is not None for key in metadata), "missing interest metadata")
        age = snap - instant(evidence["classified_at"], True)
        require(age >= 0, "future interest metadata")
        require((evidence["status"] == "stale") == (age > 30 * 86400 * 1000000000), "interest staleness mismatch")
        require(bool(categories) == (evidence["status"] == "used"), "interest status mismatch")
    require(evidence["status"] == "used" or selection["selected_counts"]["interest"] == 0, "interest cards without usable context")
    savings = value["viewer_period_savings"]
    require(savings["scope"] == {"account_id": account["account_id"], "user_id": viewer["user_id"], "academy_id": academy["academy_id"]}, "period scope mismatch")
    period = savings["period"]
    first, end = calendar_date(period["start_date"]), calendar_date(period["end_date_exclusive"])
    days = (end - first).days
    require(1 <= days <= 366 and len(savings["daily"]) == days, "daily bucket count")
    if period["input"] == "default_current_month":
        current = (EPOCH + timedelta(seconds=snap // 1000000000)).astimezone(KST).date()
        month_start = current.replace(day=1)
        next_month = (month_start.replace(day=28) + timedelta(days=4)).replace(day=1)
        require(first == month_start and end == next_month, "default calendar month mismatch")
    start_ns, end_ns = midnight(first), midnight(end)
    require(instant(period["start_at"], True) == start_ns and instant(period["end_at_exclusive"], True) == end_ns, "period timezone mismatch")
    coverage(savings["coverage"], start_ns, end_ns, opened, snap)
    totals = flattened(savings["totals"])
    sums = {key: 0 for key in totals}
    for i, bucket in enumerate(savings["daily"]):
        day = first + timedelta(days=i)
        require(bucket["date"] == day.isoformat(), "daily date sequence")
        coverage(bucket["coverage"], midnight(day), midnight(day + timedelta(days=1)), opened, snap)
        values = flattened(bucket["totals"])
        require(bucket["has_activity"] == has_activity(bucket["totals"]), "daily activity mismatch")
        if bucket["coverage"]["status"] == "unobserved":
            require(not any(values.values()), "unobserved day contains activity")
        for key, number in values.items():
            sums[key] += number
    require(totals == sums, "daily sums differ from period totals")
    require(savings["has_activity"] == has_activity(savings["totals"]), "period activity mismatch")


def self_test():
    cases = strict_loads((FIXTURES / "cases.json").read_bytes())
    count = 0
    for kind, records in cases.items():
        for record in records:
            accepted = False
            try:
                raw = (FIXTURES / record["file"]).read_bytes()
                value = strict_loads(raw)
                if kind == "snapshots":
                    validate_snapshot(value)
                elif kind == "requests":
                    validate_request_document(raw, "2026-09-01T15:00:00Z")
                else:
                    validate_ack(value, record["handoff_id"])
                accepted = True
            except (InvalidContract, KeyError, TypeError):
                pass
            require(accepted == record["valid"], "unexpected fixture result: " + record["file"])
            count += 1
    for raw in ('{"a":1,"a":2}', '{} {}', '{"n":NaN}'):
        try:
            strict_loads(raw)
        except InvalidContract:
            count += 1
        else:
            raise InvalidContract("invalid JSON accepted")
    try:
        validate_request_document(b" " * (MAX_BODY + 1))
    except InvalidContract:
        count += 1
    else:
        raise InvalidContract("oversized request accepted")
    print(f"PASS: {count} shared contract and strict-JSON cases")


def serve(port, credential):
    require(bool(credential), "fixture receiver credential is required")
    accepted = {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            try:
                require(self.path == "/receiver", "unknown route")
                require(self.headers.get_all("Authorization") == ["Bearer " + credential], "authentication required")
                require(self.headers.get_content_type() == "application/json", "JSON required")
                require(not self.headers.get("Transfer-Encoding"), "fixture requires Content-Length")
                size = int(self.headers.get("Content-Length", "0"))
                require(0 < size <= 4 * 1024 * 1024, "invalid fixture payload size")
                self.connection.settimeout(5)
                value = strict_loads(self.rfile.read(size))
                validate_snapshot(value)
                require(self.headers.get_all("Idempotency-Key") == [value["handoff_id"]], "idempotency key mismatch")
                accepted.setdefault(value["handoff_id"], value)
                body = json.dumps({"schema_version": 3, "handoff_id": value["handoff_id"], "accepted": True}).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (InvalidContract, ValueError, OSError, KeyError, TypeError):
                self.send_error(400, "Invalid receiver request")

    server = HTTPServer(("127.0.0.1", port), Handler)
    print(f"Local fixture receiver listening on 127.0.0.1:{server.server_port}", flush=True)
    server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--self-test", action="store_true")
    group.add_argument("--validate", type=Path)
    group.add_argument("--serve", type=int, metavar="PORT")
    parser.add_argument("--credential", default="local-fixture-only")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.validate:
        validate_snapshot(strict_loads(args.validate.read_bytes()))
        print("PASS: valid recommendation snapshot v3")
    else:
        serve(args.serve, args.credential)


if __name__ == "__main__":
    main()
