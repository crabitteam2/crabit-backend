# Backend Staging·Stable Demo 배포

이 디렉터리는 백엔드 저장소 산출물을 Docker Hub와 두 개의 독립 Vultr non-production 환경으로 전달하는 절차를 설명한다. 저장소 코드가 존재하거나 로컬 검증이 통과한 사실은 Docker Hub 발행, Vultr 배포, Vercel 연결, end-to-end Demo 준비 완료를 뜻하지 않는다. 각 외부 상태는 해당 공급자의 authoritative read-back이 필요하다.

## 고정 토폴로지

| 환경 | Git lane | 배포 trigger | Spring profile | DB |
|---|---|---|---|---|
| Staging | `develop`에서 발행된 commit 이미지 | `Deploy Staging` 수동 실행, digest 입력 | `e2e` | Staging 전용 persistent PostgreSQL 16 volume |
| Stable Demo | 보호된 `main`의 방금 발행된 이미지 | main publication job이 같은 digest를 자동 전달 | `demo` | Stable Demo 전용 persistent PostgreSQL 16 volume |

`develop` push는 immutable 후보를 발행할 뿐 Staging을 자동 배포하지 않는다. `main` push만 그 run이 발행·read-back한 digest를 Stable Demo에 자동 배포한다. 모든 이미지는 `sha-<commit12>` tag와 registry digest로 식별하며 `latest`를 만들거나 사용하지 않는다.

각 Vultr host에는 Caddy, Spring Boot backend 하나, PostgreSQL 16 하나만 둔다. Caddy만 host port 80/443을 공개하고 backend 8080과 PostgreSQL은 private Compose network에 남긴다. `/actuator/health/liveness`와 `/actuator/health/readiness`만 management surface에서 proxy한다.

## 문서 지도

- [Host bootstrap](host-bootstrap.md)
- [Secrets and environment protection](secrets.md)
- [Publication and deployment](publication-and-deploy.md)
- [Rollback and recovery](rollback-and-recovery.md)
- [Stable Demo reset](stable-demo-reset.md)
- [Frontend and Vercel handoff](frontend-handoff.md)

실제 IP, SSH host key, credential, token, 공급자 receipt는 저장소에 기록하지 않는다.
