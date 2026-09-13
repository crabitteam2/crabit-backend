# Historical snapshot before act-c3f0871a884a26b2393a274192ac7d32

This file preserves prior action provenance. It is not the current support or delivery status.

# 최신 구현 상태: 로컬 백업의 PostgreSQL 잠금 창 (act-7c1c6ce5ce5236eb4a482c61b06dba1b)

백업의 40개 테이블 잠금·잠금 획득 후 최신 지문 비교·같은 트랜잭션 내 export/검증/직렬화를 연결했다. 별도 writer의 행 변경과 테이블 DDL을 차단하고 일반 조회는 허용한다. 대기 중 commit, timeout, rollback 및 잠금 해제를 실제 PostgreSQL에서 검증한다. 범위와 한계는 preservation-window.md, 정확한 검증은 verification-7c1c6c.json을 따른다.

전체 backend 구현은 미완료다. 대상 신원 변환, 원자적 selective apply/restore, 운영 보존 경계와 100명 전체 재생·대표 UI·외부 Owner 콘솔 보존·실제 demo 적용은 남아 있다. 아래는 이전 액션 이력이다.

# 최신 구현 상태: PostgreSQL 임시 FK 검사 (act-7bb590f8a2b03228ccfaeb4af1fd1949)

관계형 임시 적재에 현재 카탈로그의 FK를 연결했다. 모든 행을 적재한 뒤 양쪽을 pg_temp로 한정해 실제 PostgreSQL이 전체 참조를 검사한다. 공개 부모 행이 있어도 임시 부모가 없으면 실패하며 원본 공개 행·시퀀스·스키마와 후속 정상 호출을 보존한다. 자세한 동작은 relational-staging.md, 이번 실행 증거는 verification-7bb590.json을 따른다.

이 단계는 로컬 typed import의 사전 검사다. 공개 테이블 교체, 대상 신원 변환, 원자적 selective apply/restore와 drift 방지, 전체 100명 재생·canonical/CSV·대표 UI·Owner 콘솔 보존·실제 demo 적용은 남아 있다. 이전 액션 이력의 미구현 목록은 각 후속 주제 문서와 구분한다.

# 현재 액션: PostgreSQL typed staging

act-38b89ac40c907004674f2f5cb746b6c0에서 관계형 export의 실제 SQL 타입 적재/read-back 사전 검사를 추가했다. 38개 임시 테이블의 CHECK·고유 index를 검증하고, 실패 시 rollback과 공개 DB 행·sequence·schema의 보존을 확인한다. 공개 domain import와 apply/restore는 아직 구현되지 않았다. 상세 범위는 relational-staging.md, 이번 실제 검증은 verification-38b89a.json을 따른다. 아래 기록은 이전 액션 당시의 범위이며 현재 전체 지원 목록으로 읽지 않는다. 현재 dispatcher는 승인 schema의 25종 명령을 지원한다.

# 관계형 export 입력 타입 검증

현재 replay DB 카탈로그에 바인딩된 strict JSON 읽기를 추가하고 기존 관계형 검증에 PostgreSQL 값 타입 검사를 연결했다. 실제 JOIN·GRANT·CREATE·DEPOSIT의 serialize/read-back과 변조 거부, DB·원본 보존을 확인한다. 상세 지원 범위는 `relational-input.md`, 이번 액션의 정확한 검증은 `verification-3719b8.json`을 따른다. 전체 typed import·대상 변환·원자적 apply/restore·100명 전체 검증과 demo 적용은 남아 있다. 아래는 이전 액션 이력이다.

# FK 외 참조 경계 검사

방문·페이지·공유 해제 이력·checkpoint의 명시적 참조를 선택/보존 양방향으로 검사한다. 실제 13개 명령과 PostgreSQL에서 83개 참조를 확인했고, SQL FK 경계가 없는 방문 행 선택에서도 의미 경계 20개를 찾는다. 범위와 제한은 `semantic-graph-boundary.md`, 현재 액션의 정확한 검증은 `verification-91801.json`을 따른다. 학생 idempotency/recap JSON, 전체 import/apply/restore와 실제 demo 적용은 미완료다. 아래는 이전 액션 이력이다.

# 현재 구현: 교체 후보 행의 FK 경계 검사

명시한 primary key의 행만 선택하고 선택/보존 양방향 FK, 복합 키와 partition digest를 검사한다. 범위를 자동 확장하지 않으며 FK closure는 적용 준비 완료를 뜻하지 않는다. 실제 PostgreSQL의 두 학생·한 위시와 보존 지문 검증은 `graph-boundary.md`, 정확한 결과는 `verification-ec8281.json`을 따른다. 전체 import/대상 graph/apply/restore 및 현재 demo 적용은 미완료다. 아래는 이전 액션의 이력이다.

# 현재 구현: 독립 검증 전후 DB 보존 검사

실제 replay의 독립 export/검증 전후에 40개 테이블·시퀀스·선택된 스키마 정의의 지문을 비교한다. 원본 비밀키/미디어 내용을 내보내지 않으며 같은 건수의 값 변경 및 롤백 후 sequence 증가를 감지한다. 상세는 `preservation-fingerprint.md`, 이번 검사 기록은 `verification-837464.json`이다. 전체 import 계약과 selective apply/restore·외부 Owner 보존·100명 전체 데이터·UI/demo 적용은 미완료다. 아래는 이전 액션 당시의 이력이다.

# 현재 구현: 실제 Python 추천 순서의 독립 대조

