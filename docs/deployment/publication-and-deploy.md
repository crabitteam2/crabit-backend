# Publication and deployment

## Branch matrix

1. PR은 focused tests, 전체 suite, image/runtime/workflow/Google Cloud plan 검증을 통과한다.
2. `develop` 또는 `main`의 exact backend commit은 single-platform `sha-<commit12>` image 하나를 Docker Hub에 발행하고 registry digest와 locally tested config digest를 exact read-back한다. mutable selector나 multi-platform index를 배포하지 않는다.
3. `develop` backend digest와 `crabit-data` main에서 별도로 발행·read-back한 recap digest는 `Deploy Staging` 수동 workflow의 두 입력이다. 각 image OCI revision이 matching source lane의 ancestor일 때만 진행한다.
4. `main` publication run은 backend job output digest와 Stable Demo environment의 reviewed `CRABIT_RECAP_IMAGE_DIGEST`를 하나의 release pair로 deployment job에 전달한다.
   Stable Demo feed opt-in이 활성일 때는 이 exact 이미지 쌍과 Environment classifier version의 호환성 및 인증된 Python HTTP를 추가로 검증한다. runtime 렌더링이 전용 credential과 설정을 검증한 뒤 snapshot으로 진행한다. 기본 opt-in은 `false`이다.
5. deployment job은 GitHub OIDC를 environment deployer service account로 교환하고 `verify-environment.sh`로 exact own-environment VM, 그 VM의 public IPv4/host, OS Login, data disk와 single writer를 read-back한다. reserved address resource 자체는 provisioning operator의 `verify-project.sh`가 확인하므로 deployer에 project-wide address read를 주지 않는다.
6. exact operation ID로 data-disk snapshot을 만들고 status `READY`, source disk, 100 GB size, labels, provider ID를 확인한다. 실패한 create는 같은 이름을 authoritative read-back해 exact match인 경우만 채택한다.
7. runtime file, snapshot proof, archive를 pinned host-key/IAP transport로 전송한다. 전송 전에 selected environment, project, zone, instance, data disk, snapshot proof, HostKeyAlias, active deployer, provider가 read-back한 destination VM identity가 모두 일치해야 한다. remote deployment는 operation lock 안에서 snapshot proof를 다시 검증한다.

## Runtime read-back

Remote script는 exact backend/recap digest를 pull하고 PostgreSQL, private recap service, backend, Caddy를 순서대로 시작한 뒤 다음을 확인한다.

- 두 running container `.Config.Image`와 local RepoDigests가 selected immutable pair와 같다.
- recap은 internal network에서만 health가 확인되고 host port, database credential, public ingress를 갖지 않는다.
- public HTTPS readiness가 성공 HTTP와 정확히 `{"status":"UP"}`만 반환한다.
- 성공한 pair만 current state에 원자적으로 기록하고 이전 verified pair를 rollback candidate로 보존한다. 새 pair 검증 실패 시 이전 pair를 자동 복구한다.
- backend 8080, recap 8081, PostgreSQL 5432는 host port를 publish하지 않는다.
- feed opt-in 시 동일 data digest의 내부 feed 서비스를 backend보다 먼저 시작하고 최종 HTTPS readiness 이후 health 및 이미지 일치를 재확인한다. feed에는 공개 포트와 DB credential을 전달하지 않는다.

수동 Stable Demo reset은 `main` 및 명시적 confirmation 조건을 유지하고 배포와 같은 operation lock/concurrency와 snapshot 증명을 사용한다. 다음 배포용 feed 변수 대신 현재 release의 enabled/classifier/두 이미지를 고정한다. 활성 release의 전용 credential과 선택 이미지 호환성은 backend 중지 전에 검증한다. reset 이후 같은 feed 이미지를 재조정하고 검증 실패 시 backend를 중지한다. legacy 비활성 release와 rollback의 previous-release 설정 보존은 유지된다. reset mock 테스트는 실제 reset 실행 권한이나 데이터 초기화 승인을 대신하지 않는다.

Workflow success도 현재 Google Cloud/Vercel delivery success의 충분조건이 아니다. workflow run, Docker Hub digest, snapshot, VM running digest, disk attachment, firewall, HTTPS response를 별도 read-back한다.
