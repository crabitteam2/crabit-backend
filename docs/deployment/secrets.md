# Secrets and GitHub environments

GitHub environments `dockerhub`, `staging`, `stable-demo`를 분리하고 deployment environment에 required reviewer를 둔다. generated Google credential file `gha-creds-*.json`은 Git에서 무시한다. Google service-account JSON key와 SSH private key를 생성하거나 저장하지 않는다.

## Docker Hub

- Secrets: `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`
- Public repository: `crabitteam2/crabit-backend`

## Staging·Stable Demo 공통

- Variables: `GCP_PROJECT_ID`, `GCP_PROJECT_NUMBER`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`
- Variables: `CRABIT_PUBLIC_HOST`, `CRABIT_DATABASE_NAME`, `CRABIT_DATABASE_USERNAME`, `CRABIT_COMPOSE_PROJECT`
- Stable Demo variable: reviewed `CRABIT_RECAP_IMAGE_DIGEST`; Staging은 workflow input으로 exact digest를 받는다.
- Secrets: `CRABIT_DATABASE_PASSWORD`, `CRABIT_RECAP_GENERATION_CREDENTIAL`, `CRABIT_GCP_KNOWN_HOSTS`
- Feed opt-in variables: `CRABIT_FEED_RANKING_ENABLED` (기본 `false`), `CRABIT_FEED_CLASSIFIER_VERSION` (`wish-category-v1@sha256:<64자리 digest>`)
- Feed secret: `CRABIT_FEED_RANKING_CREDENTIAL`. 활성 배포에서는 필수이며 recap 및 다른 환경의 feed credential과 재사용하지 않는다. 영문/숫자 및 `._:/@+-`만 허용한다.

Stable Demo 배포에는 `stable-demo` Environment 값을 별도로 등록한다. reset은 현재 릴리스의 enabled/version/image를 보존하므로 다음 배포용 변수를 읽지 않고 현재 전용 feed secret만 전달한다. 활성인 current/previous release를 reset/복구할 수 있도록 secret을 유지해야 한다. 기존 secret 이력을 release 파일에 저장하거나 자동 복원하지 않는다. credential은 mode 0600 runtime file을 통해 backend와 내부 feed에만 전달하고 출력·문서·release metadata에는 기록하지 않는다.

`CRABIT_RECAP_GENERATION_CREDENTIAL`은 recap 전용 opaque visible-ASCII token이다. environment별로 분리하고 database, persona, balance provider token과 재사용하지 않는다. workflow는 mode 0600 runtime file에 한 번만 추가하며 Compose가 같은 값을 backend와 private recap service에만 주입한다. credential file fallback, repository default, image layer, health response, debug trace를 만들지 않는다.

WIF provider는 repository 이름만 신뢰하지 않는다. immutable GitHub repository ID `1332782656`과 GitHub environment claim `staging` 또는 `stable-demo`를 함께 요구한다. 각 GitHub environment의 `GCP_SERVICE_ACCOUNT`는 matching deployer만 가리키며, environment principal은 다른 deployer를 impersonate할 수 없다. `CRABIT_GCP_KNOWN_HOSTS`는 `gce-crabit-staging` 또는 `gce-crabit-stable-demo` HostKeyAlias의 independently verified exact line이다. workflow는 `ssh-keyscan`을 실행하거나 strict checking을 낮추지 않는다.

`CRABIT_PUBLIC_HOST`는 matching reserved IPv4에서 파생한 `api-staging.<dashed-ip>.sslip.io` 또는 `api-demo.<dashed-ip>.sslip.io`와 exact 일치해야 한다.

## Stable Demo 전용

- Variable: `CRABIT_DEMO_BALANCE_PROVIDER_URL`
- Secrets: `CRABIT_DEMO_BALANCE_PROVIDER_TOKEN`과 여섯 `CRABIT_DEMO_TOKEN_*`

여섯 persona token은 완전하고 서로 달라야 하며 E2E namespace와 재사용하지 않는다. token, database password, provider credential은 browser, cookie, response, source map, build output, log, repository, evidence에 기록하지 않는다.
# Wish photo opt-in

`CRABIT_WISH_PHOTO_ENABLED` is an environment-scoped non-secret GitHub variable,
defaulting to `false`. The renderer derives all photo project, bucket and runtime
identity values; do not create JSON/HMAC keys or store tokens/signed URLs. See
[Wish photo runtime](wish-photo-runtime.md) before any separately authorized activation.