실제 wire request로 가중 점수·상위 40개 후보·MMR·동률·카테고리 간격 및 최종 구성 보정을 독립 계산하고 수락된 Python 응답 순서와 대조한다. 원본 바이트와 별도 검증 파일을 raw index에 보존한다. 상세 범위는 `feed-ranking-verification.md`, 이번 액션 결과는 `verification-0c6003.json`이다. 전체 100명 이력·apply/restore·UI·demo 적용은 미완료다. 아래는 이전 액션 이력이다.

# 현재 구현: 추천 구성 조건 및 모든 feed 페이지 저장 결과 대조

최근 완료·상위 10개 롤모델 구성 조건을 실제 수락된 Python 결과와 독립 대조하고, HTTP 호출 없는 빈 페이지·continuation·재조회에도 DB 원본과 결과 검증 파일을 남긴다. 새 파일은 runner의 raw index에 포함된다. `feed-composition-page-verification.md`와 `verification-9932dc.json`이 이번 액션의 범위와 검증 기록이다. 전체 점수/MMR·100명 재생·apply/restore 및 demo 적용은 미완료다. 아래는 이전 액션의 기록이다.

# 현재 구현: 추천 카테고리·유사도 원본 대조

실제 추천 요청의 카테고리, 대표 선택, 금액·기간 기반 기본 유사도와 제목 유사도를 원본 DB 및 고정 분류 모델에서 독립 계산한다. 실제 PostgreSQL/Python 실행과 필드별 변조 거부 검증은 `feed-similarity-verification.md`, 이번 액션의 정확한 결과는 `verification-df2b05.json`을 따른다. Python 순위 규칙·전체 100명 구현과 demo 적용은 미완료다. 아래는 이전 액션 이력이다.

# 현재 구현: 또래·이체 리캡의 두 DB 재현성

실제 Python을 호출하는 3명·14개 명령 재생에서 익명 또래 통계, 동일 시각 이체 효과, feed history sequence와 소수형 지표 직렬화를 보완했다. 상세는 `recap-reproducibility.md`, 이번 액션의 검증 결과는 `verification-bfc3e9.json`을 따른다. 전체 100명 구현과 demo 적용은 미완료다. 아래는 이전 액션의 이력이다.

# 현재 구현: 피드 후보·월 관측 범위 독립 검증

기록된 실제 Python 피드 요청의 후보 집합과 월 관측 범위를 원본 DB 행에서 독립 대조한다. 현재 범위·한계는 `feed-input-verification.md`, 이 액션의 검사 결과는 `verification-22e705.json`을 따른다. 아래는 이전 액션의 이력이다. 전체 backend 구현과 demo 적용은 미완료다.

# 현재 구현: 실제 추천 재생 정규화

CLI의 실제 Python 피드 교환 기록과 검증된 추천 ID 정규화를 연결했다. 상세와 한계는 `feed-normalization.md`, 현재 액션의 정확한 검사 결과는 `verification-5a8114.json`을 따른다. 전체 100명 재생·가져오기·UI·demo 적용은 미완료다. 아래는 당시 상태를 기록한 과거 액션 이력이며, 과거의 미구현 설명은 현재 상태를 뜻하지 않는다.

# 최신 구현 상태: 실제 Python 추천 페이지와 행동 연결

액션 act-765495eb5bb14d61dc57110696a096e9에서 명시적인 로컬 feed session을 dispatcher에 연결했다. 실제 추천 순위가 페이지·continuation·행동 문맥에 저장되며, 401의 LATEST 전환과 원본 증거도 보존한다. 현재 범위와 남은 작업은 feed-pipeline.md, 이 액션의 최종 검증은 verification-765495.json을 따른다. CLI feed 연결·추천 정규화·전체 100명/적용 검증은 미완료다. 아래 항목은 이전 액션 이력이다.

# 최신 구현: 실제 Python 피드 추천 실행 helper

액션 `act-49cd74bf04f90ee9ae04b43b641fc24c`에서 실제 PostgreSQL 후보·지표 입력을 기존 Java 클라이언트와 로컬 Python 추천 서비스에 연결하고 원본 request/response 및 fallback 결과를 보존했다. 상세는 `feed-execution.md`, 정확한 실행 증거는 `verification-49cd74.json`을 따른다. 아직 dispatcher FEED_QUERY와 페이지/노출 연결은 하지 않았으며 전체 구현·제품 적용은 미완료다. 아래는 각 액션 당시의 누적 이력이다.

# 최신 후속 검증: Python 성공 사례 결과와 조회 권한

액션 `act-81147b3528eb04010ba4e0d8e90a1aba`에서 최종 story 목록·유형을 독립 대조하고 실제 DB/Python 기반 조회 권한 변경 및 실패 주입 검사를 추가했다. 상세는 `recap-result-query-verification.md`, 검증 증거는 `verification-81147.json`을 따른다. 전체 구현과 제품 적용은 미완료다. 아래 항목은 이전 액션 이력이며 각 당시의 미구현 설명을 현재 상태로 해석하지 않는다.

## 리캡 성공 사례 후보의 원본 대조

종료 시점의 공유 카드·팔로우·차단·회원·위시 원본에서 성공 사례 후보를 독립 계산하고 실제 리캡 요청의 순서와 상위 5개를 대조한다. 권한·기간 조건을 어긴 추가 후보와 누락을 Python 호출 전에 거부한다. 실제 완료·공유 서비스와 Python HTTP 200, 변조 거부를 검증했다. 상세 범위는 `recap-story-verification.md`, 이 액션의 전체 검증은 `verification-a7b773.json`을 따른다. 작성자의 전월 지표와 최종 Python 선택, 전체 데이터셋·적용 작업은 계속 미완료다. 아래는 이전 액션의 누적 기록이다.

# 최신 구현: 리캡 또래 비교 원본 대조

