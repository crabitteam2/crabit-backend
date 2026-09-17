# FK 외 참조의 선택·보존 경계

`SimulationCommandDispatcher.inspectSemanticGraphBoundary(export, selection)`은 현재 runtime catalog, SQL 키·FK 및 기존 source-history 도메인 검사를 거친 관계형 export에서 명시한 primary key만 선택한다. 기존 FK 보고서와 별도로 다음 참조의 선택→보존·보존→선택 경계를 반환한다.

- `feed_source_history`의 현재 원본 행, 인접 이력 버전, payload 내부의 FK 대상. 버전 전체를 하나의 참조 사슬로 연결하므로 중간 이력만 선택해도 경계가 드러난다.
- 방문 증거의 학생·학원, `(actor_id,event_id)`가 같은 실제 PROFILE_VISIT, 정확한 wish/card/account 이력 버전, collection baseline. `history:N`은 N 이하 전체 이력에 연결한다. 이는 권한에 필요한 membership/follow/block의 존재와 부재에 대한 전역 상한을 보존하기 위한 보수적 경계이며 관련 학생만 임의 추출하지 않는다.
- 피드 페이지의 viewer·학원과 ranked/returned/item 카드 배열 및 latest card, 행동 결과·노출·사건의 카드 참조.
- checkpoint의 `active_wishes[].wishId`.
- 학생 `wish_idempotency_records`의 operation에 따른 target, 원본·이동 목적지 snapshot의 위시/계좌, ledger event. CREATE와 TRANSFER의 target은 계좌이고 나머지 승인된 operation의 target은 위시다. 임의의 idempotency key는 보고서에 노출하지 않는다. snapshot은 필수이며 지원하지 않는 사진 참조는 거부한다.
- 리캡 `request_json`, `view_json`, `internal_metrics_json`에 저장된 JSON의 알려진 `generation_id`, `student_id`, `account_id`, `card_balance_account_id`, `academy_id`, `wish_id`, `representative_wish_id`, `root_event_id`. 객체·배열을 따라 중첩 경로를 기록한다. null은 참조가 아니며 빈 객체도 참조 경계를 만들지 않는다. 잘못된 JSON, 중복 키, trailing JSON, 비객체 최상위 값, 64단계를 넘는 중첩 및 참조 위치의 숫자/배열/객체는 거부한다.

공유 해제 후 사라진 `shared_card`는 보존된 최초 source-history 버전으로 연결하고 버전 사슬을 따라 나머지 이력도 묶인다. 현재 카드가 남아 있으면 현재 행과 역사 행 모두 연결한다. 삭제된 공유 카드를 잘못된 dangling FK로 취급하지 않는다. 없는 카드·학생·위시, 다른 타입의 배열 요소, 잘못된 방문 사건, 없는/다른 종류의 이력 버전, 없는 high-water를 거부한다. PK 값은 보고서에서 digest로만 표현한다. 보고서를 만들면서 DB·원본 export·선택 집합을 변경하지 않는다.

`coveredReferencesClosed`는 명시된 참조와 SQL FK에 경계가 없다는 뜻이다. `remainingCoverage`에는 지원하지 않는 미디어와 제품 불변식·정확한 교체 범위를 명시한다. 현재 승인된 데이터 형식을 검사하며, 미래에 추가될 필드를 현재 작업의 완료 조건으로 삼지 않는다. 현재 분석은 replay가 소유한 완전한 새 DB에 한정하며 retention으로 일부 이력만 남은 임의 운영 DB를 수용하는 도구가 아니다. 전체 semantic graph 검증이나 typed import 계약이 아니며 `readyForApplication`은 항상 false다. 호출자가 source 배열에서 참조를 지운 것을 모든 도메인 의미에서 검증하는 독립 oracle로도 사용하지 않는다.

실제 PostgreSQL 검사에서는 JOIN 2회, CREATE/SHARE 각 2회, FEED_QUERY 3회(최초·continuation·동일 cursor 재조회), PROFILE_VISIT, PRIVATE 변경, GRANT, DEPOSIT의 13개 명령을 수행한다. 공유 해제된 카드가 이전 페이지와 방문 증거에 남고 입금 checkpoint에 active wish가 생긴 상태를 검사한다. 이전 액션 `act-91801`에서는 83개의 의미 참조를 확인했으며 방문 행만 선택하면 SQL FK 경계는 없어도 의미 경계 20개가 나타난다. 전체 선택, 보존 방향, 입력 순서 불변성, 위조 참조 거부와 검사 전후 DB 지문·원본 바이트 불변성을 확인한다. 정확한 명령과 결과는 `verification-91801.json`, 관측은 `build/simulation-semantic-graph/`에 남긴다.

