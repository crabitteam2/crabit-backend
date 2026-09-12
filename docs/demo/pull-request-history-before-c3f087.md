# Historical snapshot before act-c3f0871a884a26b2393a274192ac7d32

This file preserves prior action provenance. It is not the current support or delivery status.

## 백업 중 동시 쓰기 차단

백업 시작과 끝의 지문만 비교하던 경로에 PostgreSQL 테이블 잠금을 추가한다. 잠금을 얻기 전에 완료된 writer도 최신 지문 비교로 감지하여 이전 revision의 백업을 거부하며, 백업 캡처가 끝날 때까지 기존 공개 테이블의 행 변경과 DDL을 대기시킨다. 2초 잠금 timeout, 실패 rollback, callback 미실행, 일반 조회 허용과 잠금 해제를 실제 별도 JDBC 연결로 검사한다. 정확한 실행 증거는 docs/demo/verification-7c1c6c.json에 기록한다. 외부 콘솔/독립 sequence/관리자 함수 DDL의 완전한 배타성과 target import/apply/restore는 이 변경의 완료 범위에 포함되지 않는다.

# 데모 시뮬레이션의 실제 도메인 재생과 독립 검증 기반

고정 persona와 외부 잔액에 의존하던 데모에 100명 합성 학생의 역사 재생을 위한 로컬 실행 기반을 추가한다. 일반 demo에서는 활성 데이터셋의 비Owner 계좌를 가상 현금 원장에 연결하고, 기존 Owner 신원과 외부 잔액 경로를 유지한다. 시뮬레이션은 새 PostgreSQL과 실제 Java 도메인 서비스·로컬 Python 추천/리캡을 사용하며, 원본 실행 결과를 보존한 뒤 별도 검증한다. 전체 데이터 생성·제품 적용까지 완료된 변경은 아니다.

## 가상 잔액·저장·격리

- V21은 데이터셋·계좌/대표 매핑·현금 원장·적용 journal과 SIMULATION 관측 출처를 추가한다. V1..V20과 승인 OpenAPI의 바이트를 보존한다.
- 활성 데이터셋의 99개 비Owner 계좌는 계좌 상태·membership·원장 합계·sequence·캐시 정합성을 확인한다. Owner는 기존 외부 제공자를 사용한다. 누락되거나 닫힌 가상 계좌를 임의 잔액으로 성공 처리하지 않는다.
- 현금 명령은 dataset → account 순서로 잠그며 같은 사건/입력은 재사용하고 다른 입력은 충돌한다. 음수/범위 초과, 개설 전 사건과 시각 역행을 거부하고 원장·캐시를 함께 롤백한다. 현금 지급·구매는 위시 배분을 자동 생성하지 않는다.
- BUILDING의 역사 재생과 APPLIED의 현재 명령을 분리한다. 현재 명령은 DB 현재 시각을 사용하며 Owner 현금 명령은 거부한다.
- SIMULATION 출처와 dataset/cash sequence가 기존 관측 트랜잭션에 함께 저장된다. 실제 제공자 관측은 PROVIDER다.
- 데이터셋이 있으면 seed 재추가·legacy reset을 거부한다. 대표 네 명의 토큰은 서버 설정에서만 주입하고 신원·credential 중복을 거부한다. Owner lookup 일시중지는 sync 전에 제외한다.
- prod에는 simulation 제공자를 등록하지 않으며 demo+prod/demo+e2e는 거부한다. CLI tooling은 simulation source set에만 존재하고 bootJar에는 포함되지 않는다. HTTP import/reset/clock API를 추가하지 않는다.

## 적용 전 참조 경계 진단

정확한 PK 선택에 대한 SQL FK와 명시적 비FK 참조를 검사한다. 공유 해제된 카드의 과거 참조, 방문 증거의 이력 상한, 페이지/checkpoint JSON, 학생 idempotency와 recap JSON, 합성 현금 원장 출처의 선택/보존 경계를 양방향으로 보고한다. DB와 원본 export를 변경하거나 대상 범위를 자동 확대하지 않는다. 지원 범위와 한계는 `semantic-graph-boundary.md`를 따른다.

현재 replay DB에서 읽은 카탈로그를 신뢰 기준으로 관계형 export bytes를 다시 읽는 경로를 추가했다. 입력 catalog, 알 수 없는 테이블/필드, 중복·잘못된 JSON, 타입 강제 변환·정수 범위 초과·잘못된 UUID/날짜/시각을 거부한다. 실제 domain 명령으로 생성한 PostgreSQL 행을 다시 읽어 동일성과 보존을 검사한다. `relational-input.md`에 검증 범위와 남은 typed import 조건을 설명한다. 이 읽기는 DB 쓰기·UUID 변환·apply/restore를 수행하지 않는다.

관계형 export를 실제 PostgreSQL 임시 테이블 38개에 parameter로 적재하고 read-back digest를 대조한다. 현재 카탈로그의 FK를 양쪽 pg_temp 테이블에 연결하고 PostgreSQL에서 검증한다. 공개 부모 행으로 누락된 임시 부모를 대신할 수 없다. FK/CHECK/문자열 길이 실패의 rollback, 원본 공개 행·sequence·schema 보존, timestamp offset과 int8 정밀도를 검사한다. 이 경로는 공개 domain import의 전송 사전 검사이며 trigger/FK 대상 교체나 apply/restore를 수행하지 않는다. `relational-staging.md`에 범위와 한계를 기록한다.

