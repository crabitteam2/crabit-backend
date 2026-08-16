# Deterministic card balance sync

`CardBalanceProvider` is the only boundary that obtains an external card balance. A sync captures
one instant from the injected `Clock`, calls the provider without a database account lock, and then
records either success or `BALANCE_SYNC_FAILED` through the existing transactional observation
service. Successful nonzero changes therefore retain the existing exact-delta ledger proof, while
zero changes and failures create no balance-change event.

`observedAt` identifies when the serialized lookup attempt began. The sync service allows different
accounts to refresh independently but admits only one provider lookup and persistence operation at a
time for each account. It also allocates strictly increasing observation times when the configured
clock is fixed or moves backward. Existing observed-time ordering therefore produces one linear
successful history with exact deltas.

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