액션 `act-c2b82bf7b19c285fb1f796650ffb9019`은 실제 Python 리캡 호출 전에 또래 집합·52주 활동·대표 위시 달성률과 본인 대표 선택을 원본 DB 행에서 독립 재계산한다. 검증 파일은 `period`와 `peers` 결과를 함께 보존한다. 상세 의미·한계는 `recap-peer-verification.md`, 정확한 실행 증거는 `verification-c2b82.json`을 따른다. 전체 backend 구현·100명 재현성·demo 적용은 미완료다. 아래는 누적 이력이다.

# 최신 구현: 리캡 재생 산출물 연결

액션 `act-2a6029121e8b79485919c8088ee89bfd`에서 명시적인 로컬 Python 설정이 있는 runner의 CLOSE_WEEK/CLOSE_MONTH를 활성화했다. 원본 논리 명령, 동결된 요청, snapshot input, 실제 HTTP response, PostgreSQL 저장 행을 구분하여 기록한다. NOT_ELIGIBLE에는 응답 파일이 없고 실패한 HTTP 결과는 원본 그대로 남는다. 두 독립 DB의 주간·월간 정규화 비교 및 실패·충돌 테스트를 추가했다. 정확한 검증은 `verification-2a6029.json`, 동작과 제한은 `recap-replay-export.md`를 따른다. 전체 backend 구현과 demo 적용은 계속 미완료다. 아래는 누적 구현 이력이다.

# 최신 구현: 실제 리캡 입력 준비

액션 `act-7d32db2f8c78996253c508c53744fb05`에서 실제 RecapSnapshotService/RecapGenerationCoordinator를 simulation runtime에 연결했다. 기간·신원·입력 cutoff 검사, 원본 입력 동결과 재호출 불변성, 월간 3회 입금 적격성 상태를 검증한다. 상세 범위는 `recap-preparation.md`, 실행 증거는 `verification-7d32db.json`을 따른다. Python 호출 및 CLOSE 명령 dispatcher 연결은 아직 미완료이며 전체 구현 준비 완료를 주장하지 않는다.

## 휴면 복귀 사건 재생 추가

`RETURN_FROM_DORMANCY`를 같은 학생의 마지막 앱 활동과 7~21일 공백에 연결한다. 타인·미래 참조와 중간 앱 활동은 거부하며, 지급·카드 소비·자동 마감은 앱 방문으로 세지 않는다. 원본 복귀 주석을 저장하고 실제 실행 결과와 다시 대조한다. 가입·잔액·위시·행동 행은 변경하지 않으며 복귀 후 기존 서비스를 그대로 사용한다.

`priorDormancyEventId`를 휴면 직전 앱 활동의 기준점으로 해석한 범위와 제한은 `dormancy-return.md`, 이번 액션의 정확한 검증은 `verification-b78043.json`을 따른다. 일반 backend/main 코드와 승인 계약 bytes는 이번 액션에서 변경하지 않았다. 전체 backend 구현 및 현재 demo 적용은 미완료다. 아래는 누적 구현 기록이다.

## 월 예산 검증 추가 (act-b38d1211b761a2bbed7d94de047ecd5c)

실제 GRANT 원장을 명령과 대조하고, 서울 기준 완전 관측한 계좌·월의 10,000~30,000원 조건을 검증한다. 지급이 없는 월도 포함하며, 가입월·진행 중인 월은 실제 합계만 기록한다. 원본 현금 증거가 정합적이어도 월 정책 위반 시 재생은 FAILED이며 원본 결과는 보존한다. 관측 범위는 마지막 실행 사건까지다. 전체 cutoff 검증을 대신하지 않는다.

이번 simulation 회귀 260개가 통과했다. 정확한 백엔드 회귀·산출물 격리 결과와 파일 해시는 `verification-b38d12.json`, 의미와 제한은 `monthly-budget-reconciliation.md`를 참조한다. 아직 annotation/기간/adjustment/correction, 실제 Python feed·recap, 100명 전체 이력·cutoff, selective apply/restore, 대표 API/UI/DB·Owner 콘솔 보존 및 현재 demo 적용은 미완료다. 아래는 이전 작업 기록이다.

# 현재 추가 구현: 멱등성 요청과 저장 응답 대조

`act-af65eee7b93abd409ba14dfa27fbba22`는 저장 fingerprint를 실제 성공 명령으로 독립 재계산하고, 최초 응답·시각·키 집합을 검증한 뒤 논리 fingerprint로 정규화한다. 17개 명령의 두 DB 비교에서 student를 포함한 38개 테이블 전체를 검사한다. 원본 증거는 유지한다. 상세 동작과 제한은 `idempotency-reconciliation.md`, 정확한 실행 결과는 `verification-af65ee.json`을 따른다. 전체 backend 구현 및 현재 demo 적용은 미완료다. 아래 기록은 이전 액션의 구현 범위다.

# 관계형 실행 신원 비교 projection

`act-eba05f33dee4bf694341a924a6e70526`에서 typed id-map과 실제 catalog에 따라 별도 `normalized-relational.json`을 기록한다. 원본은 보존하고 runtime fingerprint 등 불투명한 필드는 명시적으로 남긴다. 최종 canonical normalized bundle은 아직 아니다. 상세 동작·제한은 `relational-normalization.md`, 이번 액션의 실행 증거는 `verification-eba05f.json`을 따른다.

# 실제 재생 신원의 typed id-map export

