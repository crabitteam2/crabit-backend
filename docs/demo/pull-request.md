# 100명 데모 행동 시뮬레이션의 실제 재생·선택 적용·복원 연결

고정 persona와 외부 잔액에 의존하던 demo에 실제 도메인 서비스로 과거 행동을 재생하고 검증된 관계형 상태를 선택 적용하는 backend 경로를 추가한다. 정책 생성기가 한 번에 모든 미래 이벤트를 알아야 하던 제약을 없애고, 실제 추천·거절·잔액 응답을 받은 뒤 다음 행동을 결정할 수 있게 한다. 현재 demo Owner의 기존 잔액/관측 출처/원장/위시 등의 그래프는 유지하면서 나머지 99명의 합성 상태와 네 대표 신원을 연결한다.

## 동작과 데이터 흐름

`SimulationReplaySession`은 일회용 PostgreSQL과 Java 도메인 서비스를 유지하며 STEP마다 실제 결과와 실현된 이벤트를 반환한다. 추천 요청에는 미정 순서를 빈 배열로 보내고 실제 Python 순서를 다음 노출/클릭/결정에 사용한다. 원래 intent와 확정 이벤트를 따로 남기며, FINISH에서 기존 고정 replay의 export·독립 검증을 함께 실행한다. 이후 DATA가 확정 bundle을 두 새 DB에서 재생해 전체 재현성을 검증한다.

승인 schema의 25종 이벤트, 논리적 실행 시각, 실제 commit/trigger, 지급·실제 카드 사용과 위시 배분의 분리, 관측/원장/조정/공유/관계/방문/행동, 실제 Python feed·recap 경로를 사용한다. 원본 HTTP bytes·실제 DB 상태·정규화 비교물을 구분한다. 추천/리캡 계산을 Java에서 꾸며 반환하거나 부분 replay를 전체 데이터 PASS로 표시하지 않는다.

`SimulationImportManager`와 별도 `simulationImport` CLI는 검증된 relational export와 대상 DB의 현재 카탈로그/스냅샷으로 적용 후보를 만든다. Owner 로컬 합성 그래프를 현재 대상 Owner 그래프로 교체하고, 나머지 99명에게만 합성 관측 출처 검사를 적용한다. Owner의 합성 현금 기록은 가져오지 않으며 매핑의 합성 cache는 0으로 유지한다. Owner의 외부 provider 분기는 기존 경로를 사용한다. 제거된 로컬 Owner 객체를 다른 학생 데이터가 참조하거나 교차 행동을 조용히 버려야 하면 후보 준비를 거부한다.

적용 후보는 PostgreSQL의 실제 row type으로 정규화되어 timestamp 표기 차이로 복원 선택을 오인하지 않는다. 원본 replay export는 그대로 보존한다. 네 대표는 학년별 비Owner 계좌에 매핑된다. APPLIED 합성 계좌의 완전히 포함된 월에는 데이터셋의 관측 시작을 사용하고, Owner/다른 계좌/부분 기간/전역 수집 시작은 유지한다.

## 영속성, 권한, 동시성

V21에 dataset/account/persona/cash/application metadata와 observation 출처를 추가한다. 추가 V22는 알림 outbox의 adjustment_case_id를 통해 실제 부모 소속을 확인한다. 이미 기록된 V21 checksum은 유지한다. 관리 역할은 NOLOGIN/NOINHERIT이며 일반 애플리케이션 로그인에는 부여하지 않는다. schema migration에는 역할 생성 권한 또는 사전 역할 준비가 필요하다.

import는 고정 테이블 allowlist, 전체 테이블 잠금, 현재 revision/snapshot 비교와 exact-row capability를 사용한다. 기존 append-only/history trigger는 일반 쓰기에서 유지한다. 적용 함수 소유자의 임시 scope에 정확히 등록된 행에만 한정된 처리를 허용한다. 기존 Owner 행처럼 before/after가 같은 행은 삭제·재삽입하지 않는다. trigger 비활성화·전역 clock 변경·전역 수집 시각 변경을 사용하지 않는다.

