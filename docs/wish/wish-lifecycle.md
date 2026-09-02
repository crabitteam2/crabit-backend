# Wish lifecycle implementation

> Documentation map: Start at the [backend README](../../README.md) for authority boundaries and links to the other backend guides. This repository-owned guide describes the implemented lifecycle; the target HTTP contract remains [api/openapi.yaml](../../api/openapi.yaml).

## Supported operations

The backend implements the seven Wish lifecycle operations from the current
`api/openapi.yaml` contract:

- create, list, and detail;
- atomic JSON Merge Patch;
- explicit completion and abandonment;
- tombstone deletion.

Every operation is scoped to the authenticated student's active Card Balance Account and
academy. Accounts owned by another student or academy are returned as not found. Deleted
Wishes are excluded from list and detail results.

## State and concurrency

New Wishes start with zero funds in `IN_PROGRESS` and `PRIVATE`, regardless of whether a
card balance is known. Target changes recalculate `IN_PROGRESS` and `AMOUNT_REACHED` from
the current amount. Target dates never trigger an automatic transition.

Patch, completion, and abandonment compare `expectedVersion` while the Wish is locked.
Deletion compares the integer `If-Match` value. A stale version returns
`VERSION_CONFLICT`; a decoded negative version returns `INVALID_VERSION`.

Merge Patch distinguishes omitted date fields from explicit `startDate: null` and
`targetDate: null`. `startDate` is the owner's optional planned start date; it is separate
from the immutable system-generated `createdAt`. The only invalid date pair is two
non-null values where `startDate > targetDate`. Create and patch reject that pair with
`422 INVALID_DATE_RANGE` and ordered `startDate`/`targetDate` field errors. Patch resolves
both supplied and retained values first, then replaces the pair atomically. All supplied
fields are applied in one transaction and increment the JPA version once; any invariant
failure rolls back every supplied change.

## Time and terminal effects

Application commands use one instant from the injected UTC `Clock`. Creation sets
`createdAt` and `updatedAt` to that instant. Mutations update `updatedAt`; explicit
completion also sets `completedAt`, and `actualDurationSeconds` is derived from the
persisted creation and completion instants.

Successful abandonment stores that same command instant internally as `abandonedAt`.
Every public Wish includes required nullable `closedAt`: it equals `completedAt` for
`COMPLETED`, the internal abandonment instant for `ABANDONED`, and null for active states.
Deletion time and target date never define lifecycle closure.

Completion is allowed only from `AMOUNT_REACHED`. Abandonment is allowed from either
active state and permanently makes the Wish private. Tombstone deletion preserves the
lifecycle state and original purpose snapshot while hiding later reads.

Completion, abandonment, and deletion lock the account and Wish in one transaction. Any
remaining Wish funds are returned and recorded by exactly one reason-specific Ledger Event.
Zero-value abandonment or deletion creates no synthetic event. Existing balance-mismatch
and Shared Card synchronization hooks remain in the same transaction.

## Idempotency

Create, completion, abandonment, and deletion require `Idempotency-Key`. The namespace is
per student and permanent. The locked student row stores immutable response records in a
JSONB object keyed by the supplied key; each record contains the operation, target,
canonical request fingerprint, original status, response Wish, and Ledger Event ID.
Stored and replayed response snapshots include `closedAt` and nullable `startDate`.
Historical snapshots that predate `startDate` deserialize that member as null. Create
fingerprints are versioned to include `startDate`; the legacy fingerprint fallback is
accepted only when the new request also has `startDate: null`, so adding a planned start
date cannot replay an older response.

An identical replay returns the original status and body with
`Idempotency-Replayed: true`. Reusing a key for a different operation, target, or canonical
request returns `IDEMPOTENCY_KEY_REUSED`. Locking the student namespace before lookup and
write makes concurrent identical commands perform one mutation.

## Recommendation handoff

Recommendation snapshot schema version 2 carries required nullable `start_date` for both
the viewer Wish and every candidate Wish. The value comes from `wish.start_date`; it is
not inferred from `created_at`. Snapshot construction fails closed if persisted data ever
contains an inverted non-null date pair. The public Shared Card response contract is
unchanged.

## Verification

The focused verification command is:

```text
./gradlew test --tests '*WishLifecycleTest' --tests '*WishCommandApiIT' --tests '*WishOwnershipIT' --tests '*WishTerminalTransitionIT' --console=plain
```

These PostgreSQL-backed suites cover lifecycle creation and patch rollback, API pagination
and validation, owner and academy isolation, injected time, optimistic concurrency,
idempotent replay, explicit terminal transitions, tombstone hiding, and Ledger Event
effects. The complete regression command is `./gradlew test --console=plain`.
