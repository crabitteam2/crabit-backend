# 로컬 검증 전후의 DB 보존 지문

`SimulationPreservationFingerprint`는 자신이 생성한 일회용 `SimulationPostgresClock` DB만 받는다. 연결 URL·credential·원격 모드·SQL 입력·웹 endpoint가 없다. repeatable-read/read-only 트랜잭션에서 UTC, ISO 날짜 표기를 고정하고 public의 예상 40개 테이블을 확인한다. 알 수 없는 테이블이나 기존 ambient transaction은 거부한다.

각 행의 PostgreSQL JSON을 서버 내부에서 SHA-256으로 해시하고, 고정 길이 행 해시를 정렬해 테이블별 건수와 지문을 만든다. 중복 행의 개수는 유지한다. 같은 행 수라도 값이 바뀌면 차이가 난다. `relationship_cursor_key`, 미디어 작업과 Flyway 이력도 검사하지만 JDBC 결과에는 건수·지문만 반환한다. 키, 미디어 경로, 원본 행이나 함수 본문은 내보내지 않는다. 이 결과는 데이터 백업이 아니다.

열/default, 제약, 사용자 trigger와 활성 상태, public 함수 정의, index, sequence 설정의 지문을 따로 기록한다. sequence의 `last_value`와 `is_called`를 읽으며 값을 할당·재설정하지 않는다. 트랜잭션 롤백 뒤 남은 sequence 증가도 차이로 보고한다. sequence는 MVCC 대상이 아니므로 처음/마지막 관측이 다르면 실패한다. 이중 관측은 외부 동시 쓰기 정지나 잠금을 대신하지 않으며, 전체 PostgreSQL 설정·role·extension·외부 서비스 보존을 주장하지 않는다.

replay는 마지막 사건 이후, 독립 export/검증 전에 첫 지문을 얻는다. 정상 검증이 끝나면 두 번째 지문과 차이를 `replay-observation.json`에 기록하고 달라지면 `REPLAY_VALIDATION_DATABASE_DRIFT`로 실패한다. 검증이 중간 실패하면 이전 지문과 false 상태만 남을 수 있다. 비교는 같은 생성 DB 이름에 한하며, 서로 다른 재생 DB의 논리 재현성 digest와 구분한다. caller가 제공한 지문을 신뢰하여 import 권한을 부여하는 경로는 없다.

실제 PostgreSQL 검사는 행 수를 유지한 닉네임 변경, 비밀키 변경, 별도 미디어 작업, 이력 수집 시작값 변경, 롤백 후 sequence 증가, 스키마 default 변경, 미등록 테이블, 다른 DB와 ambient transaction을 포함한다. replay 통합 검사는 실제 명령/거절/독립 검증이 끝난 뒤 전후 지문과 저장된 관측 파일을 확인한다.

현재 승인된 `state/export.json`은 cash-only다. 전체 relational import 계약, 정확한 대상 graph 및 역참조 분할, 백업과 selective apply/restore executor, 운영 쓰기 정지와 drift 바인딩, 외부 Owner 콘솔 read-back은 아직 필요하다. 이 도구는 그 구현을 대체하지 않으며 `readyForApplication=false`를 유지한다. 현재 액션의 검증은 `verification-837464.json`을 따른다.
