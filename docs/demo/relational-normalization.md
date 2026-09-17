# 관계형 실행 신원 정규화

`act-eba05f33dee4bf694341a924a6e70526`은 실제 재생의 `state/relational.json`과 typed `id-map.json`을 보존한 뒤 별도 `normalized-relational.json`을 기록한다. 원본의 금액·시각·일반 문자열을 유지하고 실제 catalog에서 UUID로 선언한 열만 논리 신원으로 치환한다. UUID처럼 보이는 이름이나 목적 문자열은 변경하지 않는다. 정규화된 행은 canonical JSON으로 정렬한다.

학생·계좌·위시·관측·원장·공유 카드·행동 등은 검증된 typed id-map을 사용한다. membership·관계·checkpoint·조정 사건 연결·outbox·페이지 문맥/상태·impression은 실제 행의 안정된 관계 키에서 파생한다. 동일 페이지 위치의 서로 다른 impression은 각각 연결된 실제 행동 사건의 논리 ID로 구분한다. 이미 삭제된 관계는 보존된 feed history로 연결한다. 알 수 없는 UUID, 다른 dataset, 논리 신원 충돌은 거부한다. 동일한 자연 키로 서로 다른 UUID가 생긴 모호한 페이지 상태는 임의 순서를 부여하지 않고 실패한다.

feed history payload는 원본 테이블 catalog에 따라 처리한다. checkpoint의 active wishes는 논리 위시 ID 순서로 정렬하며 페이지 카드 배열의 순서는 보존한다. idempotency snapshot의 UUID와 가상 현금 source reference의 계좌도 연결한다. SQL 관계·시간·checkpoint·행동 검증과 typed identity capture가 먼저 성공해야 한다. 정규화는 검증된 구조의 비교용 projection이며 독립적인 DB 유효성 검사나 importer가 아니다.

idempotency `requestFingerprint`는 runtime UUID를 포함한 입력에서 생성된 hash다. 후속 구현 `act-af65eee7b93abd409ba14dfa27fbba22`부터 replay는 원본 hash와 저장 응답을 실제 성공 명령·응답에 대조한 뒤 논리 fingerprint로 정규화한다. 검증 없이 normalizer만 호출한 진단 경로에서는 원본을 그대로 보존하고 `opaqueRuntimeFields`에 표시한다. 자세한 대조와 실패 의미는 `idempotency-reconciliation.md`를 따른다. 지원하지 않는 비어 있지 않은 JSON도 보존하고 같은 목록에 표시한다. 이때 `allRuntimeValuesNormalized=false`이며 전체 재현성 해시로 해석해서는 안 된다. 따라서 이 파일은 승인된 최종 `normalized.json`을 대신하지 않는다. 원본 request/response, relational export와 기존 digest는 유지된다.

100명 JOIN 및 현금 400개 명령의 두 독립 DB에서 전체 관계형 projection의 byte 동일성을 검사한다. 위시·금전·소셜·피드의 17개 명령은 현재 fingerprint 검증 후 student를 포함한 38개 테이블을 모두 비교한다. 이전 액션은 student를 제외한 37개 테이블을 비교했다. 이 시나리오를 전체 bundle/100명 행동 이력 재현성으로 보고하지 않는다. 단위 검사는 일반 텍스트/금액/시각 보존, 원본 불변성, 행 순서, 누락·충돌 신원 및 다른 dataset 거부를 다룬다. 정확한 이번 실행 결과는 `verification-eba05f.json`에 기록한다.

현재 검증 결과는 `verification-af65ee.json`을 따른다. 남은 작업은 전체 canonical bundle/legacy CSV, 나머지 명령·기간·annotation·월 예산 검사, 실제 Python 추천/recap과 100명 전체 이력, apply/restore, 실제 대표 UI/API/DB·Owner 콘솔 보존 및 별도 권한이 필요한 demo 적용이다.
