# Backend handoff: demo-student-behavior-simulation

현재 지원 범위는 이 문서와 [실행 인터페이스](backend-handoff.md)를 기준으로 읽는다. 과거 액션의 “최신”, “미구현” 문구는 [이전 상태 기록](implementation-status-history-before-c3f087.md)에 보존했다. 주제별 verification 파일은 해당 시점의 증거이며 전체 제품 완료 선언이 아니다.

## 구현된 연결

- `simulationSession`: 지속되는 일회용 DB에서 실제 Java 명령/Python 추천 결과를 한 단계씩 반환한다. 정책은 반환된 순서·금액·거절을 보고 다음 이벤트를 결정할 수 있다. 입력 intent와 실현된 events를 따로 보존한다.
- `simulationRun`: 확정된 canonical bundle 이벤트를 새 DB에서 엄격히 재생한다. 현금과 위시 배분, 관측·원장·조정, 공유/관계/노출/방문/클릭, 실제 Python 피드·리캡, 독립 대조 및 정규화 export를 연결한다. 승인 schema의 25종 이벤트를 사용한다.
- `simulationImport`: 관계형 replay export를 대상 카탈로그로 검증하고, 현재 Owner 그래프를 보존한 별도 적용 후보·백업을 만든다. 대상 PostgreSQL의 실제 타입으로 후보를 정규화한다. 원자적 dry-run/적용/복원과 journal read-back을 실행한다.
- demo runtime: APPLIED 매핑의 비Owner 99명은 합성 현금 provider를 사용한다. Owner는 기존 외부 provider를 사용한다. grade-3~grade-6 네 대표의 토큰→실제 신원 매핑과 시작 시 검증을 제공한다. 합성 99명의 완전히 포함된 기간에는 APPLIED 데이터셋의 수집 시작을 사용하며, Owner/다른 계좌/전역 수집 시작은 유지한다.

## 현재 검증과 적용 상태

2026-09-12 DATA 전체 실행에서 발견된 용량·응답 부재·알림 소속 문제는 사용자가 명시적으로 허용한 backend 수정 범위에서 보완했다. 이전 `verification-c3f087.json`과 `action-files-c3f087.json`은 해당 액션 시점의 증거로 유지한다. 현재 수정은 기준 HEAD `cfd4e400b865a8fbe99b2f27f638b05edf1cfa0e` 이후 미커밋 변경이다.

100명/18,396개 이벤트의 원본 bundle은 13,862,132,635바이트이며 수정 reader에서 768 MiB heap으로 입장을 통과했다. `crabit-data/docs/demo/artifacts/admission-07`이 실제 측정 증거다. RAW 바이트와 경로는 유지한다. 호출하지 않은 NOT_ELIGIBLE 리캡의 예약 응답 경로는 저장된 상태·입력·기간과 대조하며, 응답이 없었던 FEED 요청은 실제 LATEST fallback 증거로 검증한다.

V22는 V21 checksum을 유지하면서 `mismatch_notification_outbox.adjustment_case_id`의 부모 소속을 보완한다. 전체 복원 후보의 정확한 행 차이는 JSONB 집합 차이로 계산해 반복 배열 검색의 timeout을 제거했다. 전체 DATA의 로컬 적용·복원은 `crabit-data/docs/demo/artifacts/application-08/verification.json`에서 PASS다. 실제 Stable Demo의 사용자 승인 백업을 새 로컬 PostgreSQL에 복원한 검증도 V20→V22, 100명 적용, 현재 Owner 위시 107개 및 기존 학생 4명 보존, 도메인 전체 행·sequence 복원까지 통과했다. 대상 DB는 시뮬레이션 cohort 100명과 보존된 기존 학생을 합쳐 104명이 된다.

최신 전체 검증은 `verification-final-suite-20260913.json`이다. 최종 UUID 할당·deadline·가져오기 수정까지 포함한 일반 테스트 698개(실패/오류 0, 기존 선택적 parity 검사 1개 skip), simulation 테스트 517개(실패/오류/skip 0), bootJar/classpath가 8분 44초에 exit 0으로 완료됐다. 상위 실행자의 결과를 이 액션에서 다시 읽어 보고서 SHA-256 `cf1c1d58122d158c822408a88a524c51626573d5d730f89ad5024ebb6d0a19f5`, XML 해시, 현재 source overlay, 로그와 jar를 대조했다. 실행 소스는 추가 변경하지 않았다. 이전 698/515·8분 31초의 `verification-runtime-20260913.json`은 당시 증거로 보존한다. 전체 suite와 실제 18,396개 이벤트의 두 재생은 별도 검증이다.