실제 저장 상태에 없는 사전 할당 UUID까지 실행 신원으로 보이던 진단 목록과 별도로, 승인된 스키마의 `id-map.json`을 내보낸다. 실제 JOIN과 자동 관측·원장·효과·조정 케이스, 삭제된 공유 카드의 보존 이력에서 논리 ID와 소유 계좌를 연결한다. 중복·누락·가짜 파생 신원을 거부하고 두 독립 DB의 논리 신원 재현성을 검사한다. 자세한 동작과 범위는 `replay-identity-map.md`, 액션 `act-c07a2a314aaabbd9fb3338b04542b6d5`의 검증은 `verification-c07a2a.json`을 따른다. 전체 관계형 정규화·100명 전체 행동 이력·Python·apply/restore·UI·Owner 보존·demo 적용은 아직 미완료다.

# 최신 구현: 실행 간 재사용 가능한 피드 다음 페이지 참조

액션 `act-ca37db6ca3d708b956f682d766586303`에서 `event:<eventId>:nextCursor`를 이번 재생의 실제 선행 응답 커서로 해석한다. 원본 논리 명령 바이트를 유지하고 인과 관계·학생/학원·성공 응답을 확인하며 실제 서비스가 만료를 판정한다. 두 독립 PostgreSQL에 동일한 9개 명령을 재생하고 응답 정규화 동일성과 만료 거절을 검사했다. 상세 의미는 `feed-continuation.md`, 정확한 검증은 `verification-ca37db.json`을 따른다. 전체 구현과 현재 demo 적용은 계속 미완료다. 아래는 이전 구현 이력이다.

# 행동 export와 실행 명령의 대조

행동 테이블의 FK가 맞아도 누락된 수집 결과, 잘못된 작성자나 exposed_event_id가 탐지되지 않았다. 재생기는 실제 export를 보존한 뒤 성공 명령과 문맥·행동·방문 증거의 정확한 집합, 페이지 순서, 시각과 작성자·노출 연결을 독립 대조한다. 노출 없는 클릭과 같은 시각 클릭 이후 노출을 허용하며, 비공개 전환으로 현재 카드가 사라진 뒤에도 과거 신원과 방문 증거를 유지한다. 테스트·한계는 `behavior-reconciliation.md`, 이 액션의 정확한 검증은 `verification-d7842d.json`을 따른다. 전체 backend 구현과 현재 데모 적용은 미완료다. 아래 기록은 이전 구현 이력이다.

# 최신 구현: 관계형 시각·가입·피드 이력 검증

액션 `act-f6d498e73fb9444ab8dd2eeae2d29c00`에서 보존된 관계형 export에 마지막 실행 시각·가입 전 사건·정확한 학생/계좌/membership 바인딩과 피드 이력 payload/FK/구간/현재 행 대조를 추가했다. 상세 의미와 제한은 `relational-domain-verification.md`, 검증 결과는 `verification-f6d498.json`을 따른다. 기존 간헐적 feed-history 역행의 근본 원인 해결과 전체 기능 구현은 계속 미완료다. 아래는 이전 구현 이력이다.

# 최신 구현: 관계형 상태와 SQL 외래 키 대조

액션 `act-81098d7fc6102f969688c8d0361fc778`에서 실제 재생 DB의 38개 고정 테이블과 신뢰 스키마를 export하고 PK/UNIQUE/복합 FK·가입 학생/계좌 집합을 독립 대조한다. 커서 서명 키는 읽지 않으며 사진/외부 작업 행이 있으면 export를 거부한다. `state/relational.json`과 `state/relational-catalog.json`을 검증 전에 기록한다. 상세 의미와 남은 범위는 `relational-export.md`, 정확한 검증은 `verification-81098.json`을 따른다. 전체 도메인 검증·canonical import bundle·현재 demo 적용은 계속 미완료다. 아래는 이전 구현 이력이다.

# 최신 구현: 위시 배분 원장과 저장 잔액 검증

액션 `act-de6bbd9563b813168ca68c2a9b78b1d1`에서 실제 DB의 wish/ledger/effect 부분 export와 독립 합계·참조·명령 금액 대조를 추가했다. `simulationRun`이 state/allocation.json을 대조 전 보존한다. 상세 동작과 한계는 allocation-export.md, 정확한 검증은 verification-de6bbd.json을 따른다. 전체 관계형 export와 전체 도메인 검증은 계속 미완료다.

# 최신 구현: 실제 재생 DB의 현금 export와 독립 대조

액션 `act-516139d3b709a99c91f7c7a8932aa6c6`에서 `simulationRun`에 실제 DB 현금 원장·잔액의 일관된 읽기, `state/export.json` 기록과 독립 oracle 대조를 연결했다. 가입이 완료된 계좌만 포함하고 아직 가입하지 않은 학생의 잔액은 생성하지 않는다. 캡처한 state를 대조 전에 보존하며 DB를 보정하지 않는다. 상세 동작은 `cash-export.md`, 정확한 검증은 `verification-516139.json`을 따른다.

현금 부분 export이며 전체 관계형/FK/CSV·완성된 id-map/normalized bundle·ADJUST/CORRECT·기간/annotation·Python·100명 전체 행동 이력·apply/restore·UI·Owner 콘솔 보존·현재 demo 적용은 미완료다. 아래는 이전 구현 이력이다.

# 최신 구현: 실제 응답과 서명된 페이지 문맥의 정규화

액션 `act-7538a1a5ec926ba07b549022ee34b002`에서 실제 응답의 종류별 UUID 정규화와 검증된 커서 문맥 비교를 추가했다. 두 독립 PostgreSQL의 17개 실제 명령 응답을 비교하며 원본 UUID/서명은 달라도 정규화 해시가 같아야 한다. `simulationRun`은 전체 요청 실행과 정규화가 성공하면 `normalized-responses.json`과 별도 해시를 기록한다. 원본 파일을 치환하지 않는다. 상세 범위는 `response-normalization.md`, 정확한 검증은 `verification-7538a1.json`을 따른다.

