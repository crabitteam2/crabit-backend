# 일회용 PostgreSQL 재생 시계

`SimulationPostgresClock`은 로컬 Docker Unix socket으로 새 PostgreSQL 컨테이너를 만들고 Flyway V1..V21을 적용한다. 외부 JDBC URL·기존 DB·volume·재사용 옵션을 입력받지 않는다. DB port는 127.0.0.1의 임의 port에만 게시하며 임의 생성한 로컬 자격증명은 데이터 번들에 기록하지 않는다. 클래스와 Docker 지원 코드는 simulation source set에만 있고 배포 bootJar에는 포함되지 않는다.

`./gradlew simulationClockCheck --console=plain`은 고정한 PostgreSQL 기반 digest와 libfaketime package 버전으로 로컬 이미지를 빌드하고, 새 DB의 실제 SQL 시각과 주입 가능한 Java Clock을 비교한 뒤 컨테이너를 종료한다. 마지막 JSON은 CLOCK_VERIFIED이지만 fullDatasetReplayPerformed=false, readyForApplication=false다. 실제 관측값은 clock-observation.json에 저장했다. `simulationTest`도 동일한 이미지 빌드가 선행되므로 Docker 및 최초 package 다운로드가 필요하다. 이미지를 push하거나 기존 서비스를 시작·변경하지 않는다.

PostgreSQL 초기화와 임시 시작은 실제 시각으로 수행한다. 초기화 완료 marker 이후 최종 postgres 프로세스와 자식만 libfaketime을 preload하고, migration 전에 2026-05-31T15:00:00Z를 SQL로 확인한다. 호스트 시계와 JVM 시스템 시계는 변경하지 않는다. monotonic clock과 파일 stat 시각을 가짜 시각으로 바꾸지 않으며, DB wall-clock은 단계 사이에서만 전진한다. 형식은 UTC 마이크로초이고 캐싱을 끈 timestamp 파일을 같은 컨테이너 안에서 atomic rename으로 교체한다. 설정 근거는 [libfaketime 공식 문서](https://github.com/wolfcw/libfaketime#readme)의 timestamp file, stopped clock 및 monotonic caveat다. 실제 사용 package는 0.9.10+2024-06-05+gba9ed5b2-0.6이며 최신 upstream 동작을 검증 결과로 대신하지 않는다.

`executeAt`은 한 번에 하나의 동기 callback을 트랜잭션에서 실행한다. [start,end) 밖 시각, 역행, sub-microsecond 입력과 중첩 단계를 거부한다. 같은 시각의 여러 순차 단계는 허용한다. 단계 시작 전 새 연결, 트랜잭션 내부 전후 및 commit 이후에 clock_timestamp/current_timestamp/statement_timestamp가 정확히 같은지 검사한다. commit 시 실행되는 checkpoint trigger가 완료된 뒤에만 다음 단계로 넘어간다. callback 실패는 DB 변경을 rollback하지만 시각은 되돌리지 않는다. 시계 쓰기·read-back이 불확실하면 해당 인스턴스를 재사용하지 않는다. callback은 JDBC·Clock을 다른 thread나 callback 밖으로 넘기지 않아야 한다. 이후 전체 replay runner는 이를 내부 실행 경계로 사용하고 원래 서비스의 트랜잭션·권한·idempotency 조건을 유지해야 한다.

실제 PostgreSQL 테스트는 migration의 feed history 시작 시각, wish 변경의 valid_from/valid_to, commit 후 financial checkpoint applied_at, 정상 초기 상태의 HistoricalBalanceQueryService readSnapshotAt, 마이크로초·cutoff·역행·rollback·두 독립 DB 시각 격리를 검사한다. wish 금액을 직접 바꾸는 부분은 timestamp trigger 검사 전용이며 실제 도메인 명령 실행 또는 유효한 재생 데이터로 주장하지 않는다. 원장 없는 금액을 정상 조회 데이터로 승인하지 않는다.

남은 작업은 모든 사건의 실제 서비스 실행, Java Clock을 Spring 서비스에 연결하는 runner 구성, 원래 도메인 서비스의 트랜잭션 경계, 시점별 Python feed/recap 호출·저장 및 전체 100명 데이터의 두DB 재현성이다. 이 기반만으로 이전 일반 회귀에서 관측한 feed_source_history 시각 역행 원인을 수정했다고 주장하지 않는다. 기존 live migration과 도메인 코드는 변경하지 않았다.

## 실제 서비스 연결 (act-269e2923099eb1c5bee5d7b653fb4c29)

`SimulationDomainRuntime`은 위의 일회용 DB를 소유하고 별도의 비웹 Spring context에 실제 JPA repository·도메인 서비스·트랜잭션 proxy와 동일한 Clock을 등록한다. 일반 애플리케이션 bootstrap과 profile 파일을 읽지 않으며 HTTP 서버, seed 초기화, scheduler, 외부 HTTP 잔액 제공자는 등록하지 않는다. 외부 DB URL을 입력받는 생성자도 없다. 현금 제공자는 BUILDING 데이터셋의 실제 지급/소비 원장과 캐시를 대조하고 SIMULATION 출처를 반환한다. 이 로컬 재생 제공자는 Owner도 가상 원장으로 처리한다. APPLIED에서 사용하는 기존 demo Owner 라우팅과 별개다.

`executeServicesAt`은 시계를 고정한 동안 원래 서비스가 자신의 트랜잭션을 시작·커밋하도록 한다. 전체 사건을 외부 트랜잭션으로 감싸지 않는다. 특히 실제 WishFundMovementService의 PRE_DEPOSIT 관측은 배분보다 먼저 커밋하므로 배분이 잔액 부족으로 실패해도 남는다. 성공·예외 모두 SQL 시계를 다시 읽으며, 현재 스레드의 바깥 트랜잭션은 거부한다. 실행 중 재진입/close를 거부하고 종료 후 재사용하지 않는다. callback 및 그 services/JDBC/Clock을 다른 스레드나 callback 밖으로 전달하지 않는 내부 동기 API다. 일반 서비스의 JDBC timeout은 15초, JPA transaction timeout은 30초다.

실제 PostgreSQL의 Owner/비Owner 두 사례에서 지급 → 관측 → Wish 생성 → 같은 요청 재사용 → 배분 → 잔액 부족 거절 → 카드 소비 → 불일치 관측 → 출금 조정 → 포기를 실행한다. 도메인 시각과 SQL feed history/checkpoint 시각, 실패 후 관측의 커밋, 변하지 않은 가상 현금, 조정 종료, 포기 직전 금액과 타인 계좌 거부를 검사한다. 단일 테스트 시나리오 두 건이며 전체 100명 재생·논리 ID 정규화 재현성 또는 Python/공개 API 검증을 뜻하지 않는다.

`SimulationDomainRuntime`은 번들 사건을 해석하는 최종 실행기가 아니다. admission 결과와 전체 JOIN/사건 dispatcher, 기간 마감·Python 호출, raw 결과·상태 export를 연결하는 작업이 남아 있다. 기존 simulationRun은 아직 추가하지 않았다. 기존 일반 서비스/migration/OpenAPI/schema bytes는 이 액션에서 바꾸지 않았다.
