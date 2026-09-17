# 체크포인트와 조정 사례의 독립 검증

`act-17db297b8d14da8538a799cc4d6a09ea`는 이미 보존한 `state/relational.json`을 대상으로 금융 이력을 대조한다. `SimulationCheckpointVerifier`와 `SimulationAdjustmentVerifier`는 DB를 읽거나 수정하지 않으며 기대 응답으로 저장 값을 보정하지 않는다. replay는 PK/FK·시간/가입/피드 검사 다음에 두 검사를 수행하고 결과를 observation에 기록한다. 실패하면 기존 raw/state 증거를 남긴 채 성공 projection을 만들지 않는다.

체크포인트는 가입 시각의 빈 baseline, 계좌별 연속 revision, 단조 증가 적용 시각·원장 watermark, 실제 원장과 application의 일대일 대응을 검사한다. 활성 위시 JSON의 정확한 필드, 계좌 소유권, 중복, 금액/목표/상태, 대표 위시와 합계를 검증한다. 각 금액은 해당 watermark까지 실제 ledger effect 누적액과 대조한다. 위시별 정렬된 prefix 합계와 floor lookup을 사용한다. 관측 lookup version과 마지막 성공 관측을 연결하고, 최신 checkpoint는 현재 위시/대표 선택 및 최신 원장·관측에 일치해야 한다.

조정 사례는 성공한 시작 관측과 그 시점의 체크포인트에서 `active_wish_allocation - actual_card_balance`로 최초 부족액을 독립 계산한다. 시작 감소 원장 또는 첫 성공 관측 출처, 사건의 연속 순서·계좌·역할, 마지막 종료 원장, OPEN/RESOLVED 필드, 계좌당 하나의 열린 사례와 정확한 outbox를 대조한다. 열린 사례는 최신 관측 기준 부족액을 유지해야 하고 종료 사례의 최초 종료 checkpoint는 부족액이 해소돼야 한다. 명령 바인딩 ADJUST_ALLOCATION/CORRECT를 추가한 것은 아니다.

실제 disposable PostgreSQL에서 지급·생성·배분·구매·조회로 사례를 열고, 일부 출금과 추가 지급/조회 후 출금으로 종료한다. 원본 export의 최초 부족액을 4000에서 3999로 변조한 자료는 실제 FK 검사를 통과하지만 새 검증은 거절한다. 체크포인트 금액·합계·대표 금액을 함께 조작해도 원장 누계와 달라 거절한다. 누락 application/baseline, 잘못된 JSON 내부 위시, 관측 버전, 사건 순서/역할과 outbox 누락도 검증한다. 원본 export와 DB는 검사 후 동일하다.

이 검사는 새 빈 DB에서 현재 지원 명령을 재생한 결과에 한정된다. 기존 DB legacy backfill, 정정 명령, 모든 과거 시점의 위시 집합 완전성 및 목표 변경, JSON 전체 ID 정규화, canonical CSV/bundle, 월 예산, 기간 마감, Python, 100명 전체 행동 재현성, selective apply/restore 및 demo/UI 적용은 별도 후속 작업이다. 일반 backend bootJar나 승인된 OpenAPI/schema/migration을 변경하지 않는다. 정확한 테스트 결과는 verification-17db297.json에 기록한다.