응답 부분의 정규화이며 최종 bundle normalized.json·완성된 id-map·DB graph/FK/CSV·자동 생성 관측·조정·기간/annotation·Python·100명 전체 이력·apply/restore·UI·콘솔 보존·현재 demo 적용은 미완료다. 아래는 이전 구현 기록이며 최신 상태는 이 문단과 위 검증 파일을 우선한다.

# 최신 구현: 실제 명령 재생과 원본 파일 기록

액션 `act-94bde2b9f6c2742591424182b4405c4b`에서 `simulationRun`·새 출력 전용 journal을 추가했다. 지원 21종 명령을 새 PostgreSQL에서 실행하고 원본 논리 명령 바이트와 실제 도메인 응답을 경로별 SHA-256으로 기록한다. 결과 불일치 시 실제 응답을 보존하고 후속 실행을 차단한다. 기존 파일과 입력 bundle을 덮어쓰지 않는다. 상세 형식과 한계는 `replay-recording.md`, 새 전체 검증 증거는 `verification-94bde2.json`을 따른다.

출력은 부분 재생 증거이며 완성된 canonical bundle/apply 입력이 아니다. typed ID 정규화·관계형 상태/FK/CSV·ADJUST/CORRECT·기간/annotation·Python·100명 두 DB·apply/restore·UI·콘솔 보존·현재 demo 적용은 미완료다. 승인된 OpenAPI/schema, main 서비스·migration은 이번 액션에서 변경하지 않았다. 아래는 기존 구현 이력이다.

# 최신 액션: 실제 피드·노출·클릭 재생

`act-9873e1ac93d6d430b8998af55c2d3ff1`은 실제 `BehaviorService.createResult/collect`에 FEED_QUERY·IMPRESSION·CLICK을 연결하여 총21종을 실행한다. 실제 페이지 순서를 기대값과 비교하고 불일치 시 원본 응답을 보존하며 중단한다. 노출 없는 클릭을 허용하고 실제 권한·중복 노출·만료·privacy/재공유를 검증한다. 현재 피드는 LATEST이며 Python 추천은 미검증이다. 상세 동작과 신원 규칙은 feed-behavior.md, 최신 실행 증거는 verification-9873e1.json을 따른다. 전체 replay/export/Python/100명 두DB/apply/restore/UI/demo 적용은 계속 미완료다. 아래는 이전 액션 이력이다.

# 최신 구현 상태: 실제 프로필 방문 재생

액션 act-6a6c30ee26746a8de397c282303bdb61에서 PROFILE_VISIT을 실제 BehaviorService와 불변 방문 분류 증거 저장에 연결했다. 이제 기존 17종을 포함하여 18종 명령을 지원한다. 시뮬레이션 테스트 189개가 실제 통과했으며 전체 backend 구현은 계속 미완료다. 상세 동작·남은 범위는 profile-visits.md, 정확한 실행 증거는 verification-6a6c30.json을 따른다. 아래는 이전 액션 이력이다.

# 최신 구현 상태: 논리 명령의 실제 서비스 실행

액션 act-9ca1a088b35fe45e6e01f4b15c84b4b6는 simulation 전용 `SimulationCommandDispatcher`를 추가했다. 100명 입력을 검증하고 신규 로컬 DB에 학원/BUILDING 데이터셋을 만든다. 학생·계좌·membership은 각 JOIN 시각에 생성하며 Owner의 고정 UUID를 사용한다. 실제 Java/PostgreSQL 동기 시각에서 현금·관측·위시·소셜 17종 명령을 실행한다. 실제 결과가 입력 outcome과 다르면 원본 결과를 별도 보존하고 후속 명령을 차단한다. 상세 범위는 `command-dispatcher.md`를 참조한다.

전체 bundle replay CLI·모든 논리 신원 정규화·원본 artifact materialization·ADJUST_ALLOCATION/CORRECT·feed/behavior/period dispatch·전체 relational export/FK/CSV·실제 Python·100명 두DB 재현성·selective apply/restore·대표 UI·Owner 콘솔 보존·현재 demo 적용은 미완료다. 100개 JOIN 테스트는 전체 100명 행동 이력 검증이 아니다. 이전 시각 역행 회귀의 근본 원인도 별도 미해결이다. 최신 실제 테스트 결과는 verification.json에 기록한다. 아래는 이전 액션 이력이다.

# 백엔드 구현 인계

Feature Run `demo-student-behavior-simulation`의 기존 backend action `act-89077dceb41a842f1f610df6c068a5c8`이 남긴 구현을 action `act-7754a6833f80f96a0d14c1fd61718b23`에서 점검·보강했다. 기준 HEAD는 `67920c84d859969c218c057eab8021462390035d`다. 계약에서 이미 변경한 `api/openapi.yaml`은 그대로 보존했다. 커밋·원격 쓰기·배포·데모 적용은 수행하지 않았다.

## 구현한 동작

`CRABIT_DEMO_SIMULATION_ENABLED=true`에서만 가상 잔액 제공자와 대표 레지스트리를 활성화한다. 기본값은 false다. 활성 데이터셋의 열린 비Owner 계좌와 현재 membership을 확인하고, 현금 원장 합계·sequence·건수가 저장 잔액과 일치할 때만 가상 현금을 반환하며 관측에 `SIMULATION`, dataset ID와 원장 sequence 참조를 함께 저장한다. 미등록·비활성 계좌는 실패하며 외부 호출하지 않는다. Owner도 활성 데이터셋·열린 계좌·원래 학생 신원·현재 membership이 일치할 때만 기존 HTTP 제공자를 사용한다. `CRABIT_DEMO_OWNER_LOOKUPS_PAUSED=true`를 설정하면 요청 ID 생성 이전에 외부 호출을 차단한다. 실제 외부 콘솔의 전후 보존 검사는 별도 실행해야 한다.

