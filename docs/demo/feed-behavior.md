# 실제 피드·노출·클릭 재생

액션 `act-9873e1ac93d6d430b8998af55c2d3ff1`에서 simulation 전용 재생기의 `FEED_QUERY`, `IMPRESSION`, `CLICK`을 기존 실제 서비스에 연결했다. 18종 명령에 세 종류를 더해 21종을 실행한다. main 서비스, migration, 승인된 OpenAPI와 canonical bundle schema를 이 액션에서 바꾸지 않았다.

## 실제 실행과 입력 대조

`FEED_QUERY`는 `BehaviorService.createResult`를 호출한다. 실제 `SharedCardQueryService`가 조회·권한 검사·페이지 전환을 수행하고 실제 트랜잭션이 behavior result context와 position을 기록한다. `orderedCardIds`는 결과와 비교하는 기대값이며 DB에 삽입할 카드 목록으로 사용하지 않는다. 실제 순서가 다르면 실제 응답 bytes를 `mismatchedResult`로 보존하고 dispatcher를 중단한다. context의 논리 ID는 실제 결과 UUID와 연결한다. context ID 재사용과 기대 카드 중복은 실행 전에 거부한다. cursor는 실제 서비스가 검증하는 opaque 값이다. 현재 호스트에 Python ranking client/assembler가 없으므로 결과는 `LATEST`이며 추천 성공이나 실제 Python 검증으로 표시하지 않는다.

공유 카드의 논리 ID는 실제 카드가 처음 생성된 명령의 `eventId`다. 위시 변경 뒤 현재 `shared_card`를 읽어 처음 관측한 UUID에만 매핑한다. 같은 카드의 이후 변경은 기존 ID를 유지한다. PRIVATE 전환으로 카드가 삭제된 뒤 다시 공유하면 새 카드 UUID를 새 공유 명령 ID에 연결한다. 오래된 매핑은 유지하여 과거 context의 권한 재검사와 거절을 실행할 수 있다. `orderedCardIds`와 행동의 `cardId`에 이 논리 ID를 사용한다. 입력의 예상 출력 순서에서 임의로 새 카드 신원을 생성하지 않는다.

`IMPRESSION`은 `FEED_EXPOSURE`, `CLICK`은 `FEED_CLICK`으로 실제 `BehaviorService.collect`를 호출한다. impression 논리 ID는 actor별 namespace에서 동일 UUID로 연결한다. 노출 없는 유효한 클릭은 허용하며 합성 노출을 만들지 않는다. 성공한 행동만 `BEHAVIOR_EVENT:<eventId>`로 매핑한다. 실제 서비스의 거절 코드를 원본 결과에 남기며 노출 중복·context 권한·카드 visibility/block·정확한 만료 시각을 실제 DB에서 판단한다. 같은 원본 simulation event의 재전달은 dispatcher의 기존 bytes를 돌려주며, 이는 새로운 현재 권한 재검사 요청과 구분된다.

## 검증

`SimulationCommandDispatcherIT`의 새 테스트 세 개는 실제 페이지·원본 시간·unmatched click·동일 사건 재전달·중복 노출·잘못된 position·다른 actor context·정확한 24시간 만료·impression/context 충돌·차단·비공개·재공유 신원·가짜 기대 순서에 대한 중단을 검사한다. `SimulationBehaviorRuntimeIT`는 별도 실제 PostgreSQL에서 unmatched click이 노출 행 없이 저장되는지, 뒤늦은 노출과 중복 거절, 비공개 이후 거절이 behavior/impression 행을 남기지 않는지를 SQL로 확인한다. 최신 실행 수치와 XML digest는 `verification-9873e1.json`을 따른다.

## 남은 작업

Python 추천/recap HTTP 실행, FEED request/response 파일 materialization, cursor·impression을 포함한 전체 UUID 정규화와 전체 typed relational export, bundle runner, ADJUST_ALLOCATION/CORRECT 및 period 명령, 독립 whole-domain/month-budget oracle, 100명 두DB 재현성, selective apply/restore, 대표 UI 및 Owner 콘솔 보존/현재 demo 적용은 미완료다. 이 변경은 전체 구현이나 데이터셋 적용 준비 완료가 아니다. 앞선 간헐적 feed history 시각 역행 실패의 근본 원인도 수정하지 않았다.
