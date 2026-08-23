# Stable Demo reset

reset은 `main` ref의 `Reset Stable Demo` workflow를 `RESET_STABLE_DEMO` 확인 문자열과 함께 수동 실행한다. GitHub `stable-demo` environment의 reviewer가 승인해야 한다.

1. host operation lock을 획득하고 current state와 실제 running backend가 같은 immutable digest인지 확인한다.
2. serving backend를 중지해 product traffic을 unavailable로 만든다.
3. 같은 digest의 `demo-reset` one-shot service를 `demo`, `web-application-type=none`, `crabit.demo.lifecycle=reset`으로 실행한다.
4. PostgreSQL transaction advisory lock 안에서 전체 synthetic graph를 복구한다. commit 뒤 completion marker가 있을 때만 성공이다.
5. 같은 digest의 backend를 다시 시작하고 내부 health와 public HTTPS readiness를 확인한다.

실패하면 DB transaction은 rollback되고 serving backend는 중지된 채 남는다. success marker를 만들거나 자동 retry·restart하지 않는다. 운영자가 DB와 image identity를 조사한 뒤 새 수동 결정을 내려야 한다. 반복 reset과 concurrent reset은 동일한 결정적 최종 상태를 남긴다. HTTP reset endpoint는 존재하지 않는다.
