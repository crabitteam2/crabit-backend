# 일회용 재생 DB의 시작 연결 확인

직전 전체 회귀의 `SimulationPostgresClockIT.independentDatabasesDoNotShareTimeAndClosedDatabaseCannotBeReused`는 첫 DB 생성자의 최초 SQL 시각 확인에서 실패했다. 보존된 XML에는 `ConnectionFactoryImpl.enableSSL`의 `SocketTimeoutException: Read timed out`과 fallback 인증 연결의 `Connection reset`이 함께 있다. 컨테이너 시작 로그 이후, Flyway 이전의 실패다. 이 기록은 연결 단계의 실패 위치를 보여 주며 Docker 네트워크나 PostgreSQL의 근본 원인을 확정하지는 않는다.

`SimulationDatabaseReadiness`는 새 일회용 로컬 DB의 최초 읽기 전용 시각 확인에만 적용된다. JDBC 연결을 얻지 못했고 원인 체인에 socket timeout/refusal/reset이 있는 경우 200 ms 간격으로 최대 세 번 시도한다. 마지막 실패는 그대로 전달한다. 인증 오류와 SQL 시각 불일치 등 다른 오류는 즉시 실패한다. 대기 중 인터럽트가 발생하면 인터럽트 상태를 보존하고 시작을 중단한다. 각 연결의 시간 제한은 기존 JDBC 드라이버 설정을 따른다.

Flyway와 후속 시각 검사, 트랜잭션, 도메인 명령은 이 재시도 바깥에 있다. 초기 확인이 끝나기 전에는 마이그레이션이나 도메인 쓰기를 시작하지 않는다. 생성자 실패 시 기존 컨테이너 정리가 실행된다. 원격 접속 설정을 받지 않고 기존 Unix Docker 및 loopback 검사도 유지한다. 이 클래스는 simulation source set에만 속하며 제품 bootJar에 포함되지 않는다.

검사는 일시적 실패 후 성공, 최대 횟수 초과, 인증/시각 오류의 즉시 중단, 인터럽트 보존을 다룬다. 실제 PostgreSQL의 독립 시계·rollback과 선택 백업 검사도 함께 실행한다. 정확한 전체 회귀 결과와 파일 해시는 `verification-2e2cb1.json`을 따른다. 성공한 회귀는 이번 변경의 검증이며 앞선 실패 기록을 덮어쓰거나 근본 원인이 해결됐다고 단정하지 않는다.

전체 domain import와 대상/manifest/revision 바인딩, Owner 신원 및 외부 콘솔 보존 창, 원자적 apply/restore는 여전히 미완료다. 데이터 저장소의 100명 생성·두 DB 재생 및 프런트엔드 대표 사용자 검증도 별도 액션에서 완료해야 한다. 이 변경은 실제 데모 적용이나 게이트 승인이 아니다.
