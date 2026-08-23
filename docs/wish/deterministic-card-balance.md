# Deterministic card balance sync

> Documentation map: Start at the [backend README](../../README.md) for authority boundaries and links to the other backend guides. This repository-owned guide describes balance lookup and persistence behavior; the target HTTP contract remains [api/openapi.yaml](../../api/openapi.yaml).

`CardBalanceProvider` is the only boundary that obtains an external card balance. A sync captures
one instant from the injected `Clock`, calls the provider without a database account lock, and then
records either success or `BALANCE_SYNC_FAILED` through the existing transactional observation
service. Successful nonzero changes therefore retain the existing exact-delta ledger proof, while
zero changes and failures create no balance-change event.

`observedAt` records the actual lookup-attempt time from the configured clock and is never rewritten
to manufacture ordering. Provider calls remain outside the account lock. When a result completes,
the database account lock assigns `accountLookupVersion`; successful ancestry and latest-balance
reads follow that version. The persisted history therefore stays linear across restarts, independent
application instances, fixed clocks, and provider completions that finish out of attempt order.
Mismatch adjustment links created by balance sync follow the same database persistence order. Their
ledger and observation timestamps retain the actual attempt time, while a resolved episode's boundary
is clamped to its latest event time so an inverted completion cannot roll back a valid observation.

The `e2e` profile supplies `DeterministicCardBalanceAdapter` and its `CardBalanceScriptControl`.
Tests enqueue success or failure responses per account; each account consumes its own queue in
insertion order. An empty or exhausted queue is an explicit failure. The E2E clock is fixed by
`crabit.e2e.clock.instant`, so the same seed data, clock value, and scripts reproduce the same
semantic observation and ledger sequence.

The `demo` profile instead selects `DemoHttpCardBalanceProvider`. It sends `POST` to the exact
`/api/provider/balance-lookups` HTTPS endpoint configured by
`CRABIT_DEMO_BALANCE_PROVIDER_URL`, using the machine credential from
`CRABIT_DEMO_BALANCE_PROVIDER_TOKEN`. The endpoint must be absolute, have no user-info, query, or
fragment, and must not end in a trailing slash. The credential must contain at least 32
visible-ASCII non-whitespace characters. Startup fails closed and names only the invalid environment
variable; it never includes the configured value.

This outbound adapter is bound to Demo Scenario Console revision
`e9752ca81c7ec18c00e5f1407a86859b51e016e3`. Each logical lookup generates one UUID and sends only
`lookupId` and `accountId`. A successful response is HTTP 200 JSON with the same `lookupId`, outcome
`SUCCESS`, a non-negative JavaScript-safe integer `balanceKrw`, and boolean `replayed`. An explicit
provider failure has outcome `FAILURE`, the same `lookupId`, boolean `replayed`, and no balance.
Unknown, missing, or malformed fields, an invalid media type, and responses larger than 16 KiB fail
closed without exposing the body.

The adapter uses a two-second connection timeout and a three-second per-attempt request timeout. It
retries once after 100 ms only for transport or timeout failures and HTTP 429, 502, 503, or 504. The
retry reuses the exact `lookupId` and `accountId`; a later logical lookup receives a new `lookupId`.
Redirects are never followed, so the bearer credential is sent only to the configured endpoint.
HTTP 400, 401, every other non-200 response, and invalid HTTP 200 envelopes are not retried. Every
exhausted or non-retryable failure maps to the existing provider `Failure`; status codes, credentials,
and provider response bodies never cross the `CardBalanceProvider` boundary.

Outside `demo` and `e2e`, neither the HTTP nor scripted adapter exists. The production-safe
`UnavailableCardBalanceProvider` returns failure; it never fabricates a balance. The demo profile has
no `CardBalanceScriptControl` and no `/e2e/**` route, while e2e keeps only the deterministic adapter.

The public bodyless refresh endpoint always uses `USER_REQUESTED`. `PRE_DEPOSIT` is exposed only as
an internal application seam that returns `DepositBalanceProof` after the lookup transaction has
committed. A failed pre-deposit lookup throws only after its failed observation is committed, so the
caller never enters the Wish deposit transaction. `AUTO_DAILY` visits active account UUIDs in stable
order and isolates each account failure so later accounts are still attempted. The cron expression
and zone are operational configuration, not a user-visible execution-time guarantee.