가상 현금 명령은 dataset 행을 먼저 잠그고 계좌와 가상잔액 행을 잠근 뒤 지급·구매를 원장과 잔액에 같은 트랜잭션으로 기록한다. 동일 event ID·입력은 중복 반영하지 않으며 다른 입력은 충돌한다. 음수·범위 초과·역행 시각·가입 이전 사건을 거부하고 BUILDING 재생은 고정 기간 안으로 제한한다. 위시 배분·관측·조정을 자동 생성하지 않는다. APPLIED Owner의 가상 현금 변경은 거부한다. 역사 시각을 받는 `apply`는 BUILDING에서만 동작한다. APPLIED에서는 `applyCurrent`가 잠금 후 DB 현재 시각을 사용하므로 현재 사건을 과거로 삽입할 수 없다. 일일 작업은 보존 창에서 Owner를 sync 전에 건너뛴다. prod에서는 simulation 플래그만으로 빈이 활성화되지 않으며 demo+prod 동시 활성화는 거부한다. 이 서비스는 내부 Java 명령이며 HTTP 관리 엔드포인트는 없다.

대표 등록은 학년별 25명·총100명·Owner1명·단일 학원·열린 계좌와 대표의 활성 membership을 확인한다. `grade-3`부터 `grade-6`까지 네 매핑과 `CRABIT_DEMO_TOKEN_GRADE_3`..`6`을 검증하며 기존 신원·자격증명과 충돌하면 시작을 거부한다. UUID·토큰은 클라이언트가 지정하지 않는다. 기존 여섯 persona의 의미는 유지한다.

V21은 데이터셋·계좌 매핑·대표·가상 현금 원장·적용 journal을 추가하고 balance_observation에 출처를 추가한다. V1..V20과 기존 불변 이력 trigger는 변경하지 않았다. 데이터셋 등록이 존재하면 기존 seed를 추가하지 않고 legacy reset을 거부한다. 데이터셋이 없는 종전 e2e reset만 새 빈 테이블을 기존 FK closure에 포함한다. 이 reset은 데이터셋 apply/restore 수단이 아니다.

## 아직 구현/검증되지 않은 범위

이 변경만으로 전체 구현 준비 완료를 주장하지 않는다. artifact payload의 닫힌 domain schema와 재생 검증기, src/simulation 역사 재생 실행기·실제 PostgreSQL 프로세스 시계 제어, 시간순 도메인 재생 및 실제 Python feed/recap 실행, typed selective apply/restore 관리 경로와 복제DB 검증이 남아 있다. application journal 테이블의 존재는 적용 실행기 또는 안전한 복원 경로의 구현 증거가 아니다.

100명 전체 역사 데이터 재생·독립 두DB 재현성·기간 누출 검사, 대표 네 명의 실제 Next/API/DB UI 검증, 외부 콘솔 최신 전체 fingerprint 보존 및 현재 데모 적용은 실행하지 않았다. 등록된 명령 목록은 원격 실행 권한이 아니며 이 action으로 원격 적용하지 않는다. simulationTest는 번들 입력 검사와 현금 oracle/실제 PostgreSQL 비교를 실행한다. simulationRun은 아직 없으며 simulationInspect의 ADMITTED를 domain 검증 성공으로 사용하지 않는다.

## 계획 경로 밖 변경

SIMULATION 출처를 기존 관측 트랜잭션에 원자적으로 저장하기 위해 balance/CardBalanceProviderResult.java, balance/CardBalanceSyncService.java, wish/BalanceObservation.java, wish/CardBalanceObservationService.java를 변경했다. e2e/PostgresMigrationIT.java와 e2e/RecapStorageMigrationIT.java의 기존 migration 개수·테이블 수 기대값은 V21 추가에 맞췄으며 이전 migration checksum과 데이터 보존 검사는 유지한다.

## 검증

최종 명령·성공/실패/생략 수와 정확한 파일 digest는 `verification.json`을 참조한다. 실제 PostgreSQL 테스트는 새 전용 컨테이너를 사용한다. H2 관측 저장 검사는 가상 출처가 commit 후 유지되고 다음 실제 provider 관측과 체인으로 연결되는지 검사한다. `bash scripts/demo/verify-runtime-isolation.sh`는 bootJar 구조만 검사한다.

## 이전 액션 act-22b865e5303f1bd7a64f6835518ea958 (입구 형식은 후속 액션에서 대체)

별도 simulation source set, version 1 manifest schema, checksum·파일 경로·엄격한 JSON·인구/대표 매핑 검사, 공유 거부 벡터와 읽기 전용 simulationInspect를 추가했다. 자세한 입력, 해시 의미와 미구현 경계는 `command-envelope.md`를 따른다. 기존 main 코드와 승인된 OpenAPI bytes는 이번 액션에서 수정하지 않았다. manifest 입구 통과는 전체 bundle domain 유효성 또는 적용 준비 완료가 아니다.


## 후속 액션 act-97b123768b2e63055e82c89f0cadf5f9

`src/simulation`에 독립 현금 oracle과 읽기 전용 `simulationCashCheck`를 추가했다. 성공한 GRANT/PURCHASE 명령에서 잔액을 재계산하여 원장의 각 항목·시각·금액·계좌별 sequence·최종 export를 비교한다. 실패/거절 명령은 원장을 만들 수 없다. 가입 전 사건, cutoff 포함 이후 사건, 미래 지급 당겨오기, 잘못된 KST 예산 월, 중복 ID, 역순, 음수/안전 정수 상한 위반을 거부한다. allocation 명령은 현금 변경 명령으로 받을 수 없다. 최초 자금은 V21의 0원 시작에 맞춰 명시적 GRANT로 기록한다.

