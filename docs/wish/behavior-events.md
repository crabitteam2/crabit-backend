# 행동 이벤트 수집과 기간 지표

정식 계약은 `api/openapi.yaml`의 세 학생 POST와 세 내부 GET 및 `x-behavior-collection-policy`다. #46 프로필 방문과 #48 피드 노출·클릭을 같은 actor/eventId 저장 경계에서 처리한다. 기존 프로필/공유 카드 GET과 feed-results 생성은 방문·노출·클릭을 만들지 않는다. 과거 GET에서 행동을 역산하거나 backfill하지 않는다.

## 수집

- 인증 학생은 actor다. 실제 프로필 경로 진입마다 eventId를 하나 생성한다. 새로고침·뒤로 가기 재진입은 새 방문, 렌더링·refetch·prefetch·단순 refocus는 새 방문이 아니다. 자기 방문은 `SELF_PROFILE_VISIT`으로 거절한다.
- `POST /v1/academies/{academyId}/feed-results`는 기존 현재 가시성/커서/최신순 페이지를 조회해 반환한 카드와 배열 인덱스를 저장한다. 페이지마다 다른 resultContextId를 발급하며 LATEST, recommendationResultId=null, modelVersion=null을 반환한다.
- 새 피드 이벤트는 본인 학원의 맥락에 실제 반환된 cardId/position을 사용한다. 맥락은 생성부터 24시간 미만 동안 사용하며 occurredAt은 생성보다 5분 이상 앞설 수 없다. 기존 맥락은 현재 권한을 대신하지 않는다.
- 노출은 문서가 보이는 동안 카드가 50% 이상 연속 1000ms 보인 경우다. 조건 이탈 또는 탭 숨김은 타이머와 주기를 종료한다. 주기당 impressionId 하나, 노출 하나다. 새 주기에만 새 impressionId를 쓴다.
- 클릭은 기존 방문하기 동작의 AUTHOR_PROFILE만 지원한다. 클릭은 노출보다 먼저 또는 노출 없이 도착할 수 있다. 도착한 프로필의 실제 진입은 별도 PROFILE_VISIT이다. 클릭에서 노출을 합성하지 않는다.

UUID와 UTC Z occurredAt은 필수다. 소수는 마이크로초로 절삭한다. 새 이벤트의 occurredAt 허용 범위는 서버 receivedAt 기준 -24시간부터 +5분까지 양끝 포함이다. 중복 JSON 키, 모르는 필드, null, 잘못된 타입, 모르는/반복 query parameter를 거절한다.

actor별 PostgreSQL transaction advisory lock과 UNIQUE(actor_id,event_id)가 유형을 가로지르는 재전송 경계를 이룬다. 맥락·카드·위치에 고정된 actor/impressionId와 노출 unique index가 impression 재사용을 막는다. 완전히 같은 재전송은 원래 occurredAt/receivedAt을 그대로 담아 200 + Idempotency-Replayed:true, 최초 수락은 201이다. 다른 내용은 409이며 원래 내용이나 다른 actor 기록을 공개하지 않는다. 24시간이 지나도 90일 보존 중인 동일 재생은 새 이벤트 시간/맥락 만료 검사보다 먼저 처리한다. 제출 범위와 원래 범위 모두 현재 접근을 재검증한다.

## 지표와 현재 접근

내부 지표는 `crabit.recommendation.handoff.enabled=true`일 때만 등록하며 기존 trigger-credential의 정확한 단일 Bearer 헤더를 요구한다. 학생/receiver 토큰, 중복 헤더는 거절한다. 인증 필터의 정확한 GET 템플릿과 controller marker 검증이 HEAD 또는 경로 정규화 우회를 차단한다. 모든 수집/지표 응답과 오류는 no-store다. 추천 outbound payload는 바꾸지 않는다.

기간은 Seoul 날짜 fromDate 포함, toDate 제외이며 1~90일이다. toDate는 내일을 넘지 않는다. occurredAt이 기간 안이고 asOf 이하이며 receivedAt이 asOf-90일보다 큰 기록만 집계한다. 한 REPEATABLE_READ 스냅샷으로 현재 회원·차단·팔로우·카드 공개/삭제/포기·계정 종료 상태를 재평가한다. raw 보존 자료는 내부에만 남는다. 권한이 복구되면 아직 보존 중인 기록이 다시 기여할 수 있다.

