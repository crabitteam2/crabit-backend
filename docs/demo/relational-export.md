# 실제 재생 DB 관계형 export

`SimulationRelationalState`는 simulation 소스셋의 로컬 진단 도구다. 실행 중인 dispatcher가 소유한 새 PostgreSQL에서만 사용하며 외부 DB 연결이나 SQL 입력을 받지 않는다. 승인된 OpenAPI 및 번들 입구의 `state/export.json` 현금 계약은 유지한다.

성공한 재생 직후 `READ ONLY REPEATABLE READ` 트랜잭션으로 38개 고정 테이블의 열, PK/UNIQUE/FK 정의와 실제 행을 한 번에 읽는다. 현재 V1..V21을 적용한 public 테이블 목록과 다르면 중단한다. `flyway_schema_history`와 `relationship_cursor_key`는 목록 확인에만 쓰고 내용을 읽지 않는다. 사진 관련 네 테이블과 application journal은 현재 지원하지 않으므로 행이 있으면 내용을 읽기 전에 거부한다. 비어 있는 테이블도 결과에 포함한다.

`state/relational-catalog.json`에는 실행 DB에서 읽은 열·제약 정의를, `state/relational.json`에는 dataset ID, catalog digest와 테이블별 실제 행을 기록한다. JSON 키와 행을 canonical 순서로 정렬하며 원본 UUID, timestamp, JSONB payload를 유지한다. 이 파일은 UUID 정규화된 canonical bundle이나 import 입력이 아니다. 원본 raw 요청/응답도 그대로 보존한다. 기존 journal의 새 디렉터리·CREATE_NEW·symlink 거부 동작을 그대로 사용한다.

파일을 보존한 뒤 DB와 독립적으로 열 집합/NOT NULL, PK 및 UNIQUE 제약, 복합 FK와 PostgreSQL MATCH SIMPLE/FULL null 의미를 검사한다. FK 대상 인덱스를 메모리에 만들어 실제 export 행끼리 대조한다. dispatcher가 보관한 catalog와 바뀐 정의를 거부하므로 입력에서 FK를 제거해 검사를 생략할 수 없다. 실제 JOIN을 완료한 학생·계좌와 단일 학원 집합, BUILDING dataset 및 시뮬레이션 계좌/현금/대표 행의 dataset 연결도 대조한다. 불일치가 나도 DB나 캡처한 행을 수정하지 않는다.

`replay-observation.json`에 `relationalStateExported`, `relationalReferencesVerified`, 파일 해시와 테이블/행/제약/검증 참조 건수를 기록한다. `relationalReferencesVerified`는 **SQL로 선언된 FK** 검사다. FK 없는 방문 이력·JSONB 내부 참조·시간 구간·CHECK 표현식·부분 UNIQUE 인덱스 의미를 모두 검사했다는 뜻은 아니다. 해당 행은 export에 포함되어 후속 도메인 검증에 사용할 수 있다. 다른 현금·배분·관측 oracle도 계속 실행한다.

실제 money replay와 100명/400명령 두 독립 DB 재생 테스트에서 export를 실행한다. 별도 실제 DB 검사는 관측 부모 누락, 계좌 복합 FK 변조, 중복 키, 열/NOT NULL 위반, 알 수 없는 테이블, 미가입 학생 추가 및 catalog 변조를 거부하고 검사가 DB를 바꾸지 않음을 확인한다. 정확한 실행 결과는 `verification-81098.json`을 따른다.

전체 typed id-map·관계형 UUID 정규화·legacy CSV·ADJUST/CORRECT·기간/annotation·전체 도메인/월 예산 검증·Python 추천/recap·100명 전체 행동 이력·selective apply/restore·실제 UI와 Owner 콘솔 보존은 아직 남아 있다. 이 도구는 `fullDatasetValidationPerformed=false`, `readyForApplication=false`를 유지하며 현재 데모에 적용하지 않는다.