dry-run은 실제 SQL 변경/FK/도메인 constraint/전체 행 read-back/Owner 그래프 대조/시퀀스 처리를 실행한 뒤 rollback한다. 실제 적용은 동일 예상 after fingerprint에 바인딩하며 감사 journal을 함께 commit한다. 이후 별도 DB read-back으로 journal ID와 fingerprint를 확인한다. 정확히 같은 요청과 같은 대상 상태는 NO_OP다. 불명확한 write 결과를 자동 재시도하지 않고 INSPECT로 journal을 확인한다.

복원은 checksum으로 검증한 원본 백업, 적용 journal, 변하지 않은 전체 target fingerprint를 요구한다. 같은 행 수의 값 변경·새 행·sequence/스키마 변경도 거부한다. 전체 행 차이는 JSONB 집합의 EXCEPT로 계산하여 반복 배열 검색의 timeout을 제거했다. 원래 도메인 상태와 시퀀스를 복원하고 감사 journal 및 RESTORED dataset metadata를 남긴다. 관계없는 행, 키/미디어, 전역 수집 기록은 보존한다. 외부 Owner 콘솔은 적용 전 읽기만 수행했으며 전후 보존 검증은 아직 완료하지 않았다.

새 일반 앱 계정의 SQL과 backend 전용 compose overlay를 제공한다. 일반 역할은 기존 도메인 DML 및 합성 현금 처리에 필요한 최소 권한을 갖고, 네 가져오기·복원 관리 함수는 실행할 수 없다. Flyway와 관리 APPLY/RESTORE는 별도 관리자 프로세스가 담당한다. `PrepareDemoPreview.java`와 로컬 wrapper는 승인 백업을 자신이 만든 loopback DB에 복원해 이 권한 분리와 실제 bootJar/HTTP를 검증한다. 비공개 연결 파일은 Git 제외 경로와 제한된 파일 권한으로 보관한다.

## 실제 응답과 기능 재현의 구분

NOT_ELIGIBLE 리캡은 실제 생성 호출이 없으므로 저장된 상태·입력·기간으로 응답 부재를 검증한다. Python을 호출했지만 응답을 받지 못한 피드는 원본 LATEST fallback 및 실제 호출 metadata로 확인한다. 존재하지 않는 응답 파일을 생성하거나 성공 응답으로 바꾸지 않는다.

피드 입력 조립은 같은 요청의 반복 가능한 DB snapshot 및 as-of 안에서 작성자·계좌·월별 통계와 제목 분류를 재사용한다. 완료 월이 다르거나 다음 요청이면 새로 조회한다. 실제 서비스와 discovery의 500ms 제한은 유지한다. 고정 재생 CLI만 명시적인 30,000ms 기능 재현 예산을 선택할 수 있고, 관측에 예산과 `servingLatencyPolicyValidated=false`를 남긴다. serving runtime에는 이 예산을 늘리는 환경변수 경로가 없다.

원본 e17164의 응답 부재는 새 실제 Python 응답을 35초 보류하여 실제 30초 timeout으로 재현한다. 새 upstream 요청·응답과 주입 기록을 별도로 보존하고 이벤트 순서·기대 응답·정규화 비교 조건은 유지한다. 이 통제된 전송 조건의 재현은 서비스의 500ms 성능 검증으로 해석하지 않는다.

이체가 두 카드의 updated_at을 같게 만드는 경우에는 UUID가 실제 동률 정렬 입력이다. 고정 재현에서 새 임의 UUID를 생성하면 원본과 다른 추천이 나올 수 있어, 검증된 id-map의 공유 카드 UUID 할당을 생성 event별로 재생한다. 원본의 169개 카드에 대해 소유 계좌·event·중복·실제 할당 여부를 대조한다. 카드 내용·원장·예상 추천 결과를 가져오지 않고 실제 도메인 서비스로 생성한다. 일반 서비스의 UUID 생성과 정렬 정책은 유지하며, 기록된 ID 할당기는 simulation 실행에만 존재한다. 두 실행은 서로 다른 DB를 사용하고 다른 신원은 독립적으로 생성한다.

## 검증

