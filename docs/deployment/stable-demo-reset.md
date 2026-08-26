# Stable Demo reset

Reset은 `main` ref의 `Reset Stable Demo` workflow를 `RESET_STABLE_DEMO` 확인 문자열과 함께 수동 실행하고 `stable-demo` reviewer 승인을 받는다.

1. WIF 인증 뒤 exact Stable Demo VM과 그 VM의 public IPv4/host, 100 GB data disk와 single-writer attachment를 read-back한다. reserved address resource는 provisioning operator 검증에 남겨 peer-environment address read 권한을 deployer에 주지 않는다.
2. unique operation ID로 data-disk snapshot을 만들고 READY 상태를 확인한다.
3. pinned host-key와 IAP/OS Login transport로 runtime file과 snapshot proof를 전달한다.
4. remote operation lock을 획득하고 current image state와 running backend가 같은 immutable digest인지 확인한다.
5. backend를 중지하고 같은 digest의 one-shot `demo-reset` service를 실행한다.
6. completion marker를 확인한 뒤 backend/Caddy를 시작하고 internal health와 exact HTTPS readiness를 검증한다.

실패하면 transaction은 rollback되고 backend는 중지된 채 남을 수 있다. completion marker를 만들거나 자동 restart/retry하지 않는다. snapshot, database, image identity를 read-back하고 새 결정을 내린다. HTTP reset endpoint는 존재하지 않는다.
