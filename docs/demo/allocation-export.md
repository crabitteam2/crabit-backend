# 위시 배분 상태와 원장 대조

액션 `act-de6bbd9563b813168ca68c2a9b78b1d1`은 simulation 전용 `SimulationAllocationState`를 추가했다. 컨트롤러가 만든 backend worktree의 로컬 disposable PostgreSQL만 읽으며 기존 demo/production DB를 입력받지 않는다.

`SimulationCommandDispatcher.allocationState()`는 읽기 전용 REPEATABLE READ 트랜잭션에서 해당 dataset 계좌의 wish, ledger_event, ledger_wish_effect를 고정 SELECT로 캡처한다. 임의 SQL/table 입력이나 import 기능은 없다. 자동 balance observation이 만든 CARD_BALANCE_CHANGE도 포함한다. Wish/Root/Effect record가 export 열을 고정한다. 목적 문자열 등 전체 도메인 필드는 포함하지 않으므로 완성된 relational export가 아니다.

`simulationRun`은 실제 캡처 결과를 `state/allocation.json`에 CREATE_NEW/fsync로 보존한 다음 검증한다. digest는 저장 canonical JSON bytes의 SHA-256이며 UUID를 유지한다. 별도 DB 사이에 같은 digest가 나와야 하는 정규화 결과가 아니다. 현금 `state/export.json`은 기존 형식을 유지한다. 관측 파일의 allocationStateExported/allocationReconciliationPerformed가 각각 실제 저장과 대조 성공을 구분하며 오류 때 후자는 false로 남는다.

검증은 production 잔액 계산기를 호출하지 않는다. wish/ledger/effect 신원의 중복, 정확한 알려진 wish 집합, 계좌 FK, 위시 생성 전 효과, cutoff 이후 시각, command-bound root 누락을 거부한다. 각 위시의 effect 합계를 저장 wish_amount와 대조하고 완료·포기·삭제 잔액 및 완료 시각을 검사한다. 배분 root의 account_delta는 0이어야 한다. deposit은 양의 효과 하나와 관측 참조, 반환은 음의 효과 하나, transfer는 서로 다른 위시의 합계 0인 두 효과를 요구한다. CARD_BALANCE_CHANGE에 위시 효과를 붙일 수 없다.

추가 command 검사는 성공한 실제 root를 입력의 명령 종류·계좌·시각과 연결하고 deposit/withdraw 금액 및 transfer 양쪽 위시/금액을 대조한다. 이 검사는 원장과 cache를 같은 거짓 금액으로 함께 바꾼 경우도 거부한다. 결과가 REJECTED인 명령에 root가 연결되거나 command 없는 allocation root가 있으면 실패한다. idempotency 재생의 기존 root는 한 번만 검사한다. 돈이 없는 종료 명령은 새로운 root 없이 성공할 수 있다.

전체 시간순 독립 lifecycle oracle, observation FK/금액 체인, 모든 관계형 테이블, typed id-map, canonical 전체 bundle/CSV, ADJUST/CORRECT, 기간·annotation, 실제 Python, 100명 전체 행동 이력, apply/restore·UI·Owner 콘솔 보존·현재 demo 적용은 여전히 남아 있다. 특히 correction_of_event_id가 있는 원장은 현재 명시적으로 거부한다. 이 부분 검사는 gate 승인이나 적용 준비 완료가 아니다. 승인된 OpenAPI/schema와 main 코드/migration은 이 액션에서 변경하지 않았다.
