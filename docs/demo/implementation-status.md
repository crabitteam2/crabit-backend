# Backend handoff: demo-student-behavior-simulation

현재 지원 범위는 이 문서와 [실행 인터페이스](backend-handoff.md)를 기준으로 읽는다. 과거 액션의 “최신”, “미구현” 문구는 [이전 상태 기록](implementation-status-history-before-c3f087.md)에 보존했다. 주제별 verification 파일은 해당 시점의 증거이며 전체 제품 완료 선언이 아니다.

## 구현된 연결

- `simulationSession`: 지속되는 일회용 DB에서 실제 Java 명령/Python 추천 결과를 한 단계씩 반환한다. 정책은 반환된 순서·금액·거절을 보고 다음 이벤트를 결정할 수 있다. 입력 intent와 실현된 events를 따로 보존한다.
- `simulationRun`: 확정된 canonical bundle 이벤트를 새 DB에서 엄격히 재생한다. 현금과 위시 배분, 관측·원장·조정, 공유/관계/노출/방문/클릭, 실제 Python 피드·리캡, 독립 대조 및 정규화 export를 연결한다. 승인 schema의 25종 이벤트를 사용한다.
- `simulationImport`: 관계형 replay export를 대상 카탈로그로 검증하고, 현재 Owner 그래프를 보존한 별도 적용 후보·백업을 만든다. 대상 PostgreSQL의 실제 타입으로 후보를 정규화한다. 원자적 dry-run/적용/복원과 journal read-back을 실행한다.
- demo runtime: APPLIED 매핑의 비Owner 99명은 합성 현금 provider를 사용한다. Owner는 기존 외부 provider를 사용한다. grade-3~grade-6 네 대표의 토큰→실제 신원 매핑과 시작 시 검증을 제공한다. 합성 99명의 완전히 포함된 기간에는 APPLIED 데이터셋의 수집 시작을 사용하며, Owner/다른 계좌/전역 수집 시작은 유지한다.

## 현재 검증과 적용 상태

`verification-c3f087.json`은 이번 통합 handoff의 명령·테스트 결과·hash를 기록하고, `action-files-c3f087.json`은 이번 액션의 변경 경로를 기록한다. 전체 검증 7분50초의 원본 XML/로그와 이후 Owner 이력·용량 한도 focused XML/로그는 서로 다른 디렉터리에 보존한다. 전체 검증은 마지막 두 delta 이전 소스이며, delta별 집중 검증 범위를 별도로 기록한다.

로컬 통합 테스트는 100명 매핑과 실제 지급·위시·배분·공유 행, 별도 대상 DB의 Owner provider 관측·원장·checkpoint·위시, dry-run rollback, 적용·중복 NO_OP·동일 건수 drift·복원을 다룬다. 실제 Python 추천을 받은 다음 행동을 확정하고 두 독립 DB에서 재생하는 세션 테스트도 포함한다. 이것은 전체 기간의 100명 정책 데이터 실행이나 네 대표의 브라우저 검증을 대신하지 않는다. Owner 외부 콘솔에는 접근하지 않았으며 보존 확인을 주장하지 않는다.

코드는 컨트롤러 생성 backend worktree의 미커밋 변경이다. 기준 HEAD는 `67920c84d859969c218c057eab8021462390035d`다. 현재 demo 데이터 적용·배포·PR·컨트롤러 게이트 승인은 실행하지 않았다.

## 담당 범위와 후속 입력

| 담당 | 입력/실행 | 결과와 남은 책임 |
|---|---|---|
| BACKEND | 승인 schema, 실제 domain/Python 호출, target DB | 지속 세션, strict replay, 관계형 export, 원자적 적용·복원, 대표 신원/provider 인터페이스 |
| DATA | 100명/학년별25명/80명+20명/기간/정책/seed | 세션 결과 기반 전체 행동 생성, 실제 feed·recap close, fixed bundle 조립, 두 DB 전체 재생 비교, canonical·legacy CSV·전체 검증 보고서 |
| DATA / 적용 실행자 | 검증된 relational export, 현재 target/console baseline, exact controller action | 로컬 PREPARE·backup·dry-run 검증, controller-bound 적용, DB journal/read-back와 별도의 외부 Owner 콘솔 read-back, 복원 증거 |
| FRONTEND | personas.json, 서버의 grade 토큰, 기존 계좌/위시/feed/recap API | grade-3~6 전환과 실제 응답 연결, 네 대표별 API/UI/DB 확인 |

DATA가 생성하는 역사 속 Owner는 현재 대상 Owner와 구분한다. 적용 후보에는 현재 Owner의 신원뿐 아니라 잔액 관측·원장·checkpoint·위시·공유·관계·방문 등의 필요한 기존 그래프가 남는다. 로컬 Owner의 합성 현금/관측/위시는 덮어쓰지 않는다. 제거할 로컬 Owner 객체를 다른 학생의 저장 행이 참조하거나, 투영 과정에서 타인의 행동을 조용히 제거해야 하는 교차 관계가 있으면 가져오기를 거부한다. DATA는 이 제한을 정책/번들 설계에 반영하고 원본 로컬 결과와 대상 후보의 차이를 명시한다.

## 용량 계약 변경

필수 1,120회 주간 마감의 RAW만으로 기존 파일 수/manifest 상한을 넘는다는 직접 확인에 따라 이번 액션에서 파일 262,144개, manifest 128 MiB, artifact 128 MiB, artifact 합계 8 GiB로 확장했다. 기존 bounded reader/writer/schema의 scalar 한도를 일치시켰고, 상한 초과는 거부한다. 전체 DATA 실행을 자르거나 원본을 버리지 않는다. 전체 검증을 반복하지 않고 기존 BundleReader 테스트의 한도 일관성/필수 마감 metadata/합계 경계 회귀로 검증했다. 실제 전체 용량 측정은 DATA가 수행한다.

## 경계

최종 `readyForApplication`과 전체 데이터 품질은 DATA의 완전한 실행 및 컨트롤러 게이트가 판단한다. 세션 종료나 부분 replay, SQL dry-run 성공만으로 승격하지 않는다. replay media 생성은 지원하지 않으며 기존 media/키는 importer에서 원본을 읽거나 쓰지 않고 DB 내부 digest로 보존을 대조한다. 외부 콘솔 baseline digest는 실행자가 제공하는 바인딩이며 외부 검증 자체가 아니다. 이 backend action은 DATA/FRONTEND 산출물을 대신 만들지 않는다.
