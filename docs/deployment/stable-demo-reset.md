# Stable Demo reset

Reset은 `main` ref의 `Reset Stable Demo` workflow를 `RESET_STABLE_DEMO` 확인 문자열과 함께 수동 실행하고 `stable-demo` reviewer 승인을 받는다.

1. WIF 인증 뒤 exact Stable Demo VM과 그 VM의 public IPv4/host, 100 GB data disk와 single-writer attachment를 read-back한다. reserved address resource는 provisioning operator 검증에 남겨 peer-environment address read 권한을 deployer에 주지 않는다.
2. unique operation ID로 data-disk snapshot을 만들고 READY 상태를 확인한다.
3. pinned host-key와 IAP/OS Login transport로 runtime file과 snapshot proof를 전달한다.
4. remote operation lock을 획득하고 current release state와 running backend/recap이 같은 immutable pair인지 확인한다.
5. backend를 중지하고 같은 digest의 one-shot `demo-reset` service를 실행한다. 이 transaction은 한 Asia/Seoul cutoff에서 직전 완료 주·월을 고정하고 Owner의 직전 완료 월에 deterministic `WISH_DEPOSIT` 3건과 matching Wish effect를 넣는다. deposit은 직전 완료 주간 밖에 있어 월간은 3-deposit eligible, 주간은 zero activity다. 이 단계의 secret-free `CRABIT_DEMO_FIXTURE_RESET_COMPLETED` receipt는 외부 완료 marker가 아니다.
6. serving backend가 중지되고 remote operation lock이 유지된 상태에서 credential-free `demo-reset` container로 기존 `RecapRegenerationCommand`를 주간·월간 각 한 번 실행한다. request UUID는 account/kind/period target에 deterministic하게 묶이며 같은 key는 같은 logical reservation을 재사용한다. 두 reservation 중 하나라도 실패하면 backend를 시작하지 않는다.
7. 두 reservation 뒤에만 같은 backend/Caddy를 시작하고 동일 recap pair, internal health와 exact HTTPS readiness를 검증한다. one-shot reset과 reservation process에는 recap credential을 전달하지 않는다.
8. runtime env의 Owner token을 server-side에서만 사용해 receipt의 exact 주간·월간과 query 없는 두 default를 bounded polling한다. 네 read가 모두 같은 period/generation을 선택하고 `SUCCEEDED`, positive generation version, schema version 1, `recap-1`, non-null generatedAt/result를 반환한 뒤에만 `CRABIT_DEMO_RESET_COMPLETED`를 출력한다.

fixture insert가 transaction 안에서 실패하면 reset 이전 상태 전체가 rollback된다. transaction commit 뒤 reservation, Python, persistence, read-back, 인증, timeout이 실패하면 fixture commit까지 rollback되었다고 간주하면 안 된다. script는 final marker를 만들지 않고 serving backend를 중지한다. snapshot, database의 fixture/reservation/result, image identity를 read-back하고 새 operator/controller 결정을 내린다. ambiguous 결과를 blind retry하지 않는다. HTTP reset endpoint는 존재하지 않는다.

실제 Stable Demo 실행 전에는 backend PR 66 input-parity behavior 또는 exact equivalent와 호환되는 Python
receiver가 먼저 merge·배포되어야 한다. 이 문서와 local verification은 workflow dispatch, deploy, reset,
generation, merge, release, Core production 사용을 승인하지 않는다.
