# Crabit Backend

Crabit의 위시, 카드 잔액, 원장, 공유 카드 도메인을 제공하는 Java 21 기반 Spring Boot 백엔드다.
이 문서는 저장소 안의 구현·계약·운영 문서를 찾기 위한 시작점이다.

## 문서 권위와 역할

| 위치 | 역할 |
|---|---|
| Riido | 제품 의도, 범위, 인수 조건, 의사결정의 유일한 규범적 원천 |
| 이 저장소 | 백엔드 구현, 버전 관리되는 목표 API 계약, 데이터 무결성 근거, 실행·검증 절차의 원천 |
| 프론트엔드 저장소와 GitHub Wiki | 프론트엔드 운영 문서의 편집 원본과 독자용 게시본. 백엔드 사실은 이 저장소의 계약·구현을 가리킨다. |
| Obsidian `Results/` | Riido, Git, CI, 공급자 증거를 가리키는 비규범적 실행·연구 결과. 계획이나 저장소 문서를 복제하지 않는다. |

## 백엔드 문서 지도

- [위시 데이터 모델과 DB 무결성](docs/wish/data-model-db-integrity.md): 도메인 용어, 관계, 불변 조건, 트랜잭션, JPA·PostgreSQL 제약
- [Deterministic card balance sync](docs/wish/deterministic-card-balance.md): 잔액 조회 경계, 관측 순서, E2E 스크립트, refresh 동작
- [PostgreSQL migration and E2E Seed](docs/wish/postgres-e2e-seed.md): Flyway, 프로필, 고정 persona, PostgreSQL 검증
- [Wish lifecycle implementation](docs/wish/wish-lifecycle.md): 위시 CRUD, 상태 전이, 동시성, 멱등성
- [친구 요청·차단 관리](docs/wish/friend-management.md): 관계 상태, API 사용, 개인정보 경계, 동시성, E2E reset 범위
- [위시 규범 백엔드 E2E 추적표](docs/wish/normative-backend-e2e-traceability.md): Riido 2-42 섹션 5~18 규칙과 자동화 테스트 근거
- [목표 API 계약](api/openapi.yaml): 공개 인터페이스의 버전 관리 원본
- [Staging·Stable Demo 배포](docs/deployment/README.md): 이미지 발행 lane, Google Cloud 런타임, 검증·복구·운영 경계

## API 계약과 현재 구현 문서

`api/openapi.yaml`은 제품이 지향하는 정적 목표·공개 계약이다. 실행 중 Springdoc이 생성하는 아래
문서는 현재 컨트롤러에 구현된 표면만 반영한다. 두 문서는 목적이 다르므로 동일한 operation 목록이나
동일한 byte라고 가정하지 않는다.

- JSON: `http://localhost:8080/v3/api-docs`
- YAML: `http://localhost:8080/v3/api-docs.yaml`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

문서 표면은 기본 설정에서 활성화되고 `prod` 프로필에서는 비활성화된다. 다른 환경에서도
`crabit.documentation.enabled`로 명시적으로 제어할 수 있다.

## 개발 환경과 실행

필수 도구는 JDK 21이다. PostgreSQL 통합 테스트에는 실행 중인 Docker daemon이 필요하다.

전체 테스트는 별도 데이터베이스 설정 없이 H2 기반 단위·slice 테스트와 Testcontainers 기반
PostgreSQL 통합 테스트를 함께 실행한다.

```shell
./gradlew test --console=plain
```

### 프로필별 실행

`e2e`, `demo`, `prod`는 모두 PostgreSQL 접속 정보가 필요하다. 아래 명령의 `<...>` 자리는 로컬
환경이나 서버의 비밀 저장소 값으로 교체하며, 실제 접속 정보와 토큰은 저장소에 커밋하지 않는다.

Staging에서 사용하는 `e2e` 프로필은 고정 persona와 Seed 인증을 제공한다. datasource, reset 정책,
persona와 커밋된 E2E 토큰의 의미는
[PostgreSQL migration and E2E Seed](docs/wish/postgres-e2e-seed.md)를 따른다.

```shell
CRABIT_DATABASE_URL='<PostgreSQL JDBC URL>' \
CRABIT_DATABASE_USERNAME='<PostgreSQL username>' \
CRABIT_DATABASE_PASSWORD='<PostgreSQL password>' \
SPRING_PROFILES_ACTIVE=e2e \
./gradlew bootRun
```

Stable Demo에서 사용하는 `demo` 프로필은 서버에만 보관하는 여섯 persona 토큰과 별도 balance
provider 자격 증명이 필요하다. 여섯 `CRABIT_DEMO_TOKEN_*` 값은 서로 달라야 하며 커밋된 E2E
토큰을 재사용할 수 없다.

```shell
CRABIT_DATABASE_URL='<PostgreSQL JDBC URL>' \
CRABIT_DATABASE_USERNAME='<PostgreSQL username>' \
CRABIT_DATABASE_PASSWORD='<PostgreSQL password>' \
CRABIT_DEMO_TOKEN_OWNER='<opaque token>' \
CRABIT_DEMO_TOKEN_FRIEND='<opaque token>' \
CRABIT_DEMO_TOKEN_NONFRIEND='<opaque token>' \
CRABIT_DEMO_TOKEN_BLOCKED='<opaque token>' \
CRABIT_DEMO_TOKEN_OTHER_ACADEMY='<opaque token>' \
CRABIT_DEMO_TOKEN_STAFF='<opaque token>' \
CRABIT_DEMO_BALANCE_PROVIDER_URL='<HTTPS provider URL>' \
CRABIT_DEMO_BALANCE_PROVIDER_TOKEN='<provider machine token>' \
SPRING_PROFILES_ACTIVE=demo \
./gradlew bootRun
```

