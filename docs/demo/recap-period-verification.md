# 리캡 기간 원장 대조

`SimulationRecapPeriodVerifier`는 CLOSE_WEEK/CLOSE_MONTH의 실제 종료 시각에 읽은 `ledger_event`, `ledger_wish_effect`, `wish`, `behavior_event` 원본 행으로 입력을 대조한다. 제품의 snapshot 생성기·리캡 계산 함수를 호출하여 기대값을 만들지 않는다. DB 행은 해당 계좌 및 학원으로 제한하며, 실행기가 소유한 일회용 로컬 DB만 읽는다.

실행 순서는 실제 snapshot 준비 → 원본 행 및 동결 입력 파일 보존 → 독립 검증 → 실제 Python 호출 → 저장 행 보존이다. 검증 실패 시 Python을 호출하지 않고 재생을 중단한다. generation 준비 자체는 이미 수행되므로 검증 실패를 무효과로 주장하지 않는다. 원본은 `event-N-period-source.json`, `event-N-period-input.json`이며, 통과한 경우에만 `event-N-period-verification.json`이 생긴다. 재생 journal은 이 파일들을 `raw/recap-period/` 아래에 원본 바이트와 checksum으로 기록한다. `recapPeriodsVerified`는 실제 통과한 종료 사건 수이다.

독립 검증은 다음을 포함한다.

- 같은 계좌의 원장·효과와 정정 원본 관계를 따라 유효 거래를 재구성한다. 입력의 거래를 root/wish/시각/종류/금액 및 중복 개수까지 비교한다. 순서 보존은 기존 정규화 검증의 책임이며 이 검증은 다중집합을 비교한다.
- 종료 경계 이후 및 경계와 같은 시각의 원장·정정·위시 생성·종결은 해당 종료 자료로 받지 않는다. 기간 시작 이전 원장도 생애 누적 이력과 위시 잔액 대조에 포함한다. 기간별 입금 횟수와 순저축은 `[start, endExclusive)`만 합산한다. 지급·구매·이체·종결 반환을 순저축 입출금으로 잘못 더하지 않는다.
- 위시 집합, 문구, 목표금액, 상태, 생성·종결·삭제 시각과 기간 종료 배분액을 원본과 비교한다. 대표 위시 ID와 플래그의 내부 일관성을 확인한다.
- 학원·방문자·대상·발생 시각·수신 시각으로 수신 방문 수, 고유 방문자 수, 직전 주 수신 방문 수, 해당 월 발신 방문 수를 다시 계산한다. 종료 시각과 같은 발생 사건 및 종료 후 수신된 사건은 제외한다.

대표 위시 선택과 peer_metrics는 후속 `recap-peer-verification.md`의 원본 대조로 연결했다. 성공 story 후보의 완전성·권한, Python의 전체 view/metrics 재계산은 아직 포함되지 않는다. 정정 체인의 검증 지원은 ADJUST/CORRECT 실행 명령 지원이나 계약 승인을 의미하지 않는다. canonical bundle 완성, 100명 전체 기간 재생, 실제 추천 Python, 선택적 적용·복원, 대표 UI 및 현재 demo 적용도 별도 남은 작업이다. 기존 제품 OpenAPI와 main 코드는 이 액션에서 수정하지 않았다.

회귀는 원본 수정 없이 입력 거래 누락·중복·금액·ID·시각 변조, 미래 정정, 미래 위시, 위시 누락·잔액 변조, 경계 및 늦은 수신 방문, 다른 계좌와 끊어진 정정 연결을 거부한다. 실제 Python/PostgreSQL 경로에서 경계 1마이크로초 전 입금을 포함한 주간 3,000원과 경계 이후 입금을 포함한 월간 3,500원을 대조하고, 후속 사건 이후에도 주간의 보존된 원본이 같은 검증 결과를 내는지 확인한다. 두 DB 재생과 raw index checksum 검사에도 기간 자료가 포함된다. 실행 결과는 `verification-c18833.json`을 참조한다.
