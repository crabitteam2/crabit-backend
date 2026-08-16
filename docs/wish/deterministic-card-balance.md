# Deterministic card balance sync

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

Outside `e2e`, neither the scripted adapter nor its mutation control exists. The production-safe
provider returns failure until a real provider adapter is configured; it never fabricates a balance.

The public bodyless refresh endpoint always uses `USER_REQUESTED`. `PRE_DEPOSIT` is exposed only as
an internal application seam that returns `DepositBalanceProof` after the lookup transaction has
committed. A failed pre-deposit lookup throws only after its failed observation is committed, so the
caller never enters the Wish deposit transaction. `AUTO_DAILY` visits active account UUIDs in stable
order and isolates each account failure so later accounts are still attempted. The cron expression
and zone are operational configuration, not a user-visible execution-time guarantee.
