# 100명 데모 행동 시뮬레이션의 backend 재생·적용·복원 연결

고정 persona와 외부 잔액에 의존하던 demo에 실제 도메인 서비스로 과거 행동을 재생하고 검증된 관계형 상태를 선택 적용하는 backend 경로를 추가한다. 정책 생성기가 한 번에 모든 미래 이벤트를 알아야 하던 제약을 없애고, 실제 추천·거절·잔액 응답을 받은 뒤 다음 행동을 결정할 수 있게 한다. 현재 demo Owner의 기존 잔액/관측 출처/원장/위시 등의 그래프는 유지하면서 나머지 99명의 합성 상태와 네 대표 신원을 연결한다.

## 동작과 데이터 흐름

`SimulationReplaySession`은 일회용 PostgreSQL과 Java 도메인 서비스를 유지하며 STEP마다 실제 결과와 실현된 이벤트를 반환한다. 추천 요청에는 미정 순서를 빈 배열로 보내고 실제 Python 순서를 다음 노출/클릭/결정에 사용한다. 원래 intent와 확정 이벤트를 따로 남기며, FINISH에서 기존 고정 replay의 export·독립 검증을 함께 실행한다. 이후 DATA가 확정 bundle을 두 새 DB에서 재생해 전체 재현성을 검증한다.

승인 schema의 25종 이벤트, 논리적 실행 시각, 실제 commit/trigger, 지급·실제 카드 사용과 위시 배분의 분리, 관측/원장/조정/공유/관계/방문/행동, 실제 Python feed·recap 경로를 사용한다. 원본 HTTP bytes·실제 DB 상태·정규화 비교물을 구분한다. 추천/리캡 계산을 Java에서 꾸며 반환하거나 부분 replay를 전체 데이터 PASS로 표시하지 않는다.

`SimulationImportManager`와 별도 `simulationImport` CLI는 검증된 relational export와 대상 DB의 현재 카탈로그/스냅샷으로 적용 후보를 만든다. Owner 로컬 합성 그래프를 현재 대상 Owner 그래프로 교체하고, 나머지 99명에게만 합성 관측 출처 검사를 적용한다. Owner의 합성 현금 기록은 가져오지 않으며 매핑의 합성 cache는 0으로 유지한다. Owner의 외부 provider 분기는 기존 경로를 사용한다. 제거된 로컬 Owner 객체를 다른 학생 데이터가 참조하거나 교차 행동을 조용히 버려야 하면 후보 준비를 거부한다.

적용 후보는 PostgreSQL의 실제 row type으로 정규화되어 timestamp 표기 차이로 복원 선택을 오인하지 않는다. 원본 replay export는 그대로 보존한다. 네 대표는 학년별 비Owner 계좌에 매핑된다. APPLIED 합성 계좌의 완전히 포함된 월에는 데이터셋의 관측 시작을 사용하고, Owner/다른 계좌/부분 기간/전역 수집 시작은 유지한다.

## 영속성, 권한, 동시성

V21에 dataset/account/persona/cash/application metadata와 observation 출처를 추가한다. 관리 역할은 NOLOGIN/NOINHERIT이며 일반 애플리케이션 로그인에는 부여하지 않는다. schema migration에는 역할 생성 권한 또는 사전 역할 준비가 필요하다.

import는 고정 테이블 allowlist, 전체 테이블 잠금, 현재 revision/snapshot 비교와 exact-row capability를 사용한다. 기존 append-only/history trigger는 일반 쓰기에서 유지한다. 적용 함수 소유자의 임시 scope에 정확히 등록된 행에만 한정된 처리를 허용한다. 기존 Owner 행처럼 before/after가 같은 행은 삭제·재삽입하지 않는다. trigger 비활성화·전역 clock 변경·전역 수집 시각 변경을 사용하지 않는다.

dry-run은 실제 SQL 변경/FK/도메인 constraint/전체 행 read-back/Owner 그래프 대조/시퀀스 처리를 실행한 뒤 rollback한다. 실제 적용은 동일 예상 after fingerprint에 바인딩하며 감사 journal을 함께 commit한다. 이후 별도 DB read-back으로 journal ID와 fingerprint를 확인한다. 정확히 같은 요청과 같은 대상 상태는 NO_OP다. 불명확한 write 결과를 자동 재시도하지 않고 INSPECT로 journal을 확인한다.

