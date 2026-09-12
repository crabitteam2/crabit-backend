# 실제 재생 신원의 typed export

`simulationRun`은 실제 관계형 export와 참조·시간·체크포인트·조정·행동 대조를 마친 뒤 승인된 `idMap` 스키마의 `id-map.json`을 기록한다. 입력 명령에서 미리 할당한 UUID 목록만으로 행의 존재를 주장하지 않는다. 학생·계좌는 실제 JOIN으로 저장된 행만 포함하며, 부분 재생은 100명 전체 신원 검사를 통과한 번들이 아니다.

기존 명령에 명시적으로 바인딩된 논리 ID를 유지한다. 도메인이 자동 생성한 원장은 계좌와 application_order, 관측은 계좌와 account_lookup_version, 위시 효과는 원장과 위시, 조정 케이스는 계좌와 시작 관측을 기준으로 `auto:<sha256>` 논리 ID를 만든다. 이 ID는 원본 UUID를 대체 저장하거나 DB를 변경하지 않는다. 서로 다른 실제 행이 같은 논리 ID에 대응하거나, 같은 종류의 UUID에 별칭이 붙으면 실패한다. 고정된 원인 키가 없는 미등록 신원은 추측하지 않고 거부한다.

ACADEMY·STUDENT의 ownerAccountId는 null이다. ACCOUNT는 자신의 논리 계좌 ID를 참조한다. 위시·원장·효과·관측·조정·리캡은 저장 계좌를, 피드 문맥·행동은 실제 행위자의 계좌를 참조한다. 공유 카드는 위시 계좌를 참조하며 PRIVATE 전환으로 현재 카드가 없어져도 `feed_source_history`의 저장된 payload에서 과거 카드 신원을 보존한다. 같은 카드 UUID의 현재/과거 위시 소유 관계가 다르면 실패한다. 실제 행 또는 보존 이력이 없는 실행 파생 신원도 거부한다.

파일은 canonical JSON으로 새 출력 디렉터리에 한 번 기록한다. `identityMapDigest`는 원본 UUID를 포함한 파일의 해시이고, `logicalIdentityDigest`는 replayUuid만 제외한 신원 집합의 해시다. 후자는 관계형 금액·내용·시간 전체를 정규화한 digest가 아니다. 다른 DB에서 UUID가 달라져도 같은 명령의 논리 신원 집합은 같아야 한다. 원본 raw 응답과 `execution-identities.json`은 계속 보존된다.

현재 스키마에 없는 MEMBERSHIP·FEED_SESSION·FEED_PAGE_STATE는 진단용 execution-identities에만 남는다. typed id-map은 관계형 전체 UUID 정규화, legacy CSV, 완성된 canonical bundle 또는 target/apply UUID 매핑이 아니다. 전체 100명 행동 이력, Python feed/recap, 월별 예산 검증, 기간·annotation·정정 명령, selective apply/restore와 실제 UI·Owner 콘솔 보존·현재 demo 적용은 별도 미완료 범위다. `fullDatasetValidationPerformed=false`, `readyForApplication=false`를 유지한다.

검증은 실제 100명/400명령 두 PostgreSQL 재생의 201개 가입 신원, 자동 관측·원장·효과·조정의 정확한 행 수, 실제 17명령 money/social/feed 재생의 논리 신원 해시 동일성, 현재 카드 삭제 후 과거 신원 보존을 포함한다. 별칭·가짜 파생 신원·누락된 위시 신원·자동 논리 ID 충돌을 거부하며 행 순서를 바꿔도 결과가 같다. 정확한 실행 결과는 `verification-c07a2a.json`을 따른다.
