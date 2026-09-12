# 실제 재생 DB의 현금 상태 내보내기

`simulationRun`은 지원 명령의 실제 실행이 모두 성공하면 같은 일회용 DB에서 REPEATABLE READ·read-only 트랜잭션으로 현금 원장과 현재 잔액을 읽는다. 결과는 `state/export.json`에 기존 `cashState` schema로 기록한다. 입력 bundle의 state를 복사하거나 기대 금액으로 DB를 채우지 않는다.

각 원장의 종류·금액·계좌별 sequence·발생 시각은 실제 저장 행에서 읽는다. DB에 별도 저장되지 않는 `balanceAfter`는 SQL window sum으로 산출하며 관측에 이 출처를 명시한다. 캐시의 `card_funds`·`cash_sequence`는 그대로 읽는다. 명령은 cashEntryId와 eventId의 연결 및 서로 다른 계좌에서 같은 시각에 발생한 행의 전역 순서만 제공한다. 알 수 없는 원장 ID, 알 수 없는 계좌 또는 DB logical-account 매핑 불일치는 거부한다.

생성된 bytes를 먼저 저장하고 SHA-256을 기록한 뒤 기존 독립 현금 oracle로 명령·원장·잔액을 비교한다. 캐시가 잘못되어도 이를 수정하지 않는다. 캡처가 성공한 뒤 대조가 실패한 경우 관측은 FAILED이고 저장한 state는 남는다. 명령 자체가 실패해 재생기가 중단되면 최종 state를 생성하지 않는다. 기대 결과와 일치한 REJECTED 명령은 정상 재생 결과이며 원장이 생기면 검증 실패다.

JOIN을 실제 완료한 학생만 검증 대상에 포함한다. 미래 가입자의 0원 잔액은 합성하지 않는다. `cashAccounts`와 `allStudentsJoined`가 범위를 표시한다. 전체 100명을 JOIN했더라도 전체 행동 이력, 월 예산, 추천·리캡 또는 제품 검증을 완료한 의미는 아니다. `fullDatasetValidationPerformed=false`, `readyForApplication=false`, `relationalStateExported=false`를 유지한다. 현재 state는 현금 부분만 담으며 전체 관계형 import/export나 canonical bundle을 완성하지 않는다.

새 테스트는 100명별 JOIN·GRANT·PURCHASE·잔액 부족 REJECTED 명령 총400개를 각자 독립된 두 DB에 실행하고 cash-state 및 정규화 응답 bytes/hash의 동일성을 비교한다. 실제 UUID 매핑은 서로 달라야 한다. 별도 실제 DB 검사는 잘못된 기대 금액을 export에 복사하지 않는지, 잘못된 캐시가 그대로 export되고 거부되는지, 원장·신원 연결을 알 수 없으면 거부하는지 확인한다. 부분 재생의 미가입자 제외와 outcome 불일치 시 최종 state 미생성도 검사한다. 최종 실행 결과와 파일 digest는 `verification-516139.json`을 따른다.

운영 도메인 코드·migration·승인 OpenAPI/schema bytes는 이 액션에서 변경하지 않았다. 커밋·Feature Run/action 상태·원격/외부 콘솔·demo 적용은 변경하지 않는다.
