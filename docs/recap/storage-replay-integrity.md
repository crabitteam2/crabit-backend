# 리캡 저장·재생성 무결성

GitHub backend #50의 저장 보완이다. 공개 OpenAPI 및 Python `schema_version=1`, `algorithm_version=recap-1` 계약 bytes는 유지한다. 원천 원장·방문·대표 위시의 알고리즘 선택과 집계 규칙은 별도 #56 범위다.

## 예약과 준비

정기 실행은 계정, 종류, Asia/Seoul 반개구간 기간, schema/algorithm에서 결정한 `scheduled:` 예약 키를 사용한다. 계정 잠금과 DB unique index로 중복 호출이 기존 예약을 재사용한다. 입력 digest에는 `snapshot_at`을 계속 포함하며 예약 identity와 혼용하지 않는다.

예약 시 실제 `generation_version`을 배정하고 `PREPARATION/PENDING` 행을 저장한다. 이 단계에서는 `input_digest`와 `request_json`이 함께 null이다. 공개 조회는 버전이 있는 `GENERATING`이다. 준비 worker가 별도 트랜잭션에서 claim한 뒤 repeatable-read snapshot을 만들며, 성공한 claim만 null 입력을 한 번 동결하여 `GENERATION/PENDING`으로 전환한다. Python worker는 준비 행을 claim할 수 없다. 준비 실패는 실제 버전을 가진 `FAILED`이며 `generatedAt/result`는 null이다.

준비와 Python 호출은 각자 최대 3회이며 attempt counter도 분리한다. 실행 lease는 2분, 재시도 대기는 1분·2분이다. 준비의 DB/트랜잭션 실패는 재시도하며 결정적인 입력·직렬화 오류는 재시도하지 않는다. 아직 동결되지 않은 준비 재시도만 snapshot을 다시 만들 수 있다. Python 재시도와 프로세스 재시작은 저장한 동일 request/digest/generation ID를 사용한다. 오래된 attempt의 완료·실패는 무시한다.

최초 예약 자체가 DB 전체 장애로 저장되지 않았다면 실패 이력을 저장했다고 간주하지 않는다. 실패한 정기 batch는 같은 프로세스의 다음 poll에서 재예약한다. 그 사이 프로세스가 종료되었거나 장기 누락된 기간은 운영자가 아래 명령으로 복구한다. scheduler가 실행되지 않은 과거 기간 전체를 자동 추정하거나 소급 생성하지 않는다.

기존 V16 행은 원래 generation/input/result/시각/current를 보존한 `GENERATION`으로 이관된다. 해당 기간을 처음 정기 예약할 때 key 없는 기존 행 중 version, id 오름차순의 첫 행에 정기 key만 연결한다. 과거 실행 주체를 추정한 기록이 아니라 명시적인 legacy adoption 정책이다. 기존 행이 실패 또는 미충족이어도 같은 정기 identity를 재사용한다.

## 결과와 조회

주간 활동 0은 정상 `SUCCEEDED`, 월간 유효 입금 3건 미만은 동결 입력이 있는 `NOT_ELIGIBLE`이다. 미예약 `NOT_GENERATED`와 준비/생성 `FAILED`를 구분한다. 완료된 새 버전이 없으면 기존 current 결과를 계속 조회한다.

완료 시 계정→논리 기간 순으로 잠그고, 더 높은 완료 version이 current이면 늦은 구버전을 승격하지 않는다. 새 성공 또는 확정 월간 미충족만 이전 current를 대체한다. 이전 current 해제를 flush한 다음 새 current를 설정하여 즉시 검사되는 부분 unique index를 지킨다. 모든 버전의 원문 결과는 보존한다. 같은 완료 claim 재전달은 저장값이 같으면 무변경이며 다른 결과는 conflict로 거절한다.

V18 trigger는 identity, 이미 연결한 예약 키, 동결한 input/request 및 완료한 view/metrics/generatedAt의 교체와 완료 상태 재개를 차단한다. 애플리케이션의 단계·attempt fencing과 DB 불변성 제약은 서로 다른 방어선이다.

Python HTTP 성공 응답은 strict JSON parser로 중복 key, trailing body, null/scalar/array, 알 수 없는 root 필드를 거절한다. 모든 identity 및 정확한 period를 frozen request와 대조한다. 숫자를 BigDecimal로 읽어 큰 소수의 정수 반올림과 작은 지수의 0 underflow를 차단한다. 주간·월간 view의 기존 필수 필드, 타입, 숫자 범위, nullable, 기간과 백분위 상태 관계를 검사한다. 100% 초과 달성률, 정상 0 활동, 동점 순위 null은 기존 계약대로 유지한다. internal_metrics는 열린 객체로 유지하며 새로운 필수 QA key를 요구하지 않는다. upstream story는 wish_id/type_title이며 owner/shared-card 식별자는 현재 권한을 검사하는 조회에서만 보강한다.

