# Private recap runtime and immutable release pair

Staging과 Stable Demo의 recap 생성은 공개 API가 아니라 backend와 Python 서비스 사이의
`POST /internal/v1/recap-generations` 호출이다. 배포는 기존 HTTP 계약과 recap 계산을 바꾸지 않고
두 이미지를 하나의 release pair로 취급한다.

## Runtime topology

- `backend`는 `edge`, `database`, internal `recap` network에 참여한다.
- `recap`은 internal `recap` network에만 참여하고 host port를 publish하지 않는다.
- `postgres`는 internal `database` network에만 참여한다. recap 컨테이너에는 database 환경 변수를
  전달하지 않는다.
- recap은 UID/GID `10001`, read-only root filesystem, `no-new-privileges`, writable `/tmp` tmpfs로
  실행한다. container-local TCP probe가 production worker의 8081 accept 상태를 확인한다. 별도 HTTP
  health route를 추가하지 않는다.
- backend는 recap health가 확인된 뒤 시작하며 URL은
  `http://recap:8081/internal/v1/recap-generations`로 고정한다.

`CRABIT_RECAP_GENERATION_CREDENTIAL`은 environment별 secret이다. Compose는 같은 nonempty 값을
backend의 `CRABIT_RECAP_GENERATION_CREDENTIAL`과 Python의 `CRABIT_RECAP_TOKEN`에만 주입한다.
로그, image layer, health output, repository에는 값을 남기지 않는다. Compose 밖의 `e2e`와 `demo`
application 설정은 URL·credential·enable flag가 모두 없으면 generation을 기본 비활성화한다.

## Immutable pair lifecycle

배포 입력은 다음 두 fully qualified digest다.

- `crabitteam2/crabit-backend@sha256:<digest>`
- `crabitteam2/crabit-data@sha256:<digest>`

remote deploy script는 두 digest를 pull하고 recap health, backend health, 각 container `.Config.Image`,
두 local `RepoDigests`, public aggregate HTTPS readiness를 확인한다. 모두 성공한 뒤에만 mode 0600
`current-release.env`를 원자적으로 갱신하며 직전 pair는 `previous-release.env`에 보존한다. 새 pair가
기동된 뒤 검증에 실패하면 verified current pair를 digest 그대로 다시 기동한다. 첫 배포가 실패해
복구할 current pair가 없으면 partial serving container를 중지한다.

Rollback은 backend와 recap digest를 함께 선택한다.

```shell
./scripts/deployment/rollback.sh \
  'sha256:<backend-digest>' 'sha256:<recap-digest>' demo \
  I_VERIFIED_MIGRATION_COMPATIBILITY
```

Stable Demo reset도 current release pair가 실제 running pair와 일치할 때만 진행한다. one-shot reset에는
recap credential을 전달하지 않으며 serving backend를 다시 시작한 뒤 동일 pair와 HTTPS readiness를
재검증한다.

## Repository verification

실제 runtime 검증은 두 repository의 현재 source로 만든 local image를 함께 사용한다.

```shell
backend_image="crabit-backend:sha-$(git rev-parse --short=12 HEAD)"
recap_revision="$(git -C ../crabit-data rev-parse HEAD)"
recap_image="crabit-recap:sha-${recap_revision:0:12}"

docker build --build-arg VCS_REF="$(git rev-parse HEAD)" --tag "${backend_image}" .
docker build --build-arg VCS_REF="${recap_revision}" --tag "${recap_image}" ../crabit-data
./scripts/deployment/verify-runtime.sh "${backend_image}" "${recap_image}"
```

검증은 private networking과 security options, exact secret/URL wiring, real backend-to-Python generation,
database 저장, owner lookup, backend restart persistence, recap failure 중 stored read 격리, repeat-safe
Compose activation, Demo reset을 확인한다. 이 결과는 repository/runtime evidence이며 registry publication,
GitHub secret 설정, VM rollout, live reachability, merge, release, Core production activation을 증명하지 않는다.

Core production은 이 topology에서 계속 비활성화되며 별도 승인 없이는 연결하지 않는다.
