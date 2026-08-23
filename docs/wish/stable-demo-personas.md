# Stable Demo 합성 Persona 인증

백엔드의 `demo` 프로필은 서버에만 주입된 opaque Bearer credential을 여섯 개의 합성 Persona로 해석한다. credential 값과 Persona-to-token 매핑은 API 응답, OpenAPI 문서, 로그, 저장소에 노출하지 않는다.

## 필수 서버 환경 변수

`demo` 프로필을 시작하기 전에 다음 여섯 Persona 이름에 서로 다른 값을 제공해야 한다.

- `CRABIT_DEMO_TOKEN_OWNER`
- `CRABIT_DEMO_TOKEN_FRIEND`
- `CRABIT_DEMO_TOKEN_NONFRIEND`
- `CRABIT_DEMO_TOKEN_BLOCKED`
- `CRABIT_DEMO_TOKEN_OTHER_ACADEMY`
- `CRABIT_DEMO_TOKEN_STAFF`

잔액 provider 연결에는 다음 두 값도 필요하다.

- `CRABIT_DEMO_BALANCE_PROVIDER_URL`
- `CRABIT_DEMO_BALANCE_PROVIDER_TOKEN`

값이 없거나 공백이거나 HTTP Authorization header에 사용할 수 없는 형식이면 시작에 실패한다. 값이 서로 중복되거나 저장소에 고정된 E2E seed token과 같아도 시작에 실패한다. 오류에는 문제가 발생한 환경 변수 이름만 포함하며 credential 값은 포함하지 않는다.

`CRABIT_DEMO_BALANCE_PROVIDER_URL`은 별도로 배포된 Demo Scenario Console의 정확한 HTTPS
`/api/provider/balance-lookups` endpoint다. user-info, query, fragment, trailing slash는 허용하지
않는다. `CRABIT_DEMO_BALANCE_PROVIDER_TOKEN`은 Console의 `DEMO_PROVIDER_TOKEN`과 같은
32자 이상의 visible-ASCII machine credential이어야 하며, 두 값 모두 누락되거나 유효하지
않으면 `demo` 시작이 실패한다. 이 두 설정도 오류·로그·문서 예시에 실제 값을 노출하지 않는다.

## 프로필 경계

- `demo`와 `e2e`는 동시에 활성화할 수 없다. 함께 지정하면 애플리케이션이 요청을 받기 전에 시작에 실패한다.
- `demo`에서는 시스템 UTC clock과 `DemoHttpCardBalanceProvider`만 사용한다. provider는 redirect를
  따르지 않으며 retry에서도 같은 lookup UUID를 재사용한다.
- E2E의 고정 clock, deterministic balance script, fixture reset, `/e2e/**` 제어 route는 `demo`에 로드되지 않는다.
- `prod`에는 Demo token registry와 Demo Bearer filter가 로드되지 않는다.
- 공개 계약의 인증 이름은 profile-neutral `SyntheticBearer`이며 HTTP wire shape은 `Authorization: Bearer <opaque-token>`으로 유지된다.

## Fixture lifecycle

- `crabit.demo.lifecycle=serve`가 기본값이다. 시작할 때 누락된 합성 fixture만 추가하고 기존 Demo 사용자의 변경은 덮어쓰거나 지우지 않는다.
- `crabit.demo.lifecycle=reset`은 `spring.main.web-application-type=none`과 함께 같은 immutable 이미지 digest로만 실행한다. PostgreSQL transaction advisory lock으로 동시 reset을 직렬화하고 전체 합성 그래프를 한 transaction에서 복구한다.
- 성공하면 commit 뒤 `CRABIT_DEMO_RESET_COMPLETED`만 남기고 종료한다. 실패하면 성공 marker 없이 nonzero로 끝나며 기존 DB 상태는 rollback되고 serving backend는 중지 상태로 남는다.
- reset HTTP route는 없다. 관리자는 `Reset Stable Demo` GitHub Actions의 수동 실행만 사용한다.

## 배포 전제

- Demo Scenario Console은 `e9752ca81c7ec18c00e5f1407a86859b51e016e3` revision과 해당
  Supabase migration으로 배포하고, HTTPS provider route를 authoritative read-back으로 확인한다.
- Console의 `DEMO_PROVIDER_TOKEN`과 백엔드의 `CRABIT_DEMO_BALANCE_PROVIDER_TOKEN`에 같은
  machine credential을 secret store로 설치한다. 저장소나 배포 로그에는 값을 남기지 않는다.
- Stable Demo 전용 영속 DB에 Owner Card Balance Account
  `00000000-0000-0000-0000-000000000301`이 존재함을 확인한다. `serve` lifecycle은 누락된
  합성 fixture를 추가하고, 관리자 `reset` lifecycle은 전체 합성 그래프를 복구한다.
- 배포 후 기존 공개 balance-refresh 경로로 secret-safe smoke를 수행하고 생성된 balance
  observation을 read-back한다. repository test는 DNS, TLS, firewall, secret 설치, Console 접근성,
  영속 fixture를 증명하지 않는다.

secret 설치와 운영 절차는 [배포 문서](../deployment/README.md)를 따른다. Persona 선택 UI와
Vercel의 server-only token 주입은 프론트엔드 소유 범위다.
