# PostgreSQL 임시 테이블에서의 typed import 사전 검사

`SimulationCommandDispatcher.stageRelationalState(byte[])`는 읽기 검증을 통과한 관계형 export를 실제 PostgreSQL 타입으로 임시 테이블에 적재하고 다시 읽어 비교한다. dispatcher가 소유한 일회용 로컬 replay DB 안에서만 실행되며 외부 연결이나 SQL을 입력받지 않는다. 공개 도메인 테이블에는 적재하지 않는다.

38개 테이블을 transaction-local 이름으로 만들고 현재 migration의 칼럼 타입, NULL, CHECK, 고유/부분 고유 index를 `LIKE ... INCLUDING CONSTRAINTS INCLUDING INDEXES`로 복제한다. 값은 모두 PreparedStatement parameter로 전달한다. uuid·문자열·날짜·microsecond timestamptz·boolean·int4·int8·jsonb를 지원한다. int8을 부동소수점으로 바꾸지 않으며 SQL처럼 보이는 문자열도 값으로 유지한다. timestamptz의 offset 표기는 동일 UTC instant로 정규화해 대조한다. 나머지 값이나 행의 중복을 제거·보정하지 않는다. staging 대조용 해시는 키를 정렬하고 정확한 JSON 수치를 유지하며, 안전 정수 제한이 있는 canonical bundle 해시를 변경하지 않는다.

FK는 기존 관계형 입력 검증 후 실제 PostgreSQL에서도 검사한다. 38개 임시 테이블의 행 적재가 모두 끝난 뒤 현재 카탈로그의 FK를 추가한다. 참조의 양쪽을 `pg_temp`로 한정하고 복합 칼럼 순서와 MATCH SIMPLE/FULL을 유지한다. 기본 ADD CONSTRAINT 검증으로 기존 적재 행 전체를 검사하므로 테이블 적재 순서나 순환 참조에 의존하지 않는다. 공개 테이블에 부모 행이 존재해도 임시 부모 행이 빠지면 SQLSTATE 23503으로 거부한다. 반환 report의 foreignKeys는 SQL 제약 추가가 모두 성공한 개수다.

임시 테이블에는 도메인 트리거·default·identity/sequence를 복제하지 않는다. 실제 도메인 insert 트리거가 추가 생성하는 이력·대표 행을 사전 검사 중 중복 생성하지 않고, 공개 sequence를 증가시키지 않기 위해서다. 따라서 임시 적재 성공은 공개 테이블의 trigger 동작, 최종 domain 불변식, 대상 DB의 FK closure, 전체 import 성공을 증명하지 않는다. FK의 삭제/갱신 cascade와 deferrable 실행 시점은 복제하지 않는다. 이 단계는 완성된 행 집합의 참조 유효성 검사이며 운영 변경 재생기가 아니다.

하나의 repeatable-read transaction에서 현재 로컬 catalog를 재확인하고 임시 테이블을 생성·적재·read-back한다. 성공 시 ON COMMIT DROP, 예외 시 rollback으로 임시 DDL과 행을 없앤다. 성공과 실패 모두 전후 공개 DB 행·sequence·schema fingerprint가 같아야 한다. 반환 값은 table/row 수와 source/read-back digest, `foreignKeys`, `publicRowsWritten=false`, `readyForApplication=false`다. 원본 bytes를 수정하지 않는다.

실제 JOIN → GRANT → CREATE → DEPOSIT으로 만든 관계형 행의 동일성을 검사했다. 실제 PostgreSQL의 varchar 길이와 CHECK 위반이 입력 타입/FK 검사 이후 실패하더라도 원본 DB가 보존되며 이후 정상 호출이 성공하는지 검증한다. UTC/+09:00 동치와 Long.MAX_VALUE의 손실 없는 SQL 왕복도 검사한다. 큰 version 입력은 SQL 전송 검사용이며 유효한 domain history로 승인하지 않는다.

공개 domain import, 대상 신원 변환, 정확한 선택·보존 범위, backup/manifest/target/revision 바인딩과 원자적 apply/restore는 아직 남아 있다. 100명 전체 데이터 생성·두DB 재현성·canonical/CSV·대표 UI와 현재 demo 적용은 별도 작업이다. 승인된 API/schema와 migration, 외부 Owner 콘솔 및 Feature Run/action state는 이번 액션에서 변경하지 않았다.

최신 FK 검사 액션의 실제 테스트·파일 digest는 `verification-7bb590.json`과 `action-files-7bb590.json`에 기록한다. 최초 staging 구현의 결과는 `verification-38b89a.json`에 보존되어 있다.
