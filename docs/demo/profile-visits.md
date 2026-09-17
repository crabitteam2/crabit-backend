# 실제 프로필 방문 재생

액션 `act-6a6c30ee26746a8de397c282303bdb61`은 기존 `SimulationCommandDispatcher`에 PROFILE_VISIT을 연결한다. 총 18종 명령을 지원한다. 시뮬레이션 전용 Spring 구성에 실제 BehaviorService, FeedVisitEvidenceService, FeedCategoryClassifier를 등록하고 기존 트랜잭션 proxy를 사용한다.

방문 시각은 사건 occurredAt에 고정한 Java/SQL 시각이다. 실제 서비스가 학원 접근, 대상 membership, 자기 방문과 양방향 차단을 확인한다. 별도 feed 노출 없이 직접 방문할 수 있다. 상대방 위시가 비공개인 경우 프로필 방문 자체는 수집하지만 해당 위시의 관심 분류는 포함하지 않는 기존 제품 의미를 유지한다. 가정 기반 합성 입력이며 실제 사용자 로그가 아니다.

성공한 방문의 실제 UUID를 BEHAVIOR_EVENT:<logical eventId>로 등록하고 원래 서비스 Outcome을 결과 bytes로 반환한다. 거절된 사건은 REJECTED와 실제 오류 코드로 남고 성공 신원 매핑을 만들지 않는다. 같은 전체 사건 재전달은 저장한 결과 bytes를 반환한다. 지정한 sourceEventId는 선행 causes에 있어야 하며 원본 명령은 실행기 메모리에 보존한다. source/sourceEventId는 시뮬레이션 인과관계 자료이며 기존 behavior_event 테이블에 새로운 필드로 저장하지 않는다.

실제 PostgreSQL 검사는 다음을 확인했다.

- 고정 역사 시각의 occurred_at, received_at, captured_at 및 history baseline 일치.
- 실제 공개 위시의 방문 당시 분류와 source version 저장.
- 이후 PRIVATE 전환 뒤 같은 방문을 실제 서비스에 재전달해도 원본 분류·버전·시각 유지.
- PRIVATE 전환 후 새 방문은 알려진 빈 분류로 저장.
- 차단 뒤 거절된 방문은 behavior_event/feed_visit_evidence 모두 미기록.
- dispatcher의 동일 사건 bytes 재사용, 자기 방문·미가입 대상 거절, 누락된 source cause 거부와 성공 방문만 UUID 매핑.

`./gradlew simulationTest test bootJar --console=plain` 성공. simulationTest는 실제 189개 실행, 실패/생략 0개다. 일반 test와 bootJar는 UP-TO-DATE였다. 기존 일반 test XML은 697개 중 696 성공·1 생략이며 이번 실행의 새 일반 회귀 증거로 사용하지 않는다. 생략은 실제 Python recap parity 검사다. `bash scripts/demo/verify-runtime-isolation.sh`도 통과했으며 JAR 구조 검사만 증명한다. 초기 제한 환경의 Gradle cache lock 접근 실패 후 호스트 권한으로 실행했다.

최종 명령·파일 digest는 verification-6a6c30.json에 기록한다. 이전 검증 기록은 보존한다. 승인된 OpenAPI와 canonical bundle schema bytes, main 서비스 및 migration은 이번 액션에서 변경하지 않았다. 커밋·배포·provider 쓰기·Feature Run/action 상태 변경은 실행하지 않았다.

전체 backend 구현은 아직 미완료다. 전체 bundle replay CLI, feed query/impression/click와 실제 Python feed/recap·기간 마감 연결, ADJUST_ALLOCATION/CORRECT 바인딩, 모든 논리 신원 정규화·원본 sidecar 저장·관계형 export/CSV, 독립 100명 두 DB 재현성, selective apply/restore, 대표 UI·Owner 외부 콘솔 보존·현재 데모 적용은 남아 있다. 이 부분 검증을 backend ready 또는 gate 통과로 사용하지 않는다.