## 실제 역사 재생과 증거

새로운 일회용 PostgreSQL의 Java/SQL 시계를 동기화하고 실제 domain transaction, commit trigger, lookup과 배분의 분리된 커밋을 사용한다. 데이터셋 입구는 manifest·파일 digest·closed schema·100명 구성·typed ID·시간/원인/소유권을 검사한다. 부분 검증 성공을 데이터셋 전체 PASS로 올리지 않는다.

원본 논리 명령, Java 응답, Python request/response, 실제 DB export를 각각 보존한다. 금융 원장·관측·조정·행동·방문·월 예산·리캡 기간/또래/성공 사례·피드 후보/월 지표/유사도는 원본 행과 독립 대조한다. 검증 실패 시 증거를 먼저 남기고 실행을 실패시킨다. 유효한 노출 없는 클릭을 유지하며 영향·휴면 주석이 domain row를 만들지 않는다.

실제 Python 추천의 페이지·continuation·원래 추천 신원을 저장한다. 401/timeout은 실제 LATEST fallback으로 남고 성공 추천으로 바꾸지 않는다. 최근 48시간 완료 후보 포함 및 상위 10개 롤모델 조건을 실제 수락된 결과와 독립 대조한다. 빈 페이지·continuation·같은 커서 재조회도 context·생성 시각·카드 순서를 실제 저장 행과 대조한다. HTTP가 없는 페이지에는 가짜 HTTP 증거를 만들지 않는다. 새 구성/페이지 검증 파일은 raw index에 바이트 digest로 연결한다.

실제 요청에서 `feed-rules-v1`의 가중 점수·상위 40개 후보·MMR·입력 순서 동률·카테고리 간격·최종 구성을 독립 계산하여 Python 최종 순서를 대조한다. 순서만 변조해도 실패하며 `ranking-verification.json`을 raw index에 보존한다. 전체 데이터의 입력 순서 재현성과는 별도 검증이다. 계산 경계와 테스트 범위는 `feed-ranking-verification.md`를 따른다.

재실행마다 다른 UUID와 서명 커서는 typed logical ID로 별도 정규화한다. 실제 금액·시각·순서·거절·기간·권한·원본 bytes를 보존하며 결과가 다르다고 정렬해서 숨기지 않는다. 일부 두 DB 시나리오가 같다는 사실을 전체 100명 재현성으로 표현하지 않는다.

## 로컬 검증의 DB 보존

독립 export/검증 전후의 실제 DB 행·시퀀스·스키마 지문을 기록하고 차이를 거부한다. 같은 건수에서의 값 변경과 롤백 후 sequence 증가도 탐지한다. 비밀키·미디어 내용은 서버 내부에서만 해시하며 원본을 출력하지 않는다. 상세 범위와 한계는 `preservation-fingerprint.md`를 따른다. 이는 로컬 검증의 보존 증거이며 selective import/restore나 외부 Owner 콘솔 보존 완료를 뜻하지 않는다.

## 검증 및 현재 상태

기준 HEAD는 `67920c84d859969c218c057eab8021462390035d`이며 변경은 컨트롤러 생성 worktree에 미커밋으로 있다. 승인 OpenAPI는 `sha256:c028a6dd7ae5e22e941b250e2a81b232d28a8f4ad597fa53aeba2e3b88094710`이다. 최신 검사 명령·건수·exit code·파일 digest는 `verification-7bb590.json`, 이 액션의 변경 경로는 `action-files-7bb590.json`을 따른다. 환경 의존 Python parity skip과 UP-TO-DATE 검사는 새 실행 성공과 구분한다. bootJar 격리 검사는 artifact 구조 확인이다.

## 남은 작업과 적용 경계

HTTP 없는 요청의 추천 입력 재구성 여부, 동시 시각 후보/대표 재현성, 100명 전체 이력, canonical 데이터/CSV, selective apply/restore와 drift 방지, 대표 네 명의 API/UI/DB 흐름, 최신 Owner 콘솔 전체 보존 검증이 남아 있다. 최신 코드의 상세 지원 범위는 `implementation-status.md`의 첫 항목과 각 주제 문서를 따른다.

이 문서는 검토용 PR 본문 초안이다. commit·push·PR 생성·merge·deploy·현재 demo 데이터 적용·컨트롤러 게이트 승인을 수행하거나 증명하지 않는다. 별도 저장소 data/frontend 작업과 원격 적용에는 해당 컨트롤러 액션 및 authoritative read-back이 필요하다.


교체 후보의 정확한 primary key 집합에 대해 선택/보존 행 수·digest와 양방향 FK 경계를 계산한다. 복합 키·역참조·변경된 catalog를 실제 PostgreSQL에서 검증하며 선택 범위를 자동으로 넓히지 않는다. 이는 FK 경계 진단이며 JSON/비FK 참조나 전체 selective apply/restore의 검증을 대신하지 않는다. 자세한 범위는 `graph-boundary.md`, 이번 결과는 `verification-ec8281.json`을 따른다.
