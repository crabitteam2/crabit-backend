# Publication and deployment

## Branch matrix

1. PR은 `Backend CI`의 focused tests, 전체 suite, image/runtime/workflow 검증을 통과한다.
2. `develop` 또는 `main`의 exact commit은 provenance index 없는 single-platform `sha-<commit12>` image 하나만 Docker Hub에 발행한다. BuildKit metadata로 테스트한 local manifest digest와 config digest를 고정한다. 동일 tag가 이미 있으면 그 tag를 한 번만 pull하고, 받아온 digest와 그 manifest의 config digest가 두 local digest와 exact 일치할 때만 채택한다. OCI revision label만으로 동일성을 주장하지 않으며, 다른 config·layer identity나 multi-platform index를 가진 tag는 덮어쓰지 않고 실패한다. 새 tag도 push가 반환한 digest를 immutable reference로 다시 읽고 같은 identity임을 확인한다. tag pull의 실패가 명시적인 `manifest unknown`이 아니면 absence로 간주하지 않고 push 없이 실패한다.
3. `develop` 결과는 Staging candidate다. 자동 배포 trigger는 없다.
4. `Deploy Staging`은 `workflow_dispatch`로 registry digest를 받고, image OCI revision이 현재 `origin/develop`에 귀속됨을 확인한 뒤 Staging에 배포한다.
5. `main` publication run은 위 identity 검증에서 확정한 job output digest를 직접 Stable Demo job에 전달한다. 이후 mutable tag를 다시 resolve하거나 floating selector를 쓰지 않는다.

## Host deployment read-back

배포 script는 host-wide operation lock을 잡고 exact digest를 pull한다. PostgreSQL을 먼저 healthy로 만들고 backend를 시작해 Flyway와 JPA validation, DB-backed readiness가 끝날 때까지 기다린다. 이후 다음을 모두 확인한다.

- running container `.Config.Image`가 선택한 repository digest와 같다.
- local RepoDigests에 같은 digest가 있다.
- public HTTPS readiness가 HTTP 200과 `{"status":"UP"}` 하나만 반환한다.
- 성공한 새 digest만 current state로 원자적으로 기록하고 이전 current digest를 rollback 후보로 보존한다.

로컬 성공은 공급자 성공이 아니다. GitHub workflow run, Docker Hub tag/digest, Vultr running digest, firewall, volume, HTTPS 응답을 각각 read-back한다.
