# 최신 액션: 실제 피드·노출·클릭 재생

`act-9873e1ac93d6d430b8998af55c2d3ff1`은 실제 `BehaviorService.createResult/collect`에 FEED_QUERY·IMPRESSION·CLICK을 연결하여 총21종을 실행한다. 실제 페이지 순서를 기대값과 비교하고 불일치 시 원본 응답을 보존하며 중단한다. 노출 없는 클릭을 허용하고 실제 권한·중복 노출·만료·privacy/재공유를 검증한다. 현재 피드는 LATEST이며 Python 추천은 미검증이다. 상세 동작과 신원 규칙은 feed-behavior.md, 최신 실행 증거는 verification-9873e1.json을 따른다. 전체 replay/export/Python/100명 두DB/apply/restore/UI/demo 적용은 계속 미완료다. 아래는 이전 액션 이력이다.

최신 추가: act-6a6c30ee26746a8de397c282303bdb61은 아래 17종에 PROFILE_VISIT을 더하여 18종을 지원한다. 실제 방문 수집·분류 증거·권한·중복 검증은 profile-visits.md를 따른다. 아래는 최초 dispatcher 구현 설명이다.

# 로컬 명령 실행기

`src/simulation`의 `SimulationCommandDispatcher`는 신뢰하는 저장소 schema와 dataset/manifest digest, 정확한 100명 입력을 받아 새 독립 PostgreSQL runtime을 소유한다. 기존 DB URL·target mapping을 받지 않으며 웹 endpoint와 bootJar에 포함되지 않는다. 입력 학생 정보는 가정 기반 합성 입력이다.

17종 지원 명령: JOIN, GRANT, PURCHASE, BALANCE_LOOKUP, CREATE, DEPOSIT, WITHDRAW, TRANSFER, COMPLETE, ABANDON, DELETE, SHARE, VISIBILITY_CHANGE, FOLLOW, UNFOLLOW, BLOCK, UNBLOCK. 가입 행만 고정 SQL로 원자적으로 생성하며 돈·위시·관계 변경은 기존 서비스와 트랜잭션을 사용한다. 각 실제 SQL/Java 시각을 runtime에서 확인한다. 늦은 가입자를 처음부터 membership에 넣지 않는다.

사건마다 닫힌 schema, 기간/순서, 선행 원인, artifact reference, actor 가입을 확인한다. 클라이언트 UUID를 신원으로 받지 않고 종류별 logical ID→실제 UUID 매핑을 유지한다. Owner 학생/계좌는 기존 고정 ID다. CREATE 후 실제 Wish UUID를 등록하며 TRANSFER의 root와 두 effect는 실제 commit된 원장에서 읽어 별도로 매핑한다. 매핑은 종류별 일대일 대응을 지킨다.

동일 eventId와 같은 전체 입력은 저장한 결과 바이트를 돌려주고 서비스를 다시 호출하지 않는다. 다른 입력은 충돌한다. 별도 eventId의 동일 domain idempotency key는 기존 서비스가 처리하며 그 replay 결과를 그대로 보존한다. expectedVersion을 현재 버전으로 덮어쓰지 않는다. DEPOSIT의 PRE_DEPOSIT 관측은 기존 서비스가 별도 커밋하므로 거절된 배분에도 관측이 남을 수 있다.

outcome은 실제 실행을 제어하지 않는다. 서비스의 실제 APPLIED/REJECTED/FAILED와 비교한다. 예상 불일치는 `mismatchedResult()`에 실제 바이트를 남기고 실행기를 중단한다. 예상하지 못한 DB/코드 예외 역시 즉시 중단하며 도메인 거절로 위장하지 않는다. 이때 앞선 커밋이 있을 수 있으므로 전체 DB를 소유한 실행기를 닫고 버린다. dataset READY 전환이나 원격 재시도는 하지 않는다. 결과 바이트/목록/신원 맵은 방어적으로 복사한다.

검증은 실제 지급과 현금 부족 거절, 위시 생성 replay, 중복 사건, 배분 거절, 이체 보존과 두 effect 신원, 조기 완료 거절/명시적 완료 반환, 별도 구매와 관측, 수동 출금에 따른 불일치 해소, 포기 금액과 삭제, 소셜 4종 및 공개 범위 변경, 100명 가입 시점, 허위 outcome/미지원/미가입/다른 계좌 명령을 포함한다. 정확한 실행 수와 결과는 verification.json을 따른다.

이 클래스 자체는 도메인 명령 dispatcher다. 현재 PROFILE_VISIT·FEED_QUERY·IMPRESSION·CLICK을 포함한 21종을 지원한다. `simulationRun` CLI가 별도 새 DB/출력 경로에서 호출하고 실제 결과 byte[]를 resultRef와 raw index에 기록한다. 상세 원본 의미·중단·파일 보존은 replay-recording.md를 따른다. 전체 typed id-map·관계형 이력 export/FK/CSV·전체 상태 독립 검증·월 예산 대조·실제 Python·100명 두DB 재현성은 미완료다. photo가 있는 CREATE, period/dormancy/influence 및 ADJUST_ALLOCATION/CORRECT는 미지원이며 성공으로 대체하지 않는다. 현재 피드 실행은 LATEST이고 Python 응답을 만들지 않는다.


현재 실제 응답의 UUID 및 검증된 cursor 비교는 `response-normalization.md`를 따른다. `normalizedResponses()`는 부분 응답 projection이며 최종 bundle/DB state 정규화가 아니다.
