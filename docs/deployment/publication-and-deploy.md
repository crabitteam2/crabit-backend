# Publication and deployment

## Branch matrix

1. PR은 `Backend CI`의 focused tests, 전체 suite, image/runtime/workflow 검증을 통과한다.
2. `develop` 또는 `main`의 exact commit은 `sha-<commit12>` 하나만 Docker Hub에 발행한다. 동일 tag가 이미 있으면 OCI revision이 exact commit과 일치할 때만 기존 digest를 채택한다. 불일치 tag는 덮어쓰지 않고 실패한다.
3. `develop` 결과는 Staging candidate다. 자동 배포 trigger는 없다.
4. `Deploy Staging`은 `workflow_dispatch`로 registry digest를 받고, image OCI revision이 현재 `origin/develop`에 귀속됨을 확인한 뒤 Staging에 배포한다.
5. `main` publication run은 그 job output digest를 직접 Stable Demo job에 전달한다. tag를 다시 resolve하거나 floating selector를 쓰지 않는다.

## Host deployment read-back

배포 script는 host-wide operation lock을 잡고 exact digest를 pull한다. PostgreSQL을 먼저 healthy로 만들고 backend를 시작해 Flyway와 JPA validation, DB-backed readiness가 끝날 때까지 기다린다. 이후 다음을 모두 확인한다.

- running container `.Config.Image`가 선택한 repository digest와 같다.
- local RepoDigests에 같은 digest가 있다.
- public HTTPS readiness가 HTTP 200과 `{"status":"UP"}` 하나만 반환한다.
- 성공한 새 digest만 current state로 원자적으로 기록하고 이전 current digest를 rollback 후보로 보존한다.

로컬 성공은 공급자 성공이 아니다. GitHub workflow run, Docker Hub tag/digest, Vultr running digest, firewall, volume, HTTPS 응답을 각각 read-back한다.
