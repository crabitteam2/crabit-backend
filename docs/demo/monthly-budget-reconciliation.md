# 월별 지급액과 실제 현금 원장 대조

`SimulationMonthlyBudgetVerifier`는 재생된 GRANT 명령과 실제 현금 export를 먼저 기존 독립 cash oracle로 대조한다. 실제로 APPLIED되어 원장에 한 번 존재하는 지급만 합산한다. FAILED/REJECTED 요청, PURCHASE, wish 배분·반환은 월 지급액을 늘리지 않는다. 원장 금액과 명령 금액이 다르거나 지급자가 계좌 소유자와 다르면 실패한다. 금액 합산은 정수와 overflow 검사로 수행한다.

서울 기준 월 시작부터 가입해 있고, 해당 월의 종료 경계까지 관측한 계좌·월은 합계가 10,000~30,000원이어야 한다. 지급이 0건인 월도 검사한다. 경계 직전은 부분 관측이고 경계에 도달하면 전월 검증 대상이 된다. 월 중간 가입과 현재 진행 중인 월은 PARTIAL로 실제 지급 합계만 보고하며, 비례 지급이나 최소 지급을 만들어 넣지 않는다. 9월 11일 00:00 KST cutoff에서는 6~8월만 완전 관측 가능하며 9월은 부분 관측이다. 이 구현은 가입한 계좌를 활성 계좌로 취급한다. dormancy 의미를 구현하거나 휴면 예외를 추가하지 않는다.

지급은 실제 발생한 후에만 계산에 포함한다. `budgetMonth`는 검증된 `scheduledAt`의 서울 기준 월이다. 실지급 합계는 실제 원장 `occurredAt`의 서울 기준 월로 집계한다. 예를 들어 6월 예정 20,000원이 7월 1일 00:00 KST에 들어오면 7월 수령액이며, 6월 부족액을 소급해서 채울 수 없다. 예정월과 예정 시각은 원본 명령에 그대로 보존하고, 예정 시각·월 일치 검증도 유지한다. 이는 승인 계획의 완전 관측 월 실제 지급 합계 규칙 및 기존 `SimulationCashOracle` 집계와 일치한다. 보고서의 `budgetMonth` 필드는 검사 대상 실제 수령월을 가리킨다. 설정된 예정 예산의 배분 한도 검증은 별도 생성기 의무이며 이 실지급 검증이 대체하지 않는다.

실제 실행기는 `monthlyBudgetVerification`에 관측 경계, COMPLETE/PARTIAL 월 수, 계좌·월별 합계와 지급 건수를 기록한다. 경계는 마지막으로 실행한 사건 시각이며, 전체 cutoff를 검증했다고 표시하지 않는다. 100명 중 아직 JOIN이 실행되지 않은 신원은 실제 관측 계좌로 간주하지 않는다. 전체 cutoff까지의 모든 학생·월 검증은 별도 전체 재생의 의무로 남아 있다.

월 예산 위반은 현금 원장이 자체적으로 정합적이어도 재생 결과를 FAILED로 만든다. 이미 생성한 원본 요청·응답과 cash export는 보존하고 `monthlyBudgetReconciliationPerformed=false`, `readyForApplication=false`를 유지한다. 금액을 보정하거나 지급을 추가하거나 DB를 적용하지 않는다.

단위 검증은 경계 포함 여부, 상하한, 분할 지급, 실패/거절, 지급이 없는 월, 부분 가입월, 지연/미래 지급, 잘못된 actor/예정월, 원장 변조와 September cutoff를 다룬다. 실제 PostgreSQL 통합 검증은 기존 100명·400명령을 두 새 DB에서 실행해 초기 80명의 6월 완전 관측과 전체 100명의 7월 부분 관측을 확인한다. 별도 부족액 시나리오는 9,999원 지급과 1원 소비가 정합적인 원장을 만들더라도 월 정책 검증이 실패하고 원본 증거가 남는지 확인한다.

이 변경은 simulation 코드와 문서에 한정된다. 승인된 OpenAPI/공유 dataset schema, main 서비스, migration은 이번 액션에서 바꾸지 않았다. canonical bundle/CSV 조립, 나머지 annotation/기간/adjustment/correction 실행, 실제 Python feed·recap, 100명 전체 행동 이력과 cutoff, selective apply/restore, 대표 UI/API/DB·Owner 콘솔 보존 및 현재 demo 적용은 여전히 미완료다. 기존 예정월 집계는 `verification-b38d12.json`의 역사적 결과이며, 실제 수령월로 수정한 이번 검증 결과는 `verification-f02727.json`에 기록한다.