복원은 checksum으로 검증한 원본 백업, 적용 journal, 변하지 않은 전체 target fingerprint를 요구한다. 같은 행 수의 값 변경·새 행·sequence/스키마 변경도 거부한다. 원래 도메인 상태와 시퀀스를 복원하고 감사 journal 및 RESTORED dataset metadata를 남긴다. 관계없는 행, 키/미디어, 전역 수집 기록은 보존한다. 외부 Owner 콘솔은 호출하지 않았으며 `externalConsoleVerified=false`를 유지한다.

## 검증

정확한 실행 명령·결과·건수·exit code·원본 XML 및 산출물 hash는 `verification-c3f087.json`에 기록한다. 이번 action 변경은 `action-files-c3f087.json`에 기록한다.

통합 테스트는 실제 Python 추천 결과를 다음 노출 입력으로 사용하고, 확정 이벤트를 두 독립 DB에서 재생해 피드/응답/관계형/백엔드 정규화 결과를 비교한다. 별도 import 테스트는 100명 매핑, 지급·위시·배분·공유, 현재 Owner의 23,456원 provider 관측·원장·checkpoint·위시, 일반 역할 호출 거부와 위조 scope 거부, Owner 변조 거부, dry-run rollback, 적용·중복 NO_OP·drift 거부·복원, 99명 provider 분기와 네 대표 매핑을 검증한다. 전체 `simulationTest test bootJar` 및 bootJar의 simulation 실행 코드/의존성 격리도 확인한다.

기준 HEAD는 `67920c84d859969c218c057eab8021462390035d`이며 미커밋 변경이다. 승인 OpenAPI digest `sha256:c028a6dd7ae5e22e941b250e2a81b232d28a8f4ad597fa53aeba2e3b88094710`은 유지했다. canonical schema는 사용자가 지적한 필수 마감 용량 충돌을 해결하기 위해 파일 수와 두 byteLength 상한만 변경했고, fixture의 schema bytes/digest를 동기화했다. 환경 의존 parity skip은 실제 검증 범위에서 제외해 보고한다.

## 필수 전체 이력 용량

80명×14주 마감에서만 최소 12,320개 RAW가 필요하여 기존 manifest.files 4,096개와 manifest 1 MiB가 실제 실행을 막았다. 파일 수 262,144개, manifest 128 MiB, per-artifact 128 MiB, artifact 합계 8 GiB로 기존 유한 한도를 정렬했다. raw writer/reader/schema와 관계형/선택 백업 입력이 같은 개별 상한을 사용한다. 합계는 파일을 메모리에 읽기 전에 검사한다. 원본 증거를 제거하거나 마감을 줄이지 않는다.

7분50초 전체 검증 기록은 변경 전 범위 그대로 보존했다. 이후 Owner 이력 delta와 capacity delta의 focused XML/로그를 별도로 보존한다. 실제 필수 마감 metadata의 기존 제한 초과/새 제한 통과, schema/reader 일관성, 새 파일 수·개별 bytes 상한 거부, 8 GiB 합계의 정확한 경계와 초과 사전 거부를 검사한다. 실제 DATA 전체 실행의 크기와 peak memory는 후속 측정이며 이번 scalar 검증으로 대체하지 않는다.

## 후속 담당과 현재 적용 상태

[실행 인터페이스](backend-handoff.md)에 DATA의 persistent session/fixed replay/prepare/apply/inspect/restore 명령과 FRONTEND의 grade-3~6 토큰·기존 API 사용 방법을 정리했다. [현재 상태](implementation-status.md)는 지원 범위와 담당 표이며, 과거 상태/PR 본문은 별도 history 파일에 보존했다.

DATA는 전체 기간 100명 정책 이력, 전체 두 DB 비교, canonical/CSV/최종 검증과 controller-bound target 적용·복원 증거를 완료해야 한다. FRONTEND는 네 대표 전환과 실제 UI/API/DB 흐름을 연결·검증해야 한다. Owner 외부 콘솔의 전후 보존은 별도 read-back이 필요하다. 이 backend 변경은 전체 100명 실행·브라우저·운영 적용의 완료 증거가 아니다. commit/push/PR 생성/merge/배포/현재 demo 적용/게이트 승인은 이번 역할에서 실행하지 않았다.
