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
- [목표 API 계약](api/openapi.yaml): 공개 인터페이스의 버전 관리 원본

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

`prod` 실행 전에는 `CRABIT_DATABASE_URL`, `CRABIT_DATABASE_USERNAME`,
`CRABIT_DATABASE_PASSWORD`를 설정한다. Flyway가 migration을 적용한 뒤 Hibernate가 schema를
검증한다.

```shell
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

고정 persona와 Seed 인증이 필요한 로컬 E2E 실행은 PostgreSQL datasource를 준비한 뒤 `e2e`
프로필을 사용한다. datasource, reset 정책, persona 정보는
[PostgreSQL migration and E2E Seed](docs/wish/postgres-e2e-seed.md)를 따른다.

## 검증

```shell
git diff --check
./gradlew test --console=plain
```

기능별 집중 검증 명령은 각 가이드에 기록한다. 정적 계약과 현재 Springdoc 표면의 구분은 테스트에서도
유지하며, 문서 변경이 `api/openapi.yaml`이나 런타임 동작을 대신하지 않는다.
