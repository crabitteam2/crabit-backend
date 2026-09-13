# 실제 데모 반영 시 DB 역할 분리

2026-09-13 읽기 결과에서 Stable Demo의 backend와 postgres는 같은 `crabit` superuser를 사용한다. V21/V22의 비웹 가져오기·복원 함수는 `crabit_demo_manager` 또는 DB 관리자만 실행해야 하므로, 데이터 반영 후 새 backend는 일반 로그인으로 실행한다. 이 문서는 준비 절차이며 실제 반영 완료 증거가 아니다.

`scripts/demo/create-runtime-role.sql`은 migration 이후 관리자 연결에서 한 번 실행한다. 새 `crabit_demo_app`은 NOINHERIT/NOSUPERUSER/NOCREATEDB/NOCREATEROLE/NOREPLICATION/NOBYPASSRLS이며 기존 테이블을 소유하지 않는다. 별도 비공개 채널에서 생성된 비밀번호와 LOGIN을 설정한다. 이미 역할이 있으면 실패하며 자격 증명을 자동 교체하지 않는다. 관리 역할 membership이나 전체 함수 EXECUTE를 부여하지 않는다.

일반 도메인 DML과 sequence 사용은 허용한다. 기존 이력 불변 trigger는 유지한다. Flyway 이력 및 simulation 매핑·persona·적용 journal의 변경 권한은 제거한다. live cash 명령에 필요한 dataset 행 잠금용 revision UPDATE, account의 card_funds/cash_sequence UPDATE, cash event INSERT만 별도로 허용한다. migration과 APPLY/RESTORE는 계속 별도 관리자 프로세스가 담당한다.

`deploy/compose.demo-simulation.yaml`은 새 runtime 계정과 네 grade 토큰을 backend에만 전달한다. 기존 postgres 관리자 연결 설정은 유지한다. migration을 별도 수행한 뒤 serving backend의 Flyway를 끄며, APPLIED 매핑을 startup에서 검증한다. Owner 조회 중지는 반영 구간 및 Owner 보존 검증 중에만 사용한다. 실제 운영 재개 시 기존 외부 Owner provider 경로를 사용한다.

`PrepareDemoPreview.java`는 사용자 승인 백업을 새 loopback PostgreSQL에 복원하고 V20→V22와 전체 선택 적용을 실행한다. 위 SQL 파일 그대로 일반 앱 계정을 만들고 네 관리 함수의 실제 SQLSTATE 42501 거절을 확인한다. 새 bootJar를 그 계정으로 시작해 네 대표의 계좌 HTTP 조회를 검증한다. 별도 실제 Python feed/recap loopback 프로세스가 필요하다. 비공개 연결 정보는 Git 제외 artifact 경로의 0600 파일에 두며, `stop` 파일이 생기면 자신이 만든 app과 DB만 종료한다. 공개 데모 URL이나 외부 DB 연결을 입력받지 않는다.

2026-09-13 `preview-02`에서 이 로컬 검증은 PASS다. 네 대표의 계좌 HTTP 200과 관리 함수 네 개의 실제 권한 거절을 확인했다. 이어 실제 잔액 새로고침, 계좌/위시 조회, 8월 31일 주간 리캡, 8월 월간 리캡, Python 추천순 feed, 다른 대표 및 Owner 계좌의 HTTP 404를 대조했다. 모든 주간 리캡은 SUCCEEDED, 월간은 3·6학년 NOT_ELIGIBLE 및 4·5학년 SUCCEEDED다. 마지막 잔액 관측을 DB에서 다시 읽어 네 계정 모두 SIMULATION/SUCCEEDED이고 card_funds와 같음을 확인했다. 근거는 `verification-runtime-20260913.json`의 localPreview와 DATA의 `live-target-20260913/preview-02/target/api-verification-02/`다. 이는 실제 로컬 HTTP·DB 검증이며 frontend UI나 외부 적용 검증은 아니다.

검증 도구의 첫 실행은 오래된 표시 관측값을 유지해야 한다는 잘못된 기대값에서 중단됐다. 6학년은 마지막 관측 51,306원 이후 현금 원장이 54,619원이어서 새로고침이 54,619원을 반환하는 것이 맞다. 내부 DB 성공 상태 이름도 API의 SUCCESS와 다른 SUCCEEDED다. 도구의 기대값을 고친 뒤 이미 성공한 HTTP 응답을 재검증하고 새 DB read-back을 수행했다. 이 두 도구 수정은 backend 제품 오류로 분류하지 않는다. 원래 실패 기록과 실제 HTTP 원본은 보존했다.

그 이후 deadline 주입 변경을 포함한 당시 jar `sha256:712eba5d2faf36c98c513133925712a79265e59e582bef31c764c33d1ba7d4ea`는 `preview-04`에서 serving 기본값 500ms로 새로 검증했다. 네 대표 총 32개 HTTP 호출과 새 DB read-back이 PASS이며, 이전 응답을 재사용한 검증이 아니다. 근거는 `verification-deadline-20260913.json`의 runtime 및 DATA의 `live-target-20260913/preview-04/target/api-verification/`다. 기존 preview에서 만들어 별도로 표시한 로컬 적용 후 dump를 새 DB에 복원했으며, 실제 운영 백업이나 운영 APPLY로 분류하지 않는다. dump에 포함되지 않는 관리 역할과 기존 V21의 PUBLIC 실행 권한 제한을 복구한 후 일반 runtime 로그인을 활성화한다.

실제 반영은 최종 검증된 bundle digest, 코드 및 실행 artifact digest, 최신 target 백업·fingerprint, 외부 콘솔 4개 테이블 baseline을 묶어야 한다. 관리 dry-run/단일 APPLY와 별도 DB read-back 후 외부 콘솔을 다시 읽는다. 가능한 write 후 오류는 재시도하지 않고 INSPECT부터 수행한다. 기존 Owner 전체 그래프 및 기존 학생 4명 보존과 학년별 25명 cohort를 따로 확인한다. 전체 학생 수를 100명으로 맞추기 위해 기존 사용자를 지우지 않는다.

frontend는 Owner 기본값과 별도로 grade-3~6의 실제 인증 선택을 SSR, BFF, behavior context에 일관되게 전달해야 한다. 선택 시 persona epoch/academy context와 이전 계정의 클라이언트 상태를 갱신한다. grade 토큰은 서버 환경에만 두고 응답·브라우저 저장소에 노출하지 않는다. 실제 계좌 ID는 기존 `/v1/me/card-balance-accounts` 응답으로 얻는다. 잘못된 grade 또는 누락된 credential을 Owner로 대체하지 않는다.

UUID 할당 분리 이후 현재 jar는 `sha256:faff8c765244057208a2aa2d13bb5bb5e51a0532360d3e93ba25cd35abd6b8e3`이다. 새 `preview-05`에서 일반 DB 역할, 네 관리 함수 거절, 네 대표 32개 실제 HTTP 및 DB 대조가 다시 PASS다. `verification-card-identities-20260913.json`에 최신 source/XML/runtime digests와 별도 검증 시점을 기록했다. 실제 외부 적용·frontend UI는 여전히 미실행이다.
