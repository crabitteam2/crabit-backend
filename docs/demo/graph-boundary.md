# 교체 후보 행의 FK 경계 검사

`SimulationCommandDispatcher.inspectGraphBoundary(export, selection)`은 현재 runtime에서 확인한 catalog와 관계형 export의 기존 검증을 통과한 뒤, 각 테이블에서 명시한 정확한 primary key만 선택한다. 없는 테이블·없는 행·중복 선택·잘못된 열·null key·변경된 catalog를 거부한다. 생략한 테이블과 행은 보존 대상으로 분류하며 참조를 따라 선택 범위를 자동 확장하지 않는다.

보고서는 38개 테이블 각각의 선택/보존 행 수와 digest, 선택→보존 및 보존→선택 FK 경계를 담는다. 실제 catalog의 복합 FK와 SQL MATCH SIMPLE/FULL의 null 의미를 사용한다. 경계에는 테이블·열·방향과 양 끝 primary key의 digest만 기록하고 UUID 및 원본 행 값은 출력하지 않는다. 행과 요청 순서에 영향을 받지 않으며 같은 행 수의 내용 변경은 partition digest에 반영된다. 원본 export는 기존 메모리 입력으로만 사용하며 새로운 DB 원문/비밀키/미디어 export를 추가하지 않는다.

`foreignKeyClosed`는 선택된 행 집합에 SQL FK 경계가 없는지만 뜻한다. 빈 선택과 전체 선택도 이를 만족할 수 있다. JSON 내부 참조, FK가 없는 이력·행동 참조, 비관련 학원/Owner identity 보호, 공유 부모에 대한 유지 정책, 지연 제약·삭제 순서·불변 trigger의 관리 예외를 증명하지 않는다. 경계가 있다고 임의로 참조를 삭제하거나 공유 부모를 교체해서도 안 된다. `readyForApplication`은 항상 false다. 새 CLI/HTTP endpoint, DB 연결 URL, 적용/복원 권한이나 실행기는 없다.

실제 PostgreSQL 통합 검사는 두 학생의 JOIN과 한 학생의 실제 CREATE 명령을 수행한다. 학생만 선택했을 때 보존 계좌의 역참조, 계좌만 선택했을 때 학생 방향 참조 및 위시의 복합 역참조를 확인한다. 전체/빈 선택, 입력 순서 변경, 누락/중복/위조 selection 및 catalog 거부를 검사한다. 검사 전후 DB 지문과 원본 export가 그대로인지 비교한다. 출력 증거는 `build/simulation-graph-boundary/`에 있으며 정확한 명령·결과·해시는 `verification-ec8281.json`을 따른다.

전체 typed relational import 계약, 정확한 학생/계좌/학원 대상과 FK 외 참조를 포함한 graph 선정, backup/manifest 및 revision 바인딩, 원자적 selective apply/restore, 복제 DB 실패/재적용/동시성/복원 검증은 계속 필요하다. 이 변경은 데이터 생성 저장소의 100명 정책·canonical/CSV 조립이나 프런트엔드 대표 UI 검증을 수행하지 않는다. 현재 demo에 적용하지 않았고 Feature Run/action state와 승인 OpenAPI bytes를 변경하지 않았다.
