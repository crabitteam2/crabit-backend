# 로컬 백업의 동시 쓰기 차단

`backupSelection`은 소유한 일회용 PostgreSQL에서 40개 공개 테이블을 이름순으로 `SHARE ROW EXCLUSIVE` 잠근다. 잠금 획득 후 READ COMMITTED에서 지문을 다시 읽고 요청한 지문과 비교한다. 이전 writer가 잠금 대기 중 commit했다면 그 변경을 읽어 stale backup을 거부한다. 도메인 export, 검증, 선택 행 직렬화, 마지막 지문 비교는 같은 트랜잭션과 잠금 안에서 수행한다.

일반 SELECT는 허용한다. INSERT/UPDATE/DELETE와 해당 테이블의 DDL은 잠금이 끝날 때까지 대기한다. 잠금 대기 한도는 2초, 트랜잭션 한도는 30초다. 충돌을 자동 재시도하지 않으며, 정상 종료·예외·timeout·지문 불일치에서 트랜잭션이 끝나면 잠금을 해제한다. callback에서 발생한 transactional 행 변경은 지문 불일치로 rollback한다. 공개 API는 callback이나 SQL, 외부 DB 주소를 받지 않고 기존 backupSelection만 제공한다.

기존 read-only 지문 API는 그대로 repeatable-read를 사용한다. 지문 계산은 UTC로 고정하고, 도메인 export 직전에 원래 JDBC 세션 시간대를 복원하여 이력 JSON의 시각 문자열과 원본 행이 같은 표기를 유지한다. 마지막 지문은 다시 UTC로 계산한다. 잠금 트랜잭션은 PostgreSQL LOCK 규칙상 read-only가 아니지만 백업 경로는 행 변경 명령을 실행하지 않는다. 트리거, migration, sequence, DB 시각을 변경하거나 비활성화하지 않는다. cursor 키는 기존처럼 서버 내부 해시에만 포함한다.

이 잠금은 기존 40개 테이블의 행 변경과 테이블 DDL에 대한 로컬 보존 창이다. 독립적인 nextval/setval, 새 테이블 생성, 함수 교체 또는 외부 Owner 콘솔까지 잠그는 기능은 아니다. sequence·schema 전후 변화를 감지할 수 있으나 운영 관리자와의 완전한 배타성이나 ABA 방지를 증명하지 않는다. target identity 매핑, 실제 공개 테이블 import/apply/restore, 운영 maintenance 경계는 계속 미구현이다. 백업은 여전히 restoreSupported=false와 readyForApplication=false다.

실제 PostgreSQL 검사는 40개 granted lock, 별도 연결 SELECT 허용, UPDATE/ALTER TABLE의 SQLSTATE 55P03, rollback 후 writer 재개, 선행 writer commit 후 stale 거부와 callback 미호출, 2초 lock timeout 후 잠금 해제를 검사한다. 기존 백업·지문 테스트도 함께 실행한다. 정확한 실행 결과는 verification-7c1c6c.json을 따른다.
