# 기간별 잔액과 당시 대표 위시 달성률

`GET /internal/v1/academies/{academyId}/students/{studentId}/card-balance-accounts/{accountId}/historical-balances`는 PostgreSQL에 수집한 금융 이력을 서울 달력의 일·주·월별로 재현한다. wire 계약은 `api/openapi.yaml`의 `getHistoricalBalances`, `HistoricalBalancesResponse`, `x-historical-balance-policy`가 기준이다.

## 조회와 접근 자격

`crabit.recommendation.handoff.enabled=true`일 때만 등록한다. 기존 recommendation trigger credential을 정확히 하나의 `Authorization: Bearer …` 헤더로 받는다. 학생·receiver 자격증명으로 이 조회를 호출할 수 없다. 정확한 GET에서 필터가 인증 검증 전에 `Cache-Control: no-store`를 설정하며 controller도 machine 인증 attribute를 다시 확인한다.

현재 열린 계정, 정확한 소유 학생·학원, 학생·학원 존재와 `left_at IS NULL`인 학원 소속을 한 repeatable-read snapshot 안에서 확인한다. 누락·불일치·종료·소속 이탈은 모두 `404 CARD_BALANCE_ACCOUNT_NOT_FOUND`다. 이 머신 인증에는 학생 viewer identity가 없으므로 방문·차단 정책이나 owner/viewer 403을 새로 적용하지 않는다. 재생 token도 현재 접근 자격을 대신하지 않는다.

필수 query는 `fromDate`, `toDateExclusive`, `granularity`다. `asOfRevision`만 선택이다. 날짜는 엄격한 `YYYY-MM-DD`, 단위는 대소문자를 구분하는 `DAY`, `WEEK`, `MONTH`다. 중복·알 수 없는 이름·빈 값·literal null·본문·필수 누락은 400이다. 범위는 양수이며 최대 366 달력 일이고 WEEK의 양 끝은 월요일, MONTH의 양 끝은 1일이어야 한다. 완전히 미래인 버킷을 포함할 수 없다. 현재 열린 자연 일·주·달 끝까지의 요청은 `PROVISIONAL`로 반환한다.

## 시각과 알 수 없는 값

완료된 9월 1일 버킷은 서울 9월 2일 00:00인 `2026-09-01T15:00:00Z` **미만**에 적용된 체크포인트를 사용한다. 응답은 이 실제 instant와 `evaluationBoundary=BEFORE`를 반환한다. 현재 버킷은 `evaluationHorizon` **이하**를 포함하며 `THROUGH`다. 개설 및 수집 시작에도 동일한 경계를 적용한다. 같은 적용 시각은 계정 revision으로 정렬한다.

`NONE`, `PARTIAL`, `FULL`은 버킷의 경과한 부분에 실제 수집된 범위이며 외부 관측 성공 여부와 독립적이다. 개설 전은 `ACCOUNT_NOT_OPEN`, 개설 후 수집 전은 `PRE_COLLECTION_UNKNOWN`이다. 해당 금융 값과 provenance는 null이고 0이나 현재 대표/목표로 대신하지 않는다. 수집된 내부 배정액은 외부 성공 관측이 없어도 알려진 0 또는 양수일 수 있다.

성공 외부 관측은 원래 관측 ID와 시각을 유지하여 이월한다. 최신 조회가 실패해도 이전 성공 관측을 삭제하지 않는다. 실제 첫 성공 관측 0과 성공 관측 부재는 구분한다. 관측의 값이 같거나 실패했다는 이유로 가짜 원장 이벤트를 만들지 않는다. 성공 관측의 이월은 그 사이 모든 순간의 외부 실시간 잔액을 증명하지 않는다.

알려진 잔액에서는 다음을 checked integer arithmetic으로 계산한다.

- `ledgerAvailableBalance = lastSuccessfulObservedCardBalance - activeWishAllocation`
- `displayAvailableBalance = max(ledgerAvailableBalance, 0)`
- `unresolvedShortage = max(-ledgerAvailableBalance, 0)`