최신 전체 suite는 최종 UUID 할당·deadline·가져오기 수정을 포함한 `verification-final-suite-20260913.json`이다. 일반 698개(실패/오류 0, 기존 선택적 parity skip 1), simulation 517개(실패/오류/skip 0), bootJar/classpath가 8분 44초에 exit 0으로 완료됐다. 보고서 SHA-256은 `cf1c1d58122d158c822408a88a524c51626573d5d730f89ad5024ebb6d0a19f5`이며 실제 XML·로그·현재 소스와 다시 대조했다. 최종 jar는 아래 `preview-05`의 jar와 동일하고, 실행 소스 overlay는 성공한 두 전체 재생과 같다. 이전 `verification-c3f087.json`, `verification-runtime-20260913.json`, `verification-deadline-20260913.json`과 대응 산출물은 각 시점의 증거로 보존한다.

통합 테스트는 실제 Python 추천 결과를 다음 노출 입력으로 사용하고, 확정 이벤트를 두 독립 DB에서 재생해 피드/응답/관계형/백엔드 정규화 결과를 비교한다. 별도 import 테스트는 100명 매핑, 지급·위시·배분·공유, 현재 Owner의 23,456원 provider 관측·원장·checkpoint·위시, 일반 역할 호출 거부와 위조 scope 거부, Owner 변조 거부, dry-run rollback, 적용·중복 NO_OP·drift 거부·복원, 99명 provider 분기와 네 대표 매핑을 검증한다. 전체 `simulationTest test bootJar` 및 bootJar의 simulation 실행 코드/의존성 격리도 확인한다.

피드 요청 내 재사용 수정까지 일반 테스트 698개(실패·오류 0, 기존 선택적 parity skip 1), simulation 테스트 515개(실패·오류·skip 0), bootJar/classpath 생성이 8분 31초에 통과했다. 이후 deadline 변경은 기존 serving timeout·공유 카드 회귀, 실제 Python을 700ms 늦춘 경우를 포함한 simulation feed 91개와 PostgreSQL feed API 6개가 통과했다. 두 시점의 결과를 변경 이후 전체 suite 실행으로 합치지 않는다.

deadline 변경 시점 bootJar `sha256:712eba5d2faf36c98c513133925712a79265e59e582bef31c764c33d1ba7d4ea`의 일반 역할 preview에서 네 대표별 8개씩 총 32개 실제 HTTP 호출과 별도 DB read-back이 통과했다. 잔액 새로고침·계좌·위시·주간/월간 리캡·Python 추천 피드를 확인하고 다른 대표 및 Owner 계좌는 404로 거절했다. 네 계정의 최신 관측은 SIMULATION/SUCCEEDED이며 실제 card_funds와 일치했다. 관리 함수 네 개도 실제 SQL 권한 오류로 거절됐다. 이는 frontend UI 검증과 구분한다.

전체 원본 18,396개 이벤트를 각각 실행한 두 새 DB 재현은 `fixed-replays-09/comparison.json`에서 PASS다. 05는 DB connection reset, 06은 HTTP 호출 전 500ms 소진, 07은 실제 응답 대기 deadline으로 기대 추천순과 달라져 엄격 비교가 중단됐다. 실패 기록은 보존한다. 09는 두 실행의 종료 코드가 모두 0이며 backend·relational·responses·feed·recaps 정규화 파일 5종의 바이트 해시가 모두 같다. 실행 전후 DATA 소스 및 backend overlay 해시도 같다. 근거는 `verification-full-replays-20260913.json`이다. 이 결과는 30초 기능 재생 조건의 재현 증거이며 서비스 500ms 성능이나 실제 외부 DB 반영 증거가 아니다.

