# 행동 시점의 접근 권한 독립 검사

`SimulationBehaviorAccessVerifier`는 실제 dispatcher가 실행한 명령을 sequence 순서대로 읽고, 실제 DB에서 export한 피드 항목·수집 사건이 해당 시점에 허용됐는지 재계산한다. 운영 서비스의 접근 권한 메서드나 SQL을 호출하지 않는다. 현재 관계 테이블의 최종 상태를 과거 사건에 적용하지 않는다. 같은 시각에 발생한 명령도 sequence 순서로 처리하므로 나중의 팔로우가 앞선 조회를 허용하지 않는다.

성공한 JOIN에서 학원 소속을 만들고 CREATE에서 PRIVATE 위시를 시작한다. SHARE/VISIBILITY_CHANGE, DELETE와 viewer→owner 방향 FOLLOW/UNFOLLOW, 양방향 차단을 누적한다. BLOCK은 양쪽 팔로우를 모두 종료하며 UNBLOCK은 차단 하나만 해제한다. 실패·거부된 명령은 권한 상태를 바꾸지 않는다. CREATE의 멱등 재실행은 이후 공개 범위나 삭제 상태를 초기화하지 않는다.

실제 `behavior_result_item`과 `behavior_event`의 카드 UUID를 당시 활성 카드와 연결한다. 일반 피드·노출·클릭에서 자기 카드, 다른 학원, 가입 전 학생, PRIVATE·삭제 카드, 어느 방향이든 차단된 학생, viewer가 owner를 팔로우하지 않은 FOLLOWERS 카드를 거부한다. 비공개 전환으로 사라진 카드의 UUID는 다시 공개한 새 카드와 구별한다. 이후 비공개·삭제·차단은 앞선 정당한 노출을 무효화하지 않는다. PROFILE_VISIT에는 두 학생의 학원 소속과 차단 여부를 적용하며 공개 위시나 팔로우를 요구하지 않는다.

기존 `SimulationBehaviorVerifier`가 명령과 저장 행의 신원·시각·항목·이벤트 집합 및 보존된 방문 증거를 검사한 뒤 이 검사를 호출한다. 재생 관측의 `behaviorVerification.access`에 피드 페이지·피드 카드·수집 카드·프로필 방문 검사 수를 기록하고, 성공 후에만 `behaviorAccessReconciliationPerformed=true`로 표시한다. 위반 오류에는 `BEHAVIOR_ACCESS_<rule> event=<logical event ID>`가 들어간다. raw export는 검사 전에 저장하며 수정하지 않는다.

검증은 단위 손상 주입과 실제 일회용 PostgreSQL의 도메인 재생으로 수행한다. 실제 시나리오는 역방향 팔로우만 있을 때 빈 피드, 정방향 팔로우 후 카드·클릭, 차단된 클릭의 거부, 해제 후에도 빈 FOLLOWERS 피드, 다시 팔로우한 뒤 조회, PRIVATE→ACADEMY 재공유의 새 UUID와 이전 카드 클릭 거부를 연결한다. 저장 행은 그대로 두고 선행 팔로우 방향만 변조해도 독립 검사가 거부해야 한다. 실행별 정확한 결과는 `verification-a68e4e.json`에 기록한다.

이 검사는 반환된 카드의 권한을 증명한다. 모든 후보의 누락 여부, 추천 순위, Python 호출, 방문 카테고리 재분류, 거부 사유 전체의 독립 재현, 전체 기간 마감·월 예산을 증명하지 않는다. 입력 명령의 schema·순서·실제 실행 결과와 typed UUID 바인딩은 기존 재생 검증이 선행해야 하며 임의 bundle의 독립 import 승인이 아니다. 지원된 명령에는 membership 탈퇴나 계좌 폐쇄가 없다. 그런 명령을 추가할 때 상태 전이 검증도 함께 확장해야 한다.

전체 100명 행동 이력, canonical bundle/CSV 조립, 실제 Python 피드·리캡 및 기간 경계 격리, selective apply/restore, 대표 UI 및 현재 데모/Owner 콘솔 보존 검증은 남아 있다. 이번 변경은 simulation 소스셋 안에 있으며 승인된 schema/OpenAPI, 운영 코드, Feature Run/action 상태 또는 원격 환경을 바꾸지 않는다.