당시 선택된 위시의 목표와 배정액으로 `IN_PROGRESS=min(99,floor(100*amount/target))`, `AMOUNT_REACHED=100`을 계산한다. 곱셈은 `BigInteger`를 사용한다. 종결 후에는 원자적으로 기록된 대체 대표 또는 `KNOWN_NONE`을 반환한다. 완료·포기·삭제를 선택된 0%로 바꾸지 않는다. `AMOUNT_REACHED`는 명시적 종결 전까지 활성이다.

## V17의 기록 방식

`historical_balance_checkpoint`는 계정별 연속 revision, 적용 시각, baseline 여부, 원장 watermark, 최신/마지막 성공 관측 ID, 당시 대표 상태·목표·금액 및 활성 위시의 금융 사실을 저장한다. `active_wishes`는 Wish ID, 상태, 목표, 배정액만 포함한다. purpose·visibility·photo·profile을 복제하지 않는다.

계정·위시·대표 선택·관측·원장·위시 원장 효과의 deferred constraint trigger가 transaction의 최종 상태를 읽어 한 체크포인트로 합친다. 중간 flush나 이체 한쪽 상태는 별도 체크포인트가 되지 않는다. 기록 시 계정 잠금을 얻은 뒤 PostgreSQL `clock_timestamp()`를 사용한다. 명령 진입 때 캡처한 시각이나 `occurred_at`/`observed_at`으로 과거 적용 시각을 만들지 않는다. 같은 금융 projection, 관측 identity, 원장 watermark는 새 revision을 만들지 않는다. SQL seed와 JPA INSERT에도 동일한 규칙이 적용된다.

`historical_ledger_application`은 V17 이후 원장 행의 실제 적용 시각과 원장 순번을 연결한다. 계정 잠금 뒤 원장 순번을 배정하므로 동일 계정의 대기 writer가 순서를 뒤집지 않는다. 전역 sequence 공백은 정상이며 계정 revision과 구분한다. DB 시계가 뒤로 이동하면 적용 시각을 직전 체크포인트와 원장 적용 시각 이상으로 보정하여 순서를 유지한다.

기존 계정의 첫 baseline은 **마이그레이션이 실제 실행되는 시점**이다. 기존 원장의 최대 application order와 현재 금융 상태를 baseline에 묶되, 옛 원장에 wall-clock 적용 시각을 소급하여 붙이지 않는다. 새 계정은 생성 transaction이 끝난 상태부터 baseline을 갖는다. GET, 목록, 대표 조회는 baseline을 생성하지 않는다.

체크포인트·적용 메타데이터·원장 이벤트·원장 위시 효과·잔액 관측은 DB에서 UPDATE/DELETE를 거부한다. 이미 기록된 원장에 나중 위시 효과를 추가할 수도 없다. 정정은 새 원장 사실과 새 revision으로 추가해야 한다. 계정 identity/개설 시각도 불변이다. 이 강화는 원장 소스 전체에 적용된다. E2E reset은 관련 테이블을 함께 TRUNCATE하며 V17 이력 테이블이 없는 V16 상태도 지원한다. 테스트 fixture도 원장 이벤트와 그 위시 효과를 한 transaction에서 함께 커밋해야 하며, 이력이 참조하는 일회용 계정·위시는 개별 DELETE 대신 전체 fixture reset으로 정리한다.

## 조회 검증과 재생

