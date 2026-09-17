# 관측 출처와 원장 참조 검증

`SimulationObservationState`는 재생 DB에 커밋된 관측·계좌 lookup version·가상 현금 sequence를 읽고 배분 export의 원장과 대조한다. `simulationRun`은 `state/observations.json`을 새 파일로 먼저 저장하고 SHA-256을 기록한 다음 검증한다. 저장 여부와 검증 성공은 observationStateExported 및 observationReconciliationPerformed로 나누며, 전체 검증 및 적용 준비 상태는 계속 false다.

성공 관측은 Owner를 포함하여 SIMULATION 출처, 정확한 dataset ID와 `cash:<account UUID>:<sequence>`를 가져야 한다. 관측 금액은 해당 계좌의 GRANT/PURCHASE 원장 누적합과 같아야 한다. 미래 현금을 참조하거나 관측보다 앞서 커밋된 더 최신 현금 sequence를 무시하면 거부한다. 같은 시각의 순차 사건은 시각만으로 앞뒤를 판정할 수 없으므로 source sequence를 보존한다. 전체 사건과 관측의 sequence 연결은 추가 작업이다.

계좌별 lookup version은 1부터 연속이어야 하며 계좌의 현재 version과 일치해야 한다. 성공 관측은 직전 성공 관측의 ID·잔액을 참조한다. 실패 관측은 성공 체인, 잔액, change root를 가질 수 없다. 기존 실패 서비스는 PROVIDER/null provenance를 저장하며 이는 외부 조회 성공을 뜻하지 않는다. 재생 테스트에서는 실제 recordFailure 서비스를 호출하여 저장 후 다음 성공이 실패 행을 건너뛰는지 확인한다. 외부 제공자 요청을 실행한 검사는 아니다.

성공 관측의 잔액 차이가 0이면 새 CARD_BALANCE_CHANGE가 없어야 하고, 0이 아니면 같은 계좌·시각·차액의 실제 root가 정확히 하나 필요하다. export 내 모든 CARD_BALANCE_CHANGE는 관측에 연결되어야 한다. 입금 root는 같은 계좌·시각의 성공한 PRE_DEPOSIT 관측을 참조해야 한다. 실패한 입금 시도에도 먼저 커밋된 PRE_DEPOSIT 관측은 남을 수 있다.

이 파일은 고정 SELECT와 Java record로 만든 진단 export다. 일반 SQL, target DB, apply/restore 입력을 받지 않는다. canonical JSON은 실제 UUID를 유지하므로 서로 다른 DB의 raw digest를 재현성 지표로 사용하지 않는다. 현금 command 독립 검증과 allocation 검증도 별도로 실행한다. 전체 관계형 export, observation 정규화/논리 신원, 조정 케이스·checkpoint 참조, CORRECT/ADJUST 명령 바인딩, 전체 시점별 검증, Python 추천·리캡, 대표 UI, selective apply/restore와 현재 demo 적용은 남아 있다.
