# Rollback and recovery

자동 rollback과 실패 write의 blind retry는 하지 않는다. 현재 상태가 모호하면 container digest, DB/Flyway 상태, HTTPS readiness를 먼저 관찰한다.

## Application rollback

이전에 성공 read-back한 registry digest와 대상 profile을 준비하고, 그 이미지가 현재 DB migration과 호환됨을 검토한다. 확인 뒤 host에서 다음처럼 명시 실행한다.

```shell
CRABIT_RUNTIME_ENV=/secure/path/runtime.env \
  ./scripts/deployment/rollback.sh \
  sha256:<64-hex> demo I_VERIFIED_MIGRATION_COMPATIBILITY
```

rollback은 application image만 바꾸며 persistent volume을 되돌리지 않는다. 되돌릴 이미지가 이미 적용된 forward-only migration을 읽지 못하면 복구로 간주할 수 없다.

## 실패 분류

- Flyway 실패: backend를 ready로 만들지 말고 migration 오류와 DB backup/repair 선택을 검토한다. 같은 write를 맹목적으로 반복하지 않는다.
- backend unhealthy: liveness/readiness와 running digest를 분리해 확인한다. DB outage 때 liveness는 UP일 수 있고 readiness는 DOWN이어야 한다.
- Caddy/TLS 실패: backend 내부 readiness와 public HTTPS를 각각 확인한다. Caddy 502/503은 application health JSON이 아니다.
- volume 손상·삭제: `down -v`를 실행하지 않는다. 이 non-production topology에는 자동 backup이 없으므로 공급자 snapshot이나 검증된 별도 backup이 없으면 복구 불가능할 수 있다.