방문수와 전체 기간의 distinctVisitorCount는 다르다. 일별 distinct를 더해 전체 distinct를 만들지 않는다. author-interest는 actor→author 프로필 방문수이며 위시 카테고리 선호나 피드 클릭으로 치환하지 않는다.

피드는 LATEST와 페이지 내 position별로 exposureCount, clickCount, clickedExposedImpressionCount, unmatchedClickCount를 낸다. 연결 키는 actor/impressionId이며 DB에서 context/card/position에 불변 결속된다. 양쪽 이벤트가 각각 기간과 현재 권한 조건을 만족해야 연결된다. 같은 impression에 여러 클릭은 clickCount만 늘리고 CTR 분자는 한 번만 늘린다. CTR=클릭이 있는 노출 impression 수/노출 impression 수이며 분모 0은 null이다. 연결되지 않은 클릭도 clickCount와 unmatchedClickCount에 남는다.

## 보존과 관측 범위

V14의 behavior_collection.started_at은 traffic과 무관한 최초 DB 활성화 시각이며 재시작 시 유지된다. collection activation은 프런트엔드 계측 가용성을 보장하지 않는다. coverage는 COMPLETE/PARTIAL/NONE, 시작 시각, receivedAt 보존 cutoff, fullyRetainedFrom=max(시작, cutoff+5분), availableThrough=asOf, BEFORE_COLLECTION/RETENTION_EXPIRED/OPEN_PERIOD 사유를 제공한다. 시간 범위가 겹치지 않아도 실제 보존 이벤트가 있으면 PARTIAL이다. NONE의 방문 count는 null이고 피드 items는 빈 배열이다. 일별 coverage는 각 날짜에서 별도로 계산한다. 0은 관측한 유효 보존 이벤트가 없다는 뜻이며 관측하지 못한 실제 활동이 없다는 뜻이 아니다. 24시간 지연 도착과 현재 접근 변화로 과거 결과가 바뀔 수 있다.

행동과 replay identity는 최초 receivedAt부터 90일이며 같아지는 시점에 논리 만료된다. 이후 같은 eventId의 역사적 보장은 없고 새로운 시간 검사를 적용한다. 물리 cleanup은 기본 매시간, 최대 100개 transaction, event/impression/context 각각 최대 1000행의 bounded batch(맥락당 최대 100개 item은 함께 삭제)를 실행한다. 만료 이벤트→사용되지 않는 만료 맥락의 impression→맥락/항목 순서다. 맥락 KEY SHARE와 cleanup의 SKIP LOCKED가 수집 중 참조를 보호한다. retained event가 있는 맥락/impression은 replay를 위해 유지한다. 만료 이벤트 backlog는 WARN으로 출력하며 물리 삭제 목표는 논리 만료 후 추가 24시간 이내다. 작업 오류는 scheduler 오류로 남고 성공으로 위장하지 않는다. backlog가 지속되면 실행 빈도와 DB 용량을 점검해야 한다.

fixture reset은 행동/맥락/활성화 메타데이터를 결정적으로 재설정한다. 기존 photo cleanup work 보존은 유지한다. 과거 card 식별자는 살아 있는 shared_card FK로 연결하지 않아 공개 카드 삭제가 90일 replay 보존을 삭제하거나 막지 않는다.

## 프런트엔드 후속 작업

#95/#96에서 canonical OpenAPI를 바이트 그대로 복사하고 타입을 재생성한다. 최대 100개 in-memory queue와 24시간 내 최대 3회 transient retry를 사용하며 같은 payload와 ID를 보낸다. nonretryable 4xx는 중단하며 이벤트 실패가 화면 이동을 막지 않는다. 실제 브라우저의 visibility/dwell, tab hide, reload/back navigation 검증은 이 backend PR의 완료 증거가 아니다. 추천 모델/랭킹/대시보드/개별 방문자 목록은 범위 밖이다.
