# Backend Staging·Stable Demo 배포

이 디렉터리는 Docker Hub의 immutable backend image를 Google Cloud의 두 non-production 환경으로 전달하는 절차를 설명한다. 저장소 변경이나 로컬 검증은 Google Cloud resource 생성, GitHub environment 설정, Vercel 연결, 실제 배포 성공을 증명하지 않는다. 외부 상태는 각 공급자의 authoritative read-back으로 별도 확인한다.

## 고정 토폴로지

| 환경 | Git lane | VM / profile | persistent state |
|---|---|---|---|
| Staging | `develop` digest를 수동 배포 | Seoul `e2-medium` GCE / `e2e` | 전용 100 GB `pd-balanced` data disk |
| Stable Demo | 보호된 `main`에서 발행한 같은 digest를 자동 전달 | Seoul `e2-medium` GCE / `demo` | 전용 100 GB `pd-balanced` data disk |

두 VM은 custom `crabit-nonprod` VPC의 Seoul subnet에 있고 각각 30 GB boot disk, reserved IPv4, service account, operation lock, runtime file, Compose project, PostgreSQL database, snapshot policy를 가진다. Caddy의 TCP 80/443만 public이다. TCP 22는 IAP TCP forwarding과 OS Login으로만 접근하고 8080/5432에는 public firewall rule과 host publication이 없다.

이미지는 `crabitteam2/crabit-backend@sha256:<digest>`만 허용한다. GitHub OIDC가 environment별 deployer service account로 교환되며 Google service-account JSON key나 SSH private-key secret은 사용하지 않는다. deployment와 reset은 exact 100 GB data disk의 READY snapshot을 먼저 만들고 read-back한 뒤에만 IAP를 통해 실행한다.

기존 환경은 greenfield다. 삭제된 이전 hosting provider에 연결하거나 historical database를 import하지 않는다. Staging은 repository의 deterministic E2E fixture를, Stable Demo는 기존 Demo fixture와 serialized reset path를 사용한다.

## 문서 지도

- [Google Cloud provisioning and host bootstrap](host-bootstrap.md)
- [Secrets and GitHub environments](secrets.md)
- [Publication and deployment](publication-and-deploy.md)
- [Rollback and recovery](rollback-and-recovery.md)
- [Stable Demo reset](stable-demo-reset.md)
- [Frontend and Vercel handoff](frontend-handoff.md)

실제 project ID, billing account, IP, host key, credential, token, provider receipt는 저장소에 기록하지 않는다.