첫 snapshot SQL 안의 `clock_timestamp()`로 읽기 시각을 얻고, 같은 repeatable-read transaction에서 현재 자격·체크포인트·원장·관측을 읽는다. checkpoint의 활성 위시 합계·대표 membership·목표/금액·관측 링크를 검증하고 baseline 금액에 적용 순서대로 원장 효과를 반영하여 각 후속 체크포인트와 대조한다. 부족한 baseline, revision 공백, 잘못된 관측, 사라진 이체 효과, 모순되는 합계·선택·금액은 `500 HISTORICAL_BALANCE_INTEGRITY_ERROR`다. 데이터베이스 조회 장애는 `503 HISTORICAL_BALANCE_QUERY_UNAVAILABLE`이며 이 신규 오류만 retryable이다. 연결 획득과 트랜잭션 시작·완료(commit/rollback) 과정의 인프라 오류도 조회 컨트롤러 경계에서 같은 503으로 변환한다. 두 오류 모두 SQL/provider 세부 정보 없이 빈 `fieldErrors`/`details`를 반환한다.

`dataRevision`은 canonical JSON의 base64url을 담은 `h1.` 버전 토큰이다. schemaVersion, 정확한 학원·학생·계정 identity, baseline 및 선택 checkpoint 경계, 원래 `evaluationHorizon`을 포함한다. padding·비정규 JSON·중복 필드·잘못된 값·존재하지 않는 revision·교차 계정·미래 horizon은 거부한다. 토큰의 모든 경계는 실제 불변 행과 비교한다. DB 순번은 정밀도 손실 없는 십진 문자열이다.

`asOfRevision` 재생은 입력 경계와 horizon을 고정한다. 후속 거래, 대표·목표 수정, 정정 및 자정 경과 뒤에도 금융 값·기간 상태·coverage·provenance·dataRevision·inputDigest를 유지한다. `readSnapshotAt`은 이번 실제 조회 시각이므로 달라진다. GET은 토큰을 저장하거나 외부 잔액 조회를 실행하지 않는다.

조회는 baseline부터 선택 revision까지의 모든 체크포인트와 해당 원장 구간을 읽어 검증한다. 따라서 응답 범위의 366일 제한과 별개로 비용은 계정의 누적 이력에 비례한다. 현재 구현에는 서버 측 이력 압축이나 결과 캐시가 없다.

canonical 금융 입력은 승인된 예제 `x-canonical-financial-input`의 구조를 그대로 사용한다. baseline은 별도 필드이고 `checkpoints`에는 baseline 이후 사용한 체크포인트만 revision 순서로 둔다. 원장 효과는 application order, 각 효과의 위시는 Wish ID 순서다. 관측은 lookup version(null 우선), 동률 ID 순서다. 객체 키를 재귀 정렬하고 명시적 null 및 배열 순서를 보존한 공백 없는 JSON UTF-8 바이트에 SHA-256을 적용한다. 현재 접근 자격, 읽기 시각, trace/transport 정보는 포함하지 않는다. `HistoricalCanonicalFixtureTest`는 production 입력 생성기로 승인 예제의 digest를 재현한다.

## 검증 연결과 호환성

- `HistoricalPeriodsTest`: 제외 종료, 현재 horizon, 일·주·월 정렬, 366일 상한.
- `HistoricalBalanceProjectionTest`: 당시 대표/목표, UNKNOWN/0/실패·부족액, 큰 금액, 종결 부재, token 정규성.
- `HistoricalBalanceRecordingIT`: V16→V17 실제 baseline, 원자적 기록, no-op, 실패/동일 관측, 0원 종결, rollback, 불변 제약, 잠금 대기와 시계 역행 보정, 마이그레이션 후 기존 성공 관측을 잇는 실제 refresh.
- `HistoricalBalanceApiIT`: 활성화된 실제 머신 HTTP 응답의 canonical schema, 현재 자격, 재생, 금융 변경, 무결성 오류.
- `HistoricalCanonicalFixtureTest`: 승인된 여섯 canonical 금융 입력 digest.

기존 공개 API, money bounds 및 인증 규칙은 유지한다. issue 56의 리캡은 생성 시점 대표/목표 선택과 기존 알고리즘을 계속 사용하며 이 역사 조회를 호출하지 않는다. `api/recap-generation-v1.yaml`, 프런트 계약/타입/화면, 알림·Core 연계·배포는 이 변경에 포함하지 않는다.
