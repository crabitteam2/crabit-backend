# 내부 추천 데이터 전달 v3

이 변경은 [기간별 저축 집계 #45](https://github.com/crabitteam2/crabit-backend/issues/45)와
[추천 후보 보강 #47](https://github.com/crabitteam2/crabit-backend/issues/47)을 함께 제공한다.
수신 서버는 `schema_version: 3`을 검증하고 아래 확인 응답을 반환해야 한다.
기존처럼 본문 없는 2xx 응답만 반환하는 수신 서버는 더 이상 전달 성공으로 처리되지 않는다.
학생 공개 API와 `api/openapi.yaml`은 변경하지 않는다.

## 요청과 수신 확인

기존 전용 Bearer 인증을 사용하는 `POST /internal/v1/recommendation-handoffs`에 요청한다.
학생 로그인 토큰으로 호출하는 API가 아니다. 성공 응답은 기존과 같은 본문 없는 HTTP 204다.
수신 요청에는 별도의 수신 서버 Bearer 인증과 `Idempotency-Key: <handoff_id>`를 사용한다.

```json
{
  "handoff_id": "00000000-0000-0000-0000-000000009001",
  "card_balance_account_id": "00000000-0000-0000-0000-000000000301",
  "period": {
    "start_date": "2026-08-01",
    "end_date_exclusive": "2026-09-01"
  }
}
```

`period`와 `interest_context`는 선택 필드다. 기존 두 UUID만 보내도 요청이 유효하다.
기간을 생략하면 DB 스냅샷 시각의 한국 달력상 이번 달을 조회한다. 관심 분류를 생략하면
최신·최근 완료 후보로 구성한다. 선택 객체에 `null`을 보내거나 알 수 없는 필드,
중복 JSON 키, 뒤에 붙인 다른 JSON 문서, 쿼리 문자열을 보내면 거절한다.
요청 본문은 읽는 단계부터 262,144바이트로 제한한다.

수신 서버는 HTTP 200, `Content-Type: application/json`과 정확히 다음 형태의 응답을 반환한다.
`handoff_id`는 받은 데이터의 ID와 같아야 하며, 응답 본문 상한은 4,096바이트다.

```json
{
  "schema_version": 3,
  "handoff_id": "00000000-0000-0000-0000-000000009001",
  "accepted": true
}
```

다른 버전·ID, `accepted: false`, 필드 누락·추가, 중복 키, 빈 본문, 201/204 응답은
수신 거절로 처리한다. 연결 제한은 1초, 응답 전체 제한은 5초이며 리다이렉트와 자동 재시도는 없다.
타임아웃 전에 수신 서버가 처리했을 가능성이 있으므로, 재시도할 때는 같은 논리 작업의
`handoff_id`를 유지해야 한다. 백엔드는 payload를 영속 저장하지 않아 같은 ID의 재요청이
더 최신 스냅샷을 만들 수 있다. 첫 수신 데이터 유지와 중복 처리 정책은 수신 서버의 책임이다.

## 기간과 관측 범위

기간은 `Asia/Seoul` 기준 시작일 자정 이상, 끝일 자정 미만이다. 양쪽 날짜를 함께 지정하며
1일부터 366일까지 요청할 수 있다. 예를 들어 2026-08-01부터 2026-09-01 전까지는
UTC `2026-07-31T15:00:00Z` 이상, `2026-08-31T15:00:00Z` 미만이다.
같은 시간 기준을 SQL 필터, 일별 버킷, 전체 합계에 적용한다. 주별 버킷은 제공하지 않는다.

새 `viewer_period_savings`에는 대상 계정·학생·학원, 요청 기간, 관측 범위, 활동 유무,
전체 합계, 날짜 오름차순 일별 합계를 담는다. 모든 요청 날짜를 포함하므로 거래가 없는 날도
0 버킷이 존재한다. 전체의 각 수치는 일별 수치의 합과 정확히 같아야 한다.

관측 범위는 요청 구간과 **계정 개설 시각부터 `snapshot_at` 전까지**의 교집합이다.

| 상태 | 뜻 |
|---|---|
| `fully_observed` | 요청 구간 전체를 관측했다. 0은 해당 백엔드 기록상 활동 없음이다. |
| `partially_observed` | 계정이 중간에 개설됐거나 스냅샷 이후의 시간이 포함됐다. |
| `unobserved` | 관측할 구간이 없다. 관측 시작·끝은 모두 null이다. |

이 상태와 시작·끝 시각을 기간 전체와 매일에 각각 제공한다. 원인은
`before_account_opened`, `after_snapshot` 순서로 표현한다. 미래 날짜나 개설 이전 날짜의
0을 정상 관측된 무활동으로 해석하면 안 된다. `history_source: backend_recorded`는
이 백엔드의 보존 기록을 뜻하며 Core의 전체 이력을 수집했다는 의미가 아니다.

## 거래별 집계

조회 대상은 요청한 활성 계정 하나다. 학생과 학원은 해당 계정에서 결정하며 현재 학원 소속을
검증한다. 같은 학생의 다른 계정을 합치지 않는다. `viewer_wishes`의 100개 제한과 관계없이
해당 계정의 전체 보존 원장을 SQL에서 집계한다.

| 필드 | 원천 | 횟수·금액 |
|---|---|---|
| `deposits` | `WISH_DEPOSIT` | 신규 저축 사건 수와 양수 위시 금액 |
| `withdrawals` | `WISH_WITHDRAWAL` | 일반 출금 사건 수와 출금액의 크기 |
| `transfers` | `WISH_TRANSFER` | 사건 하나를 1회로, 양수 effect를 한 번만 합산 |
| `completion_returns` | `WISH_COMPLETION_RETURN` | 완료 반환을 별도 집계 |
| `abandonment_returns` | `WISH_ABANDONMENT_RETURN` | 포기 자금 반환을 별도 집계 |
| `deletion_returns` | `WISH_DELETION_RETURN` | 삭제 자금 반환을 별도 집계 |
| `abandonment_count` | `ABANDONED`와 `abandoned_at` | 0원 포기를 포함한 위시 수 |

`CARD_BALANCE_CHANGE`는 위시 저축 행동에 포함하지 않는다. 이동의 두 effect를 두 사건으로
세거나 새 입금으로 더하지 않는다. 포기 횟수에 반환 사건 수를 다시 더하지 않는다.
논리 삭제된 본인 위시의 보존 원장과 포기 기록은 집계에 포함하지만, 삭제된 위시 자체는
`viewer_wishes`와 후보 배열에 포함하지 않는다. 후보 작성자의 계정 전체 기간 통계는 보내지 않는다.

`correction_of_event_id`가 있는 사건은 일반 행동에서 제외하고 `corrections`에 사건 종류별로
횟수·양수 합계·음수 크기 합계를 담는다. 보정이 기록된 시점에 집계하며 원래 사건의 기간을
소급 변경하지 않는다. 보정 표시가 없는 반대 방향 거래는 기록된 거래 종류를 그대로 따른다.
보정된 `CARD_BALANCE_CHANGE`도 제외한다.

모든 횟수와 금액은 0부터 9,007,199,254,740,991까지의 정수 JSON 숫자다. DB 합계와 일별 합계를
정확하게 계산하고 최종 범위를 검사한다. 넘치는 값을 반올림·잘라내거나 문자열로 바꾸지 않고
422 오류로 반환한다. Python도 bool, float, 범위 밖 정수를 거절한다.
기존 `savings_summary`는 **선택된 위시의 전체 기간 effect 수와 양·음 총합**이라는 의미를 유지한다.
새 기간 집계의 사건 수와 같은 뜻으로 해석하면 안 된다.

## 후보와 대표 위시

후보 목표는 최신 50개, 최근 30일 완료 25개, 관심 25개이며 전체 상한은 100개다.
각 후보 조회는 최대 101행만 반환한다. 먼저 관심 25개와 중복되지 않는 완료 25개를 확보하고,
그 ID들을 제외한 최신 후보를 채운다. 부족하면 남은 최신, 완료, 관심 순서로 보충한다.
중복 기준은 공유 카드의 `feed_id`다. 최종 배열은 최신·완료·관심 그룹 순서이고,
각 그룹에서는 정해진 시각 내림차순, ID 내림차순으로 정렬한다.

최신·관심 그룹은 카드 수정 시각, 완료 그룹은 완료 시각을 쓴다. 최근 완료 구간은
`snapshot_at - 30일` 이상, `snapshot_at` 미만이다. 보충 후 그룹 개수는 초기 목표를 넘을 수 있다.
`candidate_selection`의 실제 개수와 순서별 provenance로 각 후보의 출처를 확인할 수 있다.
이는 추천 모델 점수나 공개 피드 순위가 아니다.

세 그룹 모두 같은 현재 학원·활성 소속·활성 계정·공개 범위·조회자→작성자 팔로우 방향·양방향 차단
조건을 적용한다. 본인 카드, 비공개·삭제·포기 위시는 제외한다. Python이 보낸 ID도 권한 검사를
통과해야 한다. `candidates_truncated`는 현재 조회 가능한 카드가 100개보다 많다는 뜻이다.
후보가 없으면 정상 빈 배열이다.

본인 `viewer_wishes`는 현재 대표를 맨 앞에 정확히 한 번 포함하고, 나머지를 생성 시각·ID
내림차순으로 채운다. 대표도 100개 한도에 포함한다. `viewer_wishes_truncated`는 전체 적격
비삭제 위시가 100개를 넘는다는 뜻이다. 다른 작성자의 후보에는 대표 여부를 추가하지 않는다.

## Python 관심 근거

선택 필드 `interest_context`는 `source: python`, 분류 체계·모델 버전, 분류 시각, 요청 계정 ID,
관심 카테고리 ID 배열, 위시별 분류 배열을 받는다. 위시별 항목은 `wish_id`, `category_ids`,
`title_sha256`이다. 제목 해시는 DB에 저장된 정확한 제목의 UTF-8 바이트에 대한 소문자 SHA-256이다.
공백·유니코드 정규화를 적용하지 않는다. 카테고리 ID는 해당 taxonomy 버전 안에서만 해석한다.

위시 분류는 500개, 본인 관심 ID는 20개, 위시별 카테고리는 5개까지다. 식별자는
`[A-Za-z0-9][A-Za-z0-9._:-]{0,63}` 형식이다. 중복 위시 ID, 계정 불일치, 미래 분류 시각은 거절한다.
정확히 30일 된 분류는 사용할 수 있고 더 오래되면 제외한다.

본인의 현재 비삭제 위시에 대한 유효한 제목 해시·분류와 요청 관심 ID의 교집합으로 관심을 확인한다.
후보도 현재 제목과 맞는 분류가 있어야 관심 그룹에 들어간다. 제목 변경·없는 위시·권한 없는 위시의
분류는 후보 선정에 사용하지 않으며, ID별 거절 사유를 응답하지 않는다.
학원용 합성 `academy.category`는 이 과정에 사용하지 않는다.

`interest_evidence.status`는 `absent`, `stale`, `no_usable_classifications`, `used`다.
분류가 없거나 쓸 수 없으면 기본 그룹으로 채운다. `used`여도 권한 있는 관심 후보가 0개일 수 있다.
입력은 요청 동안만 사용하며 분류 테이블·Wish category 컬럼·모델 학습을 추가하지 않는다.
`exhaustive: false`는 받은 제한된 분류가 전체 분류를 대표하지 않음을 나타낸다.

## 오류와 일관성

| HTTP | code | 의미 |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | 요청 형식·기간·분류·크기 오류 |
| 401 | `AUTH_REQUIRED` | 전용 인증 실패 |
| 404 | `CARD_BALANCE_ACCOUNT_NOT_FOUND` | 없는 계정·종료 계정·현재 학원 소속 없음 |
| 422 | `RECOMMENDATION_DATA_INCOMPLETE` | 잘못된 저장 관계·원장 형태·대표 상태·숫자 범위 |
| 503 | `RECOMMENDATION_QUERY_UNAVAILABLE` | 전체 스냅샷을 읽을 수 없는 DB 장애 |
| 502 | `RECOMMENDATION_RECEIVER_REJECTED` | 수신 상태·형식·버전·ID 확인 실패 |
| 504 | `RECOMMENDATION_RECEIVER_UNAVAILABLE` | 수신 연결·전송·응답 시간 초과 |

503과 504는 `retryable: true`지만 서버가 자동 재전송하지 않는다. 조회 실패를 0으로 바꾸지 않고,
스냅샷 구성이 실패하면 수신 서버를 호출하지 않는다. 오류에 DB 메시지·인증 정보·원문 payload를 넣지 않는다.
계정·대표·권한·분류의 현재 제목·기존 요약·기간 집계를 하나의 읽기 전용 `REPEATABLE_READ`
트랜잭션에서 읽는다. DB의 단일 `snapshot_at`을 모든 판단에 쓰고, 트랜잭션을 닫은 뒤 외부 전송한다.

## 실행 가능한 계약 확인

세 JSON Schema는 `api/recommendation/`에 있다. `src/test/resources/recommendation/cases.json`은
Java와 Python이 함께 사용할 정상·오류 문서 목록이다. stdlib 검증기는 이 스키마에 사용된
키워드와 날짜·관계·일별 합계·coverage·대표·후보 중복 등의 의미 제약을 검사한다.
범용 JSON Schema 엔진을 제공하는 도구는 아니다.

```sh
python3 scripts/recommendation/receiver_fixture.py --self-test
python3 scripts/recommendation/receiver_fixture.py --validate /tmp/snapshot-v3.json
python3 scripts/recommendation/receiver_fixture.py --serve 8089
```

마지막 명령은 `127.0.0.1`에만 로컬 fixture 수신기를 연다. 기본 시험용 인증값은
`local-fixture-only`이며 `/receiver`에서 payload와 `Idempotency-Key`를 검증한다.
실서비스 수신기를 배포하거나 실제 추천 서버 연결을 확인하는 명령이 아니다.

Java 검증은 추천 패키지 테스트와 PostgreSQL Testcontainers를 사용한다. 전체 검증 명령은
`./gradlew test --console=plain`이다. SQL은 원장을 메모리로 모두 읽지 않고 DB에서 집계하며,
새 인덱스가 필요할 때만 쿼리 계획 근거와 함께 별도 마이그레이션을 추가한다.
기존 원장·포기 이력과 이전 마이그레이션을 다시 쓰지 않는다.

PostgreSQL 통합 테스트는 실제 전송 JSON을 `build/recommendation/actual-v3.json`에 남긴다.
Docker와 JDK 21을 준비한 뒤 다음 순서로 Java 출력과 Python 수신 계약을 함께 확인할 수 있다.

```sh
./gradlew test --tests 'com.crabit.backend.recommendation.*' --console=plain
python3 scripts/recommendation/receiver_fixture.py --validate build/recommendation/actual-v3.json
```

로컬 확인에서는 이 실제 출력 문서를 fixture 수신기에 HTTP로 두 번 전달했으며,
최초 요청과 같은 ID의 중복 요청 모두 일치하는 v3 확인 응답을 반환했다.
이 검증은 실제 추천 모델 실행이나 운영 Python 서버 연결을 포함하지 않는다.

`recordsExplainAnalyzeForActualBoundedProductionQueries`는 실제 저장소 SQL과 바인딩 값을
`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`으로 실행해
`build/recommendation/query-plans.json`에 계획을 저장한다. 기본 시드에 후보 150개와
대상 계정 원장 사건 150개를 더한 로컬 PostgreSQL 실행 결과는 다음과 같다.

| 조회 | 반환 행 | 실행 시간 |
|---|---:|---:|
| 최신 후보 | 101 | 0.975ms |
| 최근 완료 후보 | 30 | 0.636ms |
| 관심 후보 | 101 | 1.087ms |
| 일별·종류별 원장 집계 | 1 | 0.596ms |

이 작은 데이터에서는 순차 스캔도 선택된다. 기존 계정·발생 시각 인덱스와
DB 내부 집계·후보 반환 상한을 유지하며 이번 변경에는 마이그레이션을 추가하지 않았다.
위 수치는 재현용 fixture의 관측값이며 운영 규모에서의 응답 시간 보장은 아니다.