cash projection의 닫힌 스키마와 독립 JSON 거부 벡터는 이 검증기 전용이다. 전체 bundle의 EVENTS/CASH_LEDGER에서 projection을 생성·대조하는 연결은 아직 없다. 따라서 빈 bundle 입구 fixture의 ADMITTED는 여전히 도메인 검증이 아니다. 현금 CLI는 CASH_VERIFIED와 readyForApplication=false/fullDatasetValidationPerformed=false를 함께 반환한다. 실제 PostgreSQL 테스트는 production 현금 서비스의 성공, 잔액 부족 거절, commit 전 예외 롤백을 실행하고 DB에서 읽은 원장/계좌를 oracle과 대조한다. 이 검사는 역사 DB 프로세스 시계 동기화나 전체 100명 재생을 증명하지 않는다.

당시 확인 사항(후속 액션에서 아래 입구 형식 차이는 수정): 기존 manifest 입구 형식은 승인된 contract-design-v1의 최종 canonical bundle과 아직 다르다. 현재 students/representatives 내장 구조, datasetId 계산 및 files 필드(bytes)는 최종 제안의 별도 students/personas, configDigest 기반 datasetId, byteLength/recordCount 및 runtimeVersions/logicalDigest와 일치시키는 작업이 필요하다. 후속 액션은 이 차이를 해소하고 shared schema bytes를 data 저장소에 전달해야 한다. 이번에는 승인된 OpenAPI를 수정하거나 이 provisional schema를 적용 계약으로 승격하지 않았다.


## 최신 액션 act-4f26f102e5530784c39cde833d2bb59c

승인된 제안의 manifest 필드, configDigest 기반 datasetId, runtimeVersions 및 byteLength/recordCount를 반영했다. 학생/대표를 students.json/personas.json으로 분리하고 단일 학원·대표 계좌 매핑을 검증한다. schema 원본 bytes도 bundle에 포함하여 외부 신뢰 schema와 비교한다. 경로 정렬·중첩 경로·대소문자 충돌·symlink 부모·목록 밖 디렉터리·NDJSON 건수·normalized projection hash를 검사한다. Python fixture의 독립 datasetId 계산 및 Ajv2020의 문서/거부 벡터 검증을 추가했다. 이전 provisional fixture는 새로운 bundle-contract-valid로 교체했다.

현금 oracle의 월별 지급 집계는 scheduledAt의 예산 월이 아니라 occurredAt의 한국 월을 사용하도록 수정했다. 예정 월은 그대로 검사/보존하며, 지연 지급이 7월 1일에 발생하는 경계 테스트가 이를 검증한다.

최종 simulation 검사 73개는 모두 통과했다. 일반 전체 검사 697개는 695 통과·1 실패·1 생략이었다. 실패는 StudentFollowApiIT의 feed_source_history_check로 valid_to가 valid_from보다 이른 행이다. 실패 전체 실행은 full-suite-observation.json에 보존했다. 단독 재실행 결과와 정확한 파일 digest는 최신 verification.json을 확인한다. 단독 통과가 전체 실패 기록을 대체하지 않는다. Python parity 생략은 계속 미검증이며 전체 구현/게이트 준비 완료를 주장하지 않는다.

입구와 현금 부분의 보강이며 EVENTS 명령 union·고정 relational export·id-map/RAW sidecar/legacy CSV schema·full oracle·역사 replay·실제 Java/PostgreSQL 시계·Python·apply/restore·제품 적용은 남아 있다. 다른 저장소에 schema를 복사하지 않았다. 승인된 OpenAPI 원본 bytes와 main 서비스 코드는 이번 액션에서 수정하지 않았다.


## 사건 계약 및 인과관계 검사 보강 (act-74b9245bda9ae2d44cc35c643c45bbf9)

기존 번들 입구에 25종 사건의 닫힌 command/outcome 스키마와 실제 `events.ndjson` 연결을 추가했다. 입력의 미래/중복 참조, 가입·소유자·소셜 방향, 현금 지급 예정 시각, 이체 ID, 명시한 영향 신호 및 KST 기간 마감 순서를 검증한다. 선행 노출 없는 유효한 클릭은 허용한다. 자세한 wire 형식과 현재 검사 한계는 command-envelope.md에 기록했다. Java 거부/허용 테스트와 독립 Ajv2020 공유 벡터를 추가했다. 최종 결과는 verification.json을 따른다.

ADJUST_ALLOCATION/CORRECT, typed id-map/state/RAW/validation/CSV, 실제 명령 실행 및 독립 최종 상태 검증, 역사 Java/PostgreSQL clock, Python, 100명 두DB 재현성, apply/restore와 제품 검증은 여전히 미완료다. source/result 참조의 존재는 해당 결과가 실제 실행됐다는 증거가 아니다. 승인된 OpenAPI와 기존 main 서비스·migration은 이 액션에서 수정하지 않았다. 이전 간헐적 feed history timestamp 실패의 원인 수정도 하지 않았으며 새 전체 실행 결과와 과거 실패를 구분한다.

## 원본 증거·재생 신원 검사 (act-576a900ec49181b892aeee5c5e0a894c)