조회의 실제 트랜잭션 시작·종료 실패도 recap 전용 advice에서 기존 `503 RECAP_QUERY_UNAVAILABLE`, retryable=true, no-store로 변환한다. DB 오류 원문, 입력 snapshot, metrics와 credential은 공개하지 않는다.

## 새 입력 재생성 명령

DB 운영 권한이 있는 로컬/운영 process에서만 아래 별도 entry point를 사용한다. 이 PR 검증에서는 폐기 가능한 로컬 DB에서만 실행한다.

```sh
./scripts/recap/regenerate.sh build/libs/crabit-backend-0.0.1-SNAPSHOT.jar \
  00000000-0000-4000-8000-000000000003 WEEKLY 2026-08-24 \
  00000000-0000-4000-8000-000000009002
```

월간은 `MONTHLY 2026-08` 형식이다. 실제 jar 파일명은 build/libs의 boot jar를 지정한다. 계정 UUID, 종류, 완료된 기간, caller UUID key 네 인수가 모두 필요하다. 다른/중복 옵션, 현재·미래 기간, 비정규 UUID와 0001–9999 밖 연도는 DB 연결 전에 거절한다. DB 연결은 기존 Spring datasource 환경 설정을 사용하며 값을 출력하지 않는다.

명령은 `PropertiesLauncher`로 `RecapRegenerationCommand`를 직접 시작한다. 일반 웹 애플리케이션을 component-scan하지 않는 독립 non-web context이므로 demo/e2e profile이나 reset 설정을 물려받아도 fixture initializer, scheduler, Python worker를 만들지 않는다. 정상 웹 서버에는 실행용 ApplicationRunner나 HTTP 쓰기 route가 추가되지 않는다. 명령은 예약 ID/version만 출력하고 context를 종료한다. 후속 준비/생성은 정상 worker가 수행한다.

같은 key와 같은 대상·기간은 기존 예약을 반환한다. 같은 key를 다른 대상에 쓰면 거절한다. 새 key는 새 버전을 예약하고 새 snapshot을 만든다. 따라서 늦게 들어온 원장·방문 또는 변경된 대표/목표를 반영하려면 새 key를 사용한다. 자동 retry는 동일 frozen 입력이며, 새 key 재생성과 다르다. 최대 retry가 끝난 행을 명령으로 수정·재개하는 기능은 제공하지 않는다. 기존 버전을 보존하고 새 입력이 필요한 경우 새 key로 예약한다.

## Migration 순서와 검증

공유/배포 DB에는 PR62의 **V17을 먼저 포함·적용한 release에서 V18을 적용**해야 한다. V18을 먼저 배포하고 나중에 V17을 적용하는 순서는 지원하지 않는다. 기존 V1–V17 SQL을 수정하거나 Flyway out-of-order를 켜지 않는다. 현재 develop의 V16→V18 독립 테스트는 구현 검증이며 이 release 순서 조건을 해제하지 않는다.

검증은 실제 PostgreSQL의 동시 정기/명시 예약, 다른 계정 간 key 충돌, legacy adoption, 준비 실패·동결·lease fencing, DB 재조회/재시작, 최신 current 순서, 결과 conflict와 이전 성공 보존을 포함한다. 실제 HTTP 서버에서 malformed 응답을 보내고 변경 없는 Python presenter fixture의 주간 0 활동·월간 결과를 수용하는지 확인한다. 전체 Gradle/OpenAPI 검증과 bootJar, Docker image 및 실제 backend↔Python runtime 경로를 별도로 확인한다.

원본 PR62 V17과의 합성 upgrade 검증은 SQL을 별도 임시 파일로 읽어 실행한다. 이 파일은 제품 migration에 복제하지 않는다.

```sh
CRABIT_RECAP_COMPATIBILITY_V17=/private/tmp/V17__historical_balance_progress.sql \
  ./gradlew test --tests '*RecapStorageMigrationIT' --console=plain
```

이 환경 변수가 없는 일반 실행에서는 선택적 원본 V17 합성 검증 한 건이 skip된다. V16 독립 upgrade, 기존 행 보존, 제약 및 모든 제품 테스트는 계속 실행된다. 합성 검증에 사용한 원본은 PR62 b5e0c90495a5b372bfbc122b7a4db870ea13965f, SQL SHA-256 `f7457c91dd1323755063efa3930b14a5a74fabd07c7f2219a1a8ca1b04484dc7`이다.
