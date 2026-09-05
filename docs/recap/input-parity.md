# Recap input parity

The backend prepares a privacy-reduced immutable request under a repeatable-read
transaction. `snapshot_at` is acquired with PostgreSQL `clock_timestamp()` at the
first statement. Corrections visible in this snapshot apply once to the parent
chain root's identity, kind and business date. Missing parents, cycles, branches,
ambiguous Wish effects and arithmetic overflow fail generation instead of becoming
empty activity. A net-zero cancellation contributes no deposit count. Transfer
pairs use one root with `TRANSFER_IN` and `TRANSFER_OUT` effects; completion,
abandonment and deletion returns retain their distinct transaction kinds.

Each algorithm applies its own period window. The request includes all effective
transactions before the period's exclusive end because representative milestones,
early-return checks and lifetime balances require activity older than 52 weeks.
The habit peer aggregate alone uses exactly `[endExclusive - 52 weeks, endExclusive)`.
Balances are reconstructed at period end; target amounts and explicit representative
selection reflect the snapshot. Fallback uses the earliest nondeleted IN_PROGRESS
Wish, then UUID. Achievement percentages retain values over 100 and use the original
saved / target * 100 arithmetic order. A viewer without PROVIDED age receives empty
comparison cohorts. Closed accounts, other academies, self and non-PROVIDED peer
ages are excluded; representative-less peers still contribute habit activity.

Weekly stories require completion and shared-card update within the week. Existing
shared-card visibility, membership, block and follow rules run before the first
five entries, ordered by completion time then Wish UUID. Each story contains all
eight `core-metrics-v1` metrics computed over the author's entire account in the
month before that story's completion month, using Asia/Seoul dates. January uses
December of the preceding year; a week crossing a month boundary can therefore
contain different author metric windows. Deleted Wish transactions and abandoned
Wish history remain included. Profile-visit actor student IDs map to the author's
account within its academy; author visit counts are outgoing visits. Raw author
transactions and visitor identities are never transmitted.

Metrics match `monthly_recap.compute_core_metrics`: deposit count, net deposits
minus withdrawals, signed net/count average, population deviation of distinct
deposit-date gaps, and `(second-half net - first-half net) / total` pace with a
15-day midpoint. Regularity is null for fewer than two distinct dates; pace is null
for nonpositive net savings. Abandonments use closed ABANDONED Wishes, transfer
count uses outgoing transfers, and visits use outgoing profile events. No thresholds
or classification priority change. The compatible Python receiver must be released
before enabling this writer; frozen legacy requests retain their bytes and identities.

Successful stored views remain immutable. Owner queries recheck story authorization
and preserve the stored summary unless a story is redacted. An empty successful
query remains zero activity; database or transport errors remain failures.

## Verification

`RecapAuthorMetricsTest` compares all eleven committed synthetic original-Python
oracle cases (source data revision recorded in the fixture). Database tests cover
backdated/cross-month correction cancellation, stable root IDs, age provenance,
representative fallback, story visibility-before-limit and August account activity
for September stories, January/December Seoul boundaries, deleted author activity,
outgoing author visits, and the 52-week habit cutoff. Existing coordinator/client/query tests cover frozen requests,
lease and retry behavior, response identity validation and query privacy.

`./scripts/recap/verify-input-parity.sh` reruns actual PostgreSQL snapshot tests and
sends their generated weekly and qualifying monthly requests to a local compatible Python receiver configured by
`CRABIT_RECAP_PARITY_CONFIG`, a local JSON file with `url` and `token`. Missing
prerequisites fail explicitly. The conditional Java integration test is skipped in
ordinary unit runs without that file; the parity script requires it. Generated requests/results live under `build/recap-input-parity/`.
The real Java integration reserves scheduled PREPARATION, claims preparation,
builds PostgreSQL snapshots with the reserved generation ID, completes preparation,
claims generation, transmits frozen bytes with RecapPythonClient, and succeeds
through the transactional coordinator. It verifies scheduling duplicates before
preparation and after success retain the same generation and stored fields. It
reads stored request/view/metrics back, and retrieves both kinds once through the
owner-query service and twice more through fresh service instances. The complete public
responses must remain equal, including period, generation metadata, values and
nulls; all stored generation fields and the account's generation count must remain
unchanged. It also checks foreign-owner denial and exclusion of internal
metrics from public results. Persisted requests/views and owner responses are
exported beside the transport fixtures.

The controller adopted backend base `5e468b2cd21cff20b56c0fde7920cd22baed5d1c`
and data base `ae65675f53d6d1538c744f30ddce2df46de75156`, producing backend
HEAD `1fb57a7db0c0bd136a981d5bae65d3286a2b7e2b` and data HEAD
`a3faf23732ef0d88b11b2708e0876530f40c44a4`. Base integration is complete.
The review rework changes the integration test and this evidence document; it does
not change production behavior or the contract. Its fresh validation is recorded
below and must not be confused with historical pre-integration test counts.

With this scheduled-test rework applied, `./scripts/recap/verify-input-parity.sh`
passed 11 tests in four suites with zero failures, errors or skips, followed by
successful real HTTP identity/view verification. The sequential full backend
suite then passed 628 tests in 123 suites with zero failures, errors or skips in
2m 27s, with `CRABIT_RECAP_PARITY_CONFIG` enabling the real Python integration.
Both runs used backend HEAD `1fb57a7db0c0bd136a981d5bae65d3286a2b7e2b` plus
this test/document rework. Retained local reports are
`/private/tmp/recap-scheduled-parity-xml` and `/private/tmp/recap-scheduled-full-xml`;
logs are `/private/tmp/recap-scheduled-parity.log` and
`/private/tmp/recap-scheduled-full.log`.

Existing-app browser acceptance passed on these integrated production trees:
one Playwright test in 19.6 seconds. It used disposable PostgreSQL, the real Python
receiver, backend and existing frontend, seeded real ledger facts and PREPARATION
reservations, and let the backend job build every frozen request. No handcrafted
recap requests or backend/Python stubs were used. It verified weekly and qualifying
monthly rendering, repeated response equality and unchanged stored inputs,
nullable achievement presentation, story navigation to the author feed, monthly
ineligibility, failure rendering after stopping the receiver, and foreign-owner
404. Changing story visibility to FOLLOWERS removed the unauthorized story and
link while preserving the stored successful views. This run supersedes an earlier
validator failure that attempted a forbidden PRIVATE shared-card fixture update.
The local acceptance script and passing log are
`/private/tmp/crabit-recap-preparation-browser/recap-preparation.spec.mjs` and
`/private/tmp/crabit-recap-preparation-browser/run-final.log`; these are execution
artifacts, not a newly committed browser suite.

Public `api/openapi.yaml` and original algorithm source are unchanged.
