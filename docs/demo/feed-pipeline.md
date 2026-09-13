# 실제 추천 페이지와 행동 재생 연결

액션 `act-765495eb5bb14d61dc57110696a096e9`은 이전 feed-execution helper의 다음 단계로, 기존 `FeedRankingClient`를 `SimulationDomainRuntime`의 `SharedCardQueryService`에 연결한다. 명시적으로 전달한 `SimulationFeedSession`이 있는 로컬 런타임에서만 ranking 빈을 등록한다. main 서버 코드·설정·승인 OpenAPI·dataset schema는 이 액션에서 수정하지 않았다.

`SimulationCommandDispatcher`의 새 생성자에 session을 전달하면 실제 `FEED_QUERY`가 기존 `BehaviorService.createResult`를 호출한다. 후보 수집, Python HTTP 검증, 피드 context/state/transition 저장, 결과 position 저장 및 현재 권한 확인은 기존 서비스가 실행한다. 기대한 orderedCardIds를 DB에 넣지 않는다. 실제 페이지 순서가 기대와 다르면 실제 결과를 보존하고 dispatcher를 중단한다. 같은 사건의 정확한 재실행은 저장한 명령 결과를 반환하며 HTTP나 페이지를 추가하지 않는다.

session은 명령 sequence별 `event-N/` 디렉터리를 새로 생성한다. 외부 주소는 고정 경로를 가진 명시적 loopback HTTP 주소만 받으며 리다이렉트·다른 주소·중복 HTTP 호출을 거부한다. production request body의 실제 바이트를 `request.json`, 실제 응답을 `response.json`, 상태와 길이를 `http.json`에 기록한다. Authorization 값은 기록하지 않는다. `page.json`에는 실제 페이지와 `pythonInvoked`, `responseCaptured`를 기록한다. 401 등 실제 오류도 보존하지만 페이지는 기존 `LATEST` 동작을 유지한다. 빈 후보, continuation, 예산 소진 등 호출이 없는 경우 원본 HTTP 파일을 만들지 않는다. 시간 초과로 돌아온 뒤의 응답은 종료된 명령 증거를 변경하지 않는다. `pythonInvoked=true`는 HTTP 시도 의미이며 성공이나 원본 응답 보존을 뜻하지 않는다.

디렉터리 충돌은 페이지 실행 전에 거부된다. 기록 실패는 가짜 추천 성공을 반환하지 않고 재생을 중단한다. 페이지 저장 뒤 마지막 증거 쓰기가 실패할 수 있으므로 증거 실패를 DB 무변경으로 주장하지 않는다. session과 runtime은 별도 AutoCloseable이며 호출자가 둘 다 닫는다. 일반 runtime 생성자는 종전처럼 추천 설정 없이 동작한다. bootJar에 simulation 클래스는 포함되지 않는다.

검증은 `SimulationFeedPipelineIT`에서 실제 로컬 Python feed_service와 새 PostgreSQL 프로세스를 사용한다. 두 후보의 실제 Python 순위, DB ranked_card_ids·request ID, 1개씩 페이지 진행과 continuation 반복의 HTTP 생략, 실제 클릭의 unmatched 의미, 공개 범위 변경 뒤 수집 거부, 원본 추천 보존, 401에서 LATEST 저장, 빈 후보 및 경로 충돌을 확인한다. 별도 dispatcher 시나리오는 JOIN/CREATE/SHARE/FEED_QUERY/CLICK과 동일 FEED_QUERY의 재실행을 검증한다.

이 연결은 Java dispatcher 진입점까지다. `SimulationReplayRun`의 CLI feed options 및 원본 index 수집, 추천 request/context ID·digest의 typed normalization, 독립 후보/기간/순위 oracle와 전체 100명 재현성은 아직 미완료다. 현재 response normalizer는 recommendationResultId가 있으면 `NORMALIZATION_PYTHON_NOT_IMPLEMENTED`로 멈춘다. 이 거부를 제거하거나 임의 정렬로 재현성을 가장하지 않았다. 최종 canonical bundle/CSV, selective apply/restore, 대표 UI, 최신 Owner 콘솔 보존 및 현재 demo 적용도 후속 범위다. 이번 액션은 Feature Run/action/receipt, commit, 원격 provider 상태를 변경하지 않는다.


후속 액션 `act-7d9a9add3ee9ae2b11e7fbc7f1030b25`에서 CLI 옵션과 원본 인덱스 수집을 연결했다. 현재 동작과 남은 정규화 경계는 [feed-replay-journal.md](feed-replay-journal.md)를 따른다.
