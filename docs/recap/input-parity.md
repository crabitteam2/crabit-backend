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
The real Java integration builds PostgreSQL snapshots, transmits frozen bytes with
RecapPythonClient, reserves/claims/succeeds through the transactional coordinator,
reads stored request/view/metrics back, and retrieves both kinds through the owner
query service. It also checks foreign-owner denial and exclusion of internal
metrics from public results. Persisted requests/views and owner responses are
exported beside the transport fixtures. Browser verification remains a separate
integration-gate requirement.
Public `api/openapi.yaml` and original algorithm source are unchanged.
