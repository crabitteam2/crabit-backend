# 추천 구성 조건과 HTTP 없는 페이지의 저장 결과 검증

`SimulationFeedCompositionVerifier`는 실제 Java 클라이언트가 수락한 Python 응답에 대해 승인된 `feed-rules-v1`의 구성 조건을 별도로 검사한다. 최초 후보 집합에 대한 min(20, 후보 수), 중복 없는 후보 소속, request/context 신원과 모델 버전을 확인한다. 완료 후 0~48시간을 양 끝 포함으로 계산하며, 해당 완료 후보가 있으면 결과에 하나 이상 있어야 한다. COMPLETE 전월 지표에서 불도저 또는 꾸준형으로 분류되는 완료 후보는 min(2, 가능한 수)가 상위 min(10, 결과 수)에 있어야 한다. 관측 불완전 지표는 롤모델로 발명하지 않는다. 한 카드가 두 조건을 동시에 만족할 수 있다.

이 검사는 Python 순위 함수를 호출하지 않는다. 점수 가중치, MMR의 전체 정확한 순서나 category spacing을 검증했다고 주장하지 않으며 `rankingAlgorithmVerified: false`를 유지한다. 원래 생산 클라이언트의 정확한 요청 바이트 digest·응답 형식·deadline 검증은 그대로 사용한다. 401/timeout/latest fallback에는 성공 구성 검증 파일을 만들지 않는다. 실제 수락된 추천이 구성 조건을 어기면 원본 증거를 보존한 채 시뮬레이션이 실패한다.

`SimulationFeedPageVerifier`는 첫 페이지, 빈 페이지, continuation, 동일 커서 재조회 모두에서 실제 `behavior_result_context`와 순서가 있는 `behavior_result_item`을 read-back한다. 응답의 context ID·생성 시각·카드 수·위치를 원본 DB 행과 비교한다. 원본 `page-source.json`을 먼저 저장하고 성공했을 때만 `page-verification.json`을 기록한다. 이는 페이지의 저장 결과 대조이며, 원래 추천 입력을 다시 조립하거나 현재 권한의 전체 독립 검증을 대신하지 않는다. HTTP가 없으면 가짜 request/response 파일을 만들지 않는다.

runner는 `page-source`, `page-verification`, `composition-verification`을 다른 실제 feed 증거와 함께 raw index에 해시·길이·사건 ID로 연결한다. 파일명 충돌은 DB 및 출력 생성 전에 거부한다. 새로운 증거를 만들기 위해 기존 요청/응답 바이트, domain row 또는 Python 결과를 바꾸지 않는다.

단위 검사는 48시간 경계와 미래 완료, 상위 20개 누락 및 상위 10개 이탈, 분류 임계값, 불완전 관측, 중복/외부 후보, 신원·개수 변조, 작은/빈 후보 집합을 포함한다. 실제 PostgreSQL와 로컬 Python 통합 검사는 수락된 추천·401 fallback·빈 페이지·continuation·동일 커서 재조회와 변조된 저장 항목 거부를 검사한다. 두 DB runner는 새 증거 파일의 실제 바이트와 raw index 해시까지 대조한다.

전체 100명 행동 재생·동시 시각 후보 재현성·정확한 점수/MMR 순위·canonical 데이터/CSV 조립·selective apply/restore·대표 UI·Owner 콘솔 보존 및 현재 demo 적용은 별도 미완료 작업이다. 액션 `act-9932dc83fad9c301b6cd6fdbf2822597`의 정확한 검증 결과는 `verification-9932dc.json`에 기록한다. Feature Run, action state와 승인 계약은 변경하지 않는다.