`id-map.json`, `raw/index.json`, `validation.json`을 닫힌 versioned 객체로 정의하고 번들 입구에 연결했다. 재생 UUID와 논리 ID의 종류별 일대일 대응, 100명 계좌/학생 및 학원 매핑의 정확한 집합, 파생 신원의 계좌 참조를 검사한다. target UUID를 재생 매핑에 섞을 수 없다. 모든 원본 파일은 typed metadata와 실제 바이트 해시/길이 및 해당 사건의 artifact reference가 일치해야 한다. 원본 바이트는 역직렬화·재작성하지 않는다. 검증 보고서는 dataset/config/schema digest와 연결하고 NOT_RUN에 실행 건수를 기입하거나 FAIL의 오류를 숨길 수 없다. PASS 표기 자체는 실행 증거로 인정하지 않는다.

독립 Ajv와 Java 검사가 같은 닫힌 형식을 검사한다. 실제 reader 테스트는 해시를 갱신한 악성 매핑을 거부하고, opaque raw bytes를 그대로 반환하며, 원본 metadata가 없으면 거부한다. 최종 검증 결과는 verification.json에 기록한다. 기존 main 서비스, V1..V21 migration 및 승인된 OpenAPI는 이번 액션에서 변경하지 않았다.

잔액 조정과 정정 실행 바인딩, 전체 relational state/legacy CSV, 실제 Java/SQL 시간 제어 및 도메인 재생·Python·두DB·selective apply/restore·대표 UI·콘솔 보존·현재 데모 적용은 미완료다. 이 액션은 Feature Run/액션 상태를 변경하거나 commit/원격 작업을 실행하지 않았다.


## 번들 현금 대조 연결 (act-9200dce2c41aed40c01cea4976eed526)

`state/export.json`을 닫힌 cashState schema로 검사하고, manifest의 dataset ID·students의 계좌/가입·원본 events의 현금 명령과 직접 연결했다. ledger/100개 최종 잔액을 독립 재계산하며 실패 원장·다른 입력의 일관된 가짜 ledger/cache·계좌 누락·시각/sequence 변조·배분으로 만든 현금을 거부한다. cash state는 전체 relational export가 아닌 현금 부분 export이며 일반 SQL/table 입력을 받지 않는다. oracle는 DB나 bundle을 수정하지 않는다.

실제 bundle의 체크섬을 갱신한 후에도 모순을 거부하는 테스트, microsecond 경계 및 Java/Ajv 공유 거부 벡터를 추가했다. 최신 실행 결과는 verification.json을 따른다. simulationInspect는 cashReconciliationPerformed=true와 전체 domainValidationPerformed=false/readyForApplication=false를 함께 반환한다. 부분 월/활성 월 예산과 사건 결과의 실제 실행 여부는 이 연결만으로 검증되지 않는다.

기존 도메인에 별도 정정 서비스가 없음을 확인했다. ADJUST_ALLOCATION/CORRECT 실행 바인딩은 계속 미완료이며 승인된 OpenAPI나 main 서비스에 임의 명령을 추가하지 않았다. 전체 relational state/CSV·역사 Java/SQL clock·Python·100명 두DB·apply/restore·UI·콘솔 보존·데모 적용 및 과거 간헐적 feed history 시각 실패 원인 수정은 남아 있다. Feature Run/action state, commit, provider 상태는 변경하지 않았다.


## 실제 PostgreSQL 시계 기반 (act-c70e92eb2e72b00c3523f99ff79a2934)

simulation 전용 Docker 이미지와 `SimulationPostgresClock`을 추가했다. 새 로컬 DB만 소유하며 기존 DB 입력·volume·재사용이 없고 loopback port만 게시한다. V1..V21 migration부터 SQL 실제 process wall-clock을 고정하고 각 동기 트랜잭션의 전후·commit 후에 SQL 세 시각과 Java Clock을 대조한다. 마이크로초·기간·역행·중첩을 검사하고 callback 실패는 rollback하되 시각은 되돌리지 않는다. 두 독립 DB 시계는 격리된다.

`simulationClockCheck` 관측과 실제 migration/feed history/checkpoint/이력조회/rollback 검사는 simulation-clock.md와 clock-observation.json에 설명했다. main 코드·migration·OpenAPI·canonical bundle schema는 이 액션에서 변경하지 않았다. 배포 JAR 구조 격리도 검사한다. 전체 사건 runner의 Spring 서비스 Clock 연결·실제 도메인 실행·Python·100명 두DB·selective apply/restore·제품 적용은 남아 있다. 기존 간헐적 feed history 시각 역행 원인도 별개로 미해결이다.

## 관측 export 및 참조 대조 (act-ff5681df24bbac09ccf8303226120202)

실제 관측·가상 현금·lookup version을 export하고 성공 체인·실패 의미·SIMULATION provenance·현금 sequence 잔액·CARD_BALANCE_CHANGE 및 입금 관측 FK를 독립 대조한다. `observation-export.md`에 실행 의미와 한계를 기록했다. `verification-ff5681.json`이 이 액션의 테스트 및 파일 증거다. 기존 승인 OpenAPI, canonical dataset schema와 main 도메인 코드는 수정하지 않았다. ADJUST/CORRECT와 전체 relational/Python/apply/restore 구현은 계속 미완료다.

## 후속 액션 b0ee09 — 실제 Python 리캡 실행 helper

기존 production RecapPythonClient를 재사용하는 simulation 전용 실행, bounded raw HTTP 증거, 실제 coordinator 저장·read-back을 추가했다. 실제 Python 주간/월간 3,000원 결과와 owner service 조회, 401 실패 보존, NOT_ELIGIBLE 호출 생략을 검사한다. 자세한 명령/제한은 recap-execution.md에 기록한다. CLOSE_WEEK/CLOSE_MONTH dispatcher 연결 및 전체 dataset/적용 작업은 아직 남아 있다. 일반 서버/API 계약 변경은 없다.