두 전체 재생의 05 실행은 첫 DB의 3,215개 이벤트 처리 뒤 e3216에서 PostgreSQL connection reset으로 실패했다. Docker를 재개한 06 실행은 7,987개 처리 후 e7988에서 원본 추천순과 새 최신순이 달라져 중단됐다. 새 page 증거의 `pythonInvoked=false`는 500ms 준비 예산 내에 HTTP 단계까지 도달하지 못했음을 보여 준다. 두 실행 모두 둘째 DB는 시작하지 않았고 실패 증거는 유지한다. 피드 입력 조립에 요청 내 작성자·계좌·월별 통계 및 제목 분류 재사용을 추가했다. 반복 가능한 동일 DB snapshot/같은 as-of 안에서만 재사용하며 다른 완료 월과 다음 요청은 분리한다. 500ms 제품 제한은 유지한다.

07도 5,067개 처리 후 e5068에서 실제 Python 응답을 deadline 안에 받지 못해 LATEST로 전환됐고 엄격한 기대값 비교가 중단됐다. 호스트의 벽시계 지연을 기능 결정성으로 혼동하지 않도록 fixed replay에만 30,000ms의 제한된 시간 예산을 명시적으로 주입한다. serving 및 discovery 기본값은 500ms다. serving 생성자는 500ms factory를 유지하며 runtime 환경변수로 이를 늘리는 경로는 없다. `SimulationReplayRun`의 선택적 비웹 환경변수만 500 또는 30000을 허용하고 관측 파일에 실제 예산과 `servingLatencyPolicyValidated=false`를 기록한다.

`scripts/demo/fixed-replay-with-resources.py`는 원본 e17164의 실제 응답 부재를 해당 recommendation_at에만 새 실제 Python 응답을 35초 보류해 재현한다. 새 upstream 요청·응답과 주입 내역을 별도 보존하며 예상 순서를 바꾸거나 원본 응답을 만들어 넣지 않는다. 30초 재생 timeout과 정상 실제 Python 전달은 `transport-probe-02`에서 확인했다. 기능 재생 결과는 서비스의 500ms 성능 증거가 아니다. 확정된 새 RAW는 동일 바이트 검증 후 낮은 프로세스 우선순위로 투명 압축하며 여유 공간 3 GiB 미만에서 중단한다. 두 결과가 완료되어 정규화 비교를 통과하기 전에는 재현 성공을 주장하지 않는다.

deadline 변경 뒤 기존 serving timeout·공유 카드 회귀, 실제 Python 응답을 700ms 늦춘 재생을 포함한 simulation feed 91개, PostgreSQL feed API 6개가 통과했다. 당시 jar `sha256:712eba5d2faf36c98c513133925712a79265e59e582bef31c764c33d1ba7d4ea`를 기본 serving 500ms로 실행한 `preview-04`에서도 네 대표의 HTTP/DB 대조와 관리 함수 권한 거절이 통과했다. 상세 증거는 `verification-deadline-20260913.json`이다. 앞선 698/515 전체 suite와 이 변경 이후 집중 검증을 새 전체 suite 실행으로 합쳐 표현하지 않는다.

08은 11,095개 처리 후 e11096에서 실패했다. 이 시점까지 1,381개 피드 HTTP 시도/응답은 모두 기록됐고, 직전 e11078 이체가 두 공유 카드를 같은 updated_at으로 수정했다. 서비스는 updated_at DESC, id DESC로 후보를 정렬하므로 재생기의 새 임의 UUID가 동률 후보의 입력 순서와 실제 Python의 최상위 카드를 바꿨다. 원본 요청의 두 후보 위치만 교환해 같은 차이가 생기는 것을 실제 Python ranking으로 재현했다. 원본 데이터나 서비스 정렬 규칙은 바꾸지 않는다.

고정 bundle 실행은 검증된 id-map에서 공유 카드 169개의 원본 UUID 할당을 재생한다. 생성 event/소유 계좌/UUID 중복/실제 할당 여부를 검사하며 새 카드의 상태는 기존 도메인 명령으로 생성한다. 일반 SharedCardIdGenerator는 임의 UUID를 유지하고, source ID 할당기는 simulation 전용이며 bootJar에 포함되지 않는다. 학생 등 다른 신원과 DB는 독립적으로 생성한다. 같은 수정 시각의 카드 두 개를 두 실제 DB/Python으로 재생해 순서와 정규화 일치를 검증했다. 이 변경 뒤 위시·공유 카드 69개 및 simulation feed 92개 검사가 실패·오류·skip 없이 통과했다. 첫 명령의 classpath 출력 경로 누락은 테스트/bootJar 완료 후 발생한 도구 설정 오류이며 경로를 명시해 성공했다.

현재 jar는 `sha256:faff8c765244057208a2aa2d13bb5bb5e51a0532360d3e93ba25cd35abd6b8e3`이다. `preview-05`에서 일반 역할의 네 대표 32개 새 HTTP 호출, SIMULATION DB read-back, 네 관리 함수 거절이 PASS다. 상세 증거는 `verification-card-identities-20260913.json`이다. `fixed-replays-09`는 두 독립 DB에서 각각 18,396개 이벤트를 완료했고 exit 0, 정규화 5종 바이트 일치, 실행 전후 DATA/백엔드 소스 해시 일치로 PASS다. `verification-full-replays-20260913.json`에 두 DB 신원과 원본 증거 해시를 기록했다. 30초 기능 재생 조건과 원본 카드 UUID 할당 보존 조건을 명시하며, 이 결과를 서비스 500ms 성능·외부 적용·프런트엔드 UI·컨트롤러 게이트 승인으로 확대하지 않는다. 08 실패 기록은 보존한다.

