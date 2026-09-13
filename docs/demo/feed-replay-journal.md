# CLI 추천 재생과 원본 인덱스

액션 `act-7d9a9add3ee9ae2b11e7fbc7f1030b25`는 `SimulationReplayRun`에 기존 실제 Python 추천 세션을 연결했다. Java 진입점의 `FeedOptions` 및 CLI의 `CRABIT_SIMULATION_FEED_URL` / `CRABIT_SIMULATION_FEED_TOKEN`으로 활성화한다. URL은 `http://127.0.0.1:<port>/internal/v1/feed-rankings`만 허용하며 토큰은 비어 있거나 CR/LF를 포함할 수 없다. 기존 recap 옵션과 함께 사용할 수 있다. 설정이 없으면 기존 추천 미설정 동작을 유지한다. CLI 인자 네 개의 의미와 승인된 OpenAPI·dataset schema는 변경하지 않았다.

각 `FEED_QUERY`는 해당 명령 sequence의 `feed-execution/event-N/`에서 실행한다. 세션·Spring 런타임·임시 PostgreSQL은 재생의 try-with-resources로 닫는다. 신규 출력 디렉터리만 사용하며 입력 bundle이나 기존 출력 디렉터리를 덮어쓰지 않는다. main 서버·배포 설정·외부 콘솔을 조작하지 않는다.

기존 `command.requestRef`는 원본 논리 명령, `command.responseRef`와 `outcome.resultRef`는 실제 백엔드 페이지를 보존한다. 실제 Python 요청을 이 파일로 가장하지 않는다. 추가 원본은 아래 경로에 byte-for-byte 복사하고 SHA-256·바이트 수·사건 ID·종류를 `raw/index.json`에 기록한다.

- `raw/feed/event-N-request.json`: 실제 전송 요청 바이트, service=FEED.
- `raw/feed/event-N-response.json`: 실제 수신 응답 바이트, service=FEED. 오류 또는 JSON이 아닌 바이트도 손대지 않으며 contentType은 application/octet-stream으로 기록한다. 실제 HTTP Content-Type은 http 관측에 있다.
- `raw/feed/event-N-http.json`: 실제 응답 상태·Content-Type·바이트 수.
- `raw/feed/event-N-page.json`: 세션이 기록한 실제 페이지 및 HTTP 시도·응답 수집 여부. service=BACKEND.

추천 모델을 검증했다고 주장하지 않으므로 이 인덱스의 modelVersion은 null이다. Python 응답의 버전 필드는 원본 안에 남는다. Authorization 토큰은 기록하지 않는다. 빈 후보·후속 cursor·반복 후속 페이지에는 Python 요청/응답을 합성하지 않는다. 위 네 경로는 실행 전에 예약되므로 다른 명령의 원본 경로와 충돌하면 DB/출력 생성 전에 거부한다. 최종 인덱스는 기존 rawIndex schema로 검증한다.

일반 성공뿐 아니라 기대 순위 불일치로 dispatcher가 중단되더라도 finally에서 실제 HTTP·페이지 증거를 수집한다. 재생 관측의 feedHttpAttempts는 캡처된 전송 요청 수, feedHttpResponses는 캡처된 실제 응답 수, feedPagesCaptured는 페이지 관측 파일 수다. pythonInvoked는 전송 시도이며 추천 성공 의미가 아니다. 페이지 관측에는 실패 시 null 페이지가 있을 수 있다. 실행 후 정규화가 실패해도 원본 인덱스와 FAILED 관측을 남긴다. 401에서 LATEST 페이지가 저장되는 기존 동작을 추천 성공으로 표시하지 않는다.

## 검증과 남은 경계

`SimulationFeedReplayIT`의 실제 Python·PostgreSQL 시나리오는 두 공유 카드의 추천 순서가 실제 페이지와 일치하는지, 세 페이지에 HTTP가 한 번만 호출되는지, 논리 명령과 Python 원본이 구분되는지, 모든 원본 인덱스 digest/길이가 실제 파일과 일치하는지 확인한다. 401 fallback, 빈 후보, 잘못된 기대 순위에서 원본 보존, 예약 경로 충돌 및 설정 거부도 확인한다. 추천 성공의 마지막 단계는 아직 `RELATIONAL_NORMALIZATION_UNKNOWN_UUID`로 중단된다. 기존 response normalizer에도 `NORMALIZATION_PYTHON_NOT_IMPLEMENTED`가 남아 있다. 이 테스트는 그 경계와 원본 보존을 검증하며 전체 재생 성공을 주장하지 않는다. 첫 집중 실행은 이 경계의 예상 오류명을 잘못 지정해 1개 실패했고, 실제 먼저 발생하는 관계 정규화 오류를 확인한 뒤 기대값을 수정했다.

추천 요청·context ID와 input digest의 검증된 typed normalization, 독립 후보·기간·순위 검증, 100명 전체 이력 두 DB 재현성, 최종 canonical bundle/CSV, selective apply/restore·대표 UI·최신 Owner 콘솔 보존·현재 demo 적용은 미완료다. `feedExchangeNormalizationPerformed` 및 `readyForApplication`은 false이며 승인·게이트 전이·commit·원격 쓰기를 수행하지 않았다. 최종 테스트 집계와 파일 증거는 `verification-7d9a9a.json` 및 `action-files-7d9a9a.json`에 기록한다.