08은 11,095개 처리 뒤 위 UUID 동률 문제로 e11096에서 중단됐고, 수정된 09의 두 전체 실행은 통과했다. 원본 요청의 동률 후보 두 위치만 교환해 실제 Python 순위가 달라지는 것을 재현했다. 이후 위시·공유 카드 69개 및 simulation feed 92개가 실패·오류·skip 없이 통과했으며, 같은 시각 카드 두 개의 순서를 두 독립 DB에서 검증하는 회귀를 포함한다. 이 결과는 앞선 전체 suite와 별도 시점이다. 최신 jar `sha256:faff8c765244057208a2aa2d13bb5bb5e51a0532360d3e93ba25cd35abd6b8e3`의 `preview-05`도 네 대표 32개 새 HTTP 호출·DB 대조 및 관리 함수 거절을 통과했다. 근거는 `verification-card-identities-20260913.json`이다. 09는 서로 다른 새 DB에서 각각 약 48분과 49분 동안 실행했다. 검증 과정에서 각 DB의 전체 상태가 바뀌지 않았고, 각 실행의 피드 2,311회 시도/2,310회 응답/2,445개 페이지 및 리캡 1,472개가 검증됐다.

브랜치 기준은 `67920c84d859969c218c057eab8021462390035d`, 마지막 controller 기록 HEAD는 `cfd4e400b865a8fbe99b2f27f638b05edf1cfa0e`이며 후속 수정은 현재 미커밋이다. 승인 OpenAPI digest `sha256:c028a6dd7ae5e22e941b250e2a81b232d28a8f4ad597fa53aeba2e3b88094710`은 유지했다. canonical schema의 유한 용량 상한과 fixture bytes/digest는 필수 전체 이력 크기에 맞춰 동기화했다.

## 필수 전체 이력 용량

80명×14주 마감에서만 최소 12,320개 RAW가 필요하여 기존 manifest.files 4,096개와 manifest 1 MiB가 실제 실행을 막았다. 현재 파일 수 262,144개, manifest 128 MiB, per-artifact 128 MiB, artifact 합계 16 GiB로 유한 한도를 정렬했다. raw writer/reader/schema와 관계형/선택 백업 입력이 같은 개별 상한을 사용한다. 합계는 파일을 메모리에 읽기 전에 검사한다. RAW를 전부 메모리에 보유하지 않고 파일별 검증 및 재독 시 checksum을 확인한다.

실제 100명 전체 bundle 13,862,132,635바이트가 768 MiB heap으로 입장을 통과했고 최대 RSS는 905,478,144바이트였다. 새 실행에서 봉인된 파일과 RAW는 같은 출력 안에서 hardlink로 공유한다. 실행 wrapper는 봉인된 새 파일만 동일 bytes/digest 확인 후 투명 압축하고 여유 공간 3 GiB 미만이면 중단한다. 원본 증거를 제거하거나 마감을 줄이지 않는다.

## 후속 담당과 현재 적용 상태

[실행 인터페이스](backend-handoff.md)에 DATA의 persistent session/fixed replay/prepare/apply/inspect/restore 명령과 FRONTEND의 grade-3~6 토큰·기존 API 사용 방법을 정리했다. [현재 상태](implementation-status.md)는 지원 범위와 담당 표이며, 과거 상태/PR 본문은 별도 history 파일에 보존했다.

DATA의 전체 원본 생성과 canonical/CSV, 두 전체 고정 재현은 완료됐다. DATA ready receipt와 expected-tree 준비도 확인됐지만, 상위 실행자의 최신 Git read-back 기준 DATA HEAD는 `3b081b7a51523cdf8d24a6991b7e914e0fd8bc48`로 커밋 전이다. 승인된 실제 Stable Demo 백업의 로컬 복제 DB에서는 V20→V22, 전체 선택 적용, 현재 Owner 위시 107개 및 기존 학생 4명 보존, 모든 도메인 행과 sequence 복원이 통과했다. 실제 대상 DB의 cohort 100명과 보존된 기존 학생을 합친 예상 전체 학생 수는 104명이다.

현재 외부 DB는 백업·카탈로그 읽기만 수행했다. 실제 migration/APPLY/배포 및 그 이후 Owner 외부 콘솔 보존 read-back은 남아 있다. FRONTEND의 네 대표 전환과 실제 화면 검증도 남아 있다. 새 수정의 controller commit·Draft PR·외부 read-back과 게이트 승인은 완료되지 않았다. 이 설명의 로컬 검증을 실제 데모 반영 완료로 해석하지 않는다.