전체 typed import·학생/학원 교체 범위·백업/revision 바인딩·원자적 selective apply/restore와 실패/동시성 검증은 계속 필요하다. 데이터 저장소의 100명 전체 생성·canonical/CSV 조립과 프런트엔드 대표 UI 검증도 별도 작업이다. 현재 demo, 외부 Owner 콘솔, 승인된 OpenAPI/schema, Feature Run/action state를 수정하지 않았다.

이번 `act-1e6f` 검증은 실제 JOIN·GRANT·CREATE 2회·DEPOSIT·TRANSFER·CLOSE_WEEK의 7개 명령과 실제 Python 리캡 생성을 사용한다. 학생만 선택한 경우 목적지 snapshot과 ledger 참조, 리캡만 선택한 경우 원장·계좌 참조, 원장만 선택한 경우 보존된 리캡에서 들어오는 참조를 확인한다. 전체 선택은 경계가 없고 검사 전후 DB 지문과 원본 export는 동일하다. 없는 target/event/snapshot ID, 각 리캡 JSON 칼럼의 잘못된 ID·타입·문법을 거부하는 검사도 포함한다. 결과와 파일 digest는 `verification-1e6f.json` 및 `action-files-1e6f.json`에 기록한다.

이 검사는 참조 경계만 확인한다. JSON에서 필드가 삭제되거나 알려진 필드에 의미상 잘못된 기존 ID가 지정된 경우까지 검증하는 전체 리캡/멱등성 oracle은 아니다. 해당 제품·원본 명령 검증은 기존 독립 검증기와 함께 수행해야 하며 `coveredReferencesClosed`만으로 적용 가능성을 판단할 수 없다.

## 합성 잔액 출처의 현금 원장 참조

`balance_observation.simulation_source_ref`의 `cash:<account UUID>:<sequence>`를 계좌와 현금 순번에 연결한다. 순번 0은 현금 사건을 발명하지 않고 simulation 계좌에 연결하며 잔액 0을 확인한다. 양수 순번은 해당 현금 사건으로 연결하고, 현금 사건 사이에는 `previous_sequence` 참조를 두어 첫 지급부터 해당 순번까지 완전한 원장 구간을 보존한다. 현재 simulation 계좌 캐시도 최종 순번에 연결한다. 이 구조는 관측별로 전체 현금 원장 구간을 반복 나열하지 않으면서 구간 중간의 누락과 양방향 선택 경계를 드러낸다.

계좌·데이터셋, 연속 순번, 지급/소비 합계와 캐시, 시간 순서 및 관측 잔액을 검증한다. 다른 계좌 문자열, 비정규 순번·범위 초과·없는 순번, 미래 현금 참조와 오래된 순번을 거부한다. 잔액이 우연히 동일한 오래된 순번도 허용하지 않는다. 같은 시각에 순차 실행된 관측과 후속 지급은 유효하며, 실패 관측은 기존 PROVIDER/null 출처를 유지해야 한다. 원장 참조를 외부 콘솔 조회 성공으로 해석하지 않는다.

`SimulationProviderReferenceBoundaryIT`는 실제 JOIN → 0원 조회 → 지급 → 조회 → 소비 → 조회를 실행한 DB에서 전체 선택·관측만 선택·현금만 선택·마지막 현금만 선택을 확인하고 DB 지문과 원본 export가 변하지 않았음을 검사한다. 별도의 변조 사례는 SQL 관계 및 replay cutoff를 유지한 채 출처 오류를 검사한다. 실패 출처의 두 검사는 메모리상의 변조 레코드를 사용하며 실제 provider 실패 재생의 증거는 아니다. 해당 액션의 정확한 실행 결과와 artifact digest는 `verification-759828.json`에 기록한다. 이 추가 검증도 import·backup·apply·restore를 실행하거나 허용하지 않는다.