`prod`는 데이터베이스 migration 뒤 Hibernate schema 검증을 수행하고 API 문서를 비활성화한다.
현재 저장소의 비운영 배포 topology는 `prod` 공개 도메인이나 자동 배포 lane을 할당하지 않는다.
따라서 이 명령은 운영 배포 증거가 아니라 미배포 설정 템플릿이다.

```shell
CRABIT_DATABASE_URL='<PostgreSQL JDBC URL>' \
CRABIT_DATABASE_USERNAME='<PostgreSQL username>' \
CRABIT_DATABASE_PASSWORD='<PostgreSQL password>' \
SPRING_PROFILES_ACTIVE=prod \
./gradlew bootRun
```

### 브라우저 접근과 공개 HTTPS

브라우저 기반 프론트엔드가 API를 호출할 수 있도록 `e2e`와 `demo`에서만 `/v1/**`에 다음 CORS
경계를 연다.

- 모든 Origin을 `Access-Control-Allow-Origin: *`로 허용하되 credential mode는 허용하지 않는다.
- `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`와 `Authorization`, `Content-Type`,
  `Idempotency-Key`, `If-Match` 요청 헤더만 허용한다.
- 허용된 preflight는 Bearer 인증 전에 끝나지만, 뒤따르는 실제 API 요청은 기존 Bearer 인증과
  student 권한 검사를 그대로 통과해야 한다. 브라우저 CORS는 인증이나 권한을 부여하지 않는다.
- 기본 프로필과 `prod`에는 이 permissive CORS filter가 등록되지 않는다.

배포 런타임의 공개 host 원본은 서버의 `CRABIT_PUBLIC_HOST`다. 저장소 문서에는 실제 IP를 넣지
않고 다음 패턴을 사용한다.

| 환경 | Spring 프로필 | 공개 host 패턴 |
|---|---|---|
| Staging | `e2e` | GCE reserved IPv4 기반 `api-staging.<public-ip>.sslip.io` |
| Stable Demo | `demo` | GCE reserved IPv4 기반 `api-demo.<public-ip>.sslip.io` |
| Production | `prod` | 현재 비운영 topology에서 할당하지 않음 |

`<public-host>`를 위 host로 교체하면 주요 공개 URL은 다음과 같다.

- readiness: `https://<public-host>/actuator/health/readiness`
- 생성 OpenAPI: `https://<public-host>/v3/api-docs`
- Swagger UI: `https://<public-host>/swagger-ui/index.html`

`e2e`와 `demo`는 기존 Caddy 경계가 전달하는 scheme과 host를 Spring framework가 해석한다.
따라서 `X-Forwarded-Proto: https`와 공개 forwarded host가 있는 요청의 생성 OpenAPI server URL은
공개 HTTPS 주소가 되고, Swagger Execute도 내부 HTTP 주소 대신 그 주소를 사용한다. 이 설정은 새
proxy나 port를 열지 않으며 임의의 직접 인터넷 트래픽을 신뢰하도록 허용하지 않는다.

배포 검증은 readiness 응답이 HTTP 성공이고 body가 정확히 `{"status":"UP"}`인 경우에만 성공한다.
DNS, TCP, TLS, timeout, 비성공 HTTP, 비정상 JSON, `DOWN`, 또는 추가 key가 있는 응답은 최대 12번의
읽기 전용 관측 안에서 재시도한다. 각 시도는 3초로 제한하고 실패한 시도 사이에만 2초 기다리므로
최악의 retry budget은 58초다. 재시도 소진 시 image state를 갱신하지 않으며 배포·reset·image
발행·provider 쓰기 자체를 재시도하지 않는다.

host를 설정했다는 사실, 배포가 완료됐다는 사실, 공개 HTTPS가 현재 정상이라는 사실은 서로 다른
상태다. README와 `CRABIT_PUBLIC_HOST`는 설정 의도만 설명한다. 배포 완료는 배포 실행 read-back으로,
현재 live 상태는 해당 HTTPS endpoint의 별도 관측으로 확인해야 한다.

## 검증

```shell
git diff --check
./gradlew test --console=plain
```

기능별 집중 검증 명령은 각 가이드에 기록한다. 정적 계약과 현재 Springdoc 표면의 구분은 테스트에서도
유지하며, 문서 변경이 `api/openapi.yaml`이나 런타임 동작을 대신하지 않는다.

컨테이너와 배포 정의까지 포함한 검증은 다음 순서로 실행한다.

```shell
docker build --build-arg VCS_REF="$(git rev-parse HEAD)" \
  --tag "crabit-backend:sha-$(git rev-parse --short=12 HEAD)" .
./scripts/deployment/verify-image.sh \
  "crabit-backend:sha-$(git rev-parse --short=12 HEAD)" "$(git rev-parse HEAD)"
./scripts/deployment/verify-runtime.sh \
  "crabit-backend:sha-$(git rev-parse --short=12 HEAD)"
./scripts/deployment/verify-workflows.sh
./scripts/deployment/google-cloud/verify-plan.sh
```

행동 수집과 내부 방문/관심/피드 지표: [계약·재전송·보존·프런트엔드 후속 작업](docs/wish/behavior-events.md). 학생 수집 API는 명시적 이벤트만 기록하며 기존 GET 요청을 활동으로 추정하지 않습니다.
