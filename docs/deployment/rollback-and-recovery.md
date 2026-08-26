# Rollback and recovery

Rollback과 restore는 Google Cloud 안에서만 수행한다. 삭제된 이전 hosting provider로 연결하거나 Vercel을 되돌리지 않는다. failed/ambiguous snapshot, disk attach, deploy, reset, restore, Vercel update는 먼저 read-back하고 fresh write로 맹목 재시도하지 않는다.

## Previous-digest rollback

1. environment operation lock을 잡고 current와 retained previous immutable digest를 read-back한다.
2. database migration compatibility를 명시 검토한다.
3. exact attached data disk의 fresh snapshot을 만들고 READY/source/size/label/ID를 확인한다.
4. serving backend를 중지하고 retained digest를 `rollback.sh <digest> <profile> I_VERIFIED_MIGRATION_COMPATIBILITY`로 배포한다.
5. running image와 exact aggregate HTTPS UP을 확인한 뒤에만 rollback 완료로 기록한다.

Application rollback은 database bytes를 자동으로 되돌리지 않는다. forward-only migration과 호환되지 않으면 snapshot restore 결정을 별도로 내려야 한다.

## Disk restore

Restore는 operation lock 아래 backend와 PostgreSQL을 중지한 뒤 진행한다. selected READY snapshot의 source, location, size와 label을 확인해 replacement 100 GB `pd-balanced` disk를 만든다. 기존 disk와 replacement disk가 동시에 writable attachment가 되지 않게 한 뒤 한 disk만 `crabit-data`로 attach/mount한다. 이후 selected retained digest를 시작하고 disk identity, PostgreSQL/Flyway, fixture, persistence, reset eligibility, running image와 HTTPS readiness를 모두 다시 확인한다.

각 환경의 single-writer invariant는 항상 유지한다. second VM, second writable disk, parallel PostgreSQL writer가 관측되면 즉시 중단한다. repository에는 destructive restore executor를 자동화하지 않는다. live disk detach/delete는 별도 exact controller-bound provider action과 authoritative read-back이 필요하다.