`live-target-20260913/clone-02/verification.json`은 승인된 실제 DB 백업의 로컬 적용·복원 PASS다. 외부 DB 쓰기는 없다. `scripts/demo/create-runtime-role.sql`과 `PrepareDemoPreview.java`는 관리 계정과 일반 앱 계정을 분리한 로컬 HTTP 검증 준비이며, 실제 runtime 검증이 끝나기 전에는 이 준비 파일 자체를 적용 증거로 취급하지 않는다.

외부 콘솔의 현재 DB를 읽어 적용 전 기준을 확보했다. 활성 시나리오는 버전 5, 7단계 소진, 남은 단계 0, 조회 이력 34개이며 관련 4개 테이블 전체 행의 SHA-256을 기록했다. 이는 적용 전 관측이다. 실제 대상 DB 적용·서비스 배포·네 대표 UI 검증·새 수정의 PR 및 컨트롤러 게이트 승인은 아직 실행하지 않았다.

## 담당 범위와 후속 입력

이 backend 액션은 HEAD `cfd4e400b865a8fbe99b2f27f638b05edf1cfa0e` 이후 사용자가 승인한 기존 미커밋 수정을 인수하고 문서를 정리했다. Feature Run revision 15, implementation, hold 없음은 읽기만 했으며 상태나 receipt를 쓰지 않았다. DATA는 `act-436b0642c07da619e645634fa22cf115`의 ready receipt 및 expected-tree 준비까지 확인됐고, 상위 실행자의 최신 Git read-back에서 HEAD는 `3b081b7a51523cdf8d24a6991b7e914e0fd8bc48`로 커밋 전이다. 두 전체 재생은 완료됐으며 DATA 커밋이나 외부 적용 완료로 표현하지 않는다.

| 담당 | 입력/실행 | 결과와 남은 책임 |
|---|---|---|
| BACKEND | 승인 schema, 실제 domain/Python 호출, target DB | 지속 세션, strict replay, 관계형 export, 원자적 적용·복원, 대표 신원/provider 인터페이스 |
| DATA | 100명/학년별25명/80명+20명/기간/정책/seed | 세션 결과 기반 전체 행동 생성, 실제 feed·recap close, fixed bundle 조립, 두 DB 전체 재생 비교, canonical·legacy CSV·전체 검증 보고서 |
| DATA / 적용 실행자 | 검증된 relational export, 현재 target/console baseline, exact controller action | 로컬 PREPARE·backup·dry-run 검증, controller-bound 적용, DB journal/read-back와 별도의 외부 Owner 콘솔 read-back, 복원 증거 |
| FRONTEND | personas.json, 서버의 grade 토큰, 기존 계좌/위시/feed/recap API | grade-3~6 전환과 실제 응답 연결, 네 대표별 API/UI/DB 확인 |

DATA가 생성하는 역사 속 Owner는 현재 대상 Owner와 구분한다. 적용 후보에는 현재 Owner의 신원뿐 아니라 잔액 관측·원장·checkpoint·위시·공유·관계·방문 등의 필요한 기존 그래프가 남는다. 로컬 Owner의 합성 현금/관측/위시는 덮어쓰지 않는다. 제거할 로컬 Owner 객체를 다른 학생의 저장 행이 참조하거나, 투영 과정에서 타인의 행동을 조용히 제거해야 하는 교차 관계가 있으면 가져오기를 거부한다. DATA는 이 제한을 정책/번들 설계에 반영하고 원본 로컬 결과와 대상 후보의 차이를 명시한다.

## 용량 계약 변경

현재 상한은 파일 262,144개, manifest 128 MiB, artifact 128 MiB, artifact 합계 16 GiB다. RAW 전체를 메모리에 보유하지 않고 파일별 검증과 재독 시 checksum 확인을 수행한다. 실제 전체 13.86 GB 입력의 입장 최대 RSS는 905,478,144바이트였다. 새 출력의 봉인된 실행 파일과 RAW는 동일 출력 안에서 hardlink로 공유한다. 기존 원본은 삭제·축약하지 않는다.

## 경계

최종 `readyForApplication`과 전체 데이터 품질은 DATA의 완전한 실행 및 컨트롤러 게이트가 판단한다. 세션 종료나 부분 replay, SQL dry-run 성공만으로 승격하지 않는다. replay media 생성은 지원하지 않으며 기존 media/키는 importer에서 원본을 읽거나 쓰지 않고 DB 내부 digest로 보존을 대조한다. 외부 콘솔 baseline digest는 실행자가 제공하는 바인딩이며 외부 검증 자체가 아니다. 이 backend action은 DATA/FRONTEND 산출물을 대신 만들지 않는다.
