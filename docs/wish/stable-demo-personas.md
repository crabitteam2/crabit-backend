# Stable Demo 합성 Persona 인증

백엔드의 `demo` 프로필은 서버에만 주입된 opaque Bearer credential을 여섯 개의 합성 Persona로 해석한다. credential 값과 Persona-to-token 매핑은 API 응답, OpenAPI 문서, 로그, 저장소에 노출하지 않는다.

## 필수 서버 환경 변수

`demo` 프로필을 시작하기 전에 다음 여섯 이름에 서로 다른 값을 제공해야 한다.

- `CRABIT_DEMO_TOKEN_OWNER`
- `CRABIT_DEMO_TOKEN_FRIEND`
- `CRABIT_DEMO_TOKEN_NONFRIEND`
- `CRABIT_DEMO_TOKEN_BLOCKED`
- `CRABIT_DEMO_TOKEN_OTHER_ACADEMY`
- `CRABIT_DEMO_TOKEN_STAFF`

값이 없거나 공백이거나 HTTP Authorization header에 사용할 수 없는 형식이면 시작에 실패한다. 값이 서로 중복되거나 저장소에 고정된 E2E seed token과 같아도 시작에 실패한다. 오류에는 문제가 발생한 환경 변수 이름만 포함하며 credential 값은 포함하지 않는다.

## 프로필 경계

- `demo`와 `e2e`는 동시에 활성화할 수 없다. 함께 지정하면 애플리케이션이 요청을 받기 전에 시작에 실패한다.
- `demo`에서는 시스템 UTC clock과 일반 non-E2E Card Balance provider를 사용한다.
- E2E의 고정 clock, deterministic balance script, fixture reset, `/e2e/**` 제어 route는 `demo`에 로드되지 않는다.
- `prod`에는 Demo token registry와 Demo Bearer filter가 로드되지 않는다.
- 공개 계약의 인증 이름은 profile-neutral `SyntheticBearer`이며 HTTP wire shape은 `Authorization: Bearer <opaque-token>`으로 유지된다.

이 문서는 백엔드 `demo` 프로필의 인증·격리 경계만 설명한다. secret 설치 절차, 배포·인프라 구성, Persona 선택 UI, fixture 초기화·reset은 이 변경 범위에 포함되지 않는다.
