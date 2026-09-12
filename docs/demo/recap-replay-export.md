# 리캡 재생 산출물

`SimulationReplayRun`은 명시적인 `RecapOptions` 또는 CLI 환경변수 `CRABIT_SIMULATION_RECAP_URL`, `CRABIT_SIMULATION_RECAP_TOKEN`이 있을 때 CLOSE_WEEK/CLOSE_MONTH를 재생한다. URL은 기존 실행기와 동일하게 소유한 로컬 Python 서비스의 `http://127.0.0.1:<port>/internal/v1/recap-generations`만 허용한다. 토큰은 산출물에 넣지 않는다. 설정 누락 및 참조 경로 충돌은 출력 디렉터리와 DB 생성 전에 실패한다.

원본 논리 명령은 `raw/requests/event-<sequence>.json`에 보존한다. command.requestRef에는 실제 동결된 Python 입력 바이트를 기록한다. snapshotRef는 해당 입력의 `input` 객체를 별도 JSON으로 직렬화한 스냅샷이며, HTTP 바이트 자체라는 주장을 하지 않는다. responseRef는 실제 수신한 HTTP body 바이트이며 storedStateRef는 실행 이후 PostgreSQL generation 행이다. 각 파일의 원본 길이와 SHA-256, 서비스와 실제 algorithm_version을 raw index에 기록한다. HTTP 상태·응답 길이는 별도 runtime observation으로 보존한다.

NOT_ELIGIBLE은 requestRef를 전송하지 않은 동결 문서로 기록하며 responseRef 파일을 만들지 않는다. `absentRecapResponses`에 해당 경로를 명시하고 정규화된 exchange의 response는 null이다. 따라서 이 산출물을 모든 artifactRefs가 존재해야 하는 최종 canonical bundle로 주장하지 않는다. 이 제한을 해결하려고 실제로 없던 HTTP 응답을 만들면 안 된다.

HTTP 실패 시 실제 응답과 실패한 저장 행을 보존하고 후속 명령을 중단한다. HTTP status가 수신되었을 때만 pythonInvoked를 기록하며 네트워크 실패만으로 호출 성공을 추론하지 않는다. 이 플래그는 응답 수신 여부이고 성공 판정은 아니다. raw/index.json과 replay-observation.json은 실패 시에도 남는다. 정상 실행에서는 원본 request/response/저장 행을 대조한 `normalized-recaps.json`과 digest를 출력한다. 전체 요청·상태에 대한 기존 검증도 모두 통과해야 부분 재생 성공으로 보고한다.

새 회귀는 실제 Python과 두 독립 PostgreSQL을 사용하여 주간 성공 및 월간 적격성 미달, 원본 request 차이와 정규화 digest 동일성, 401 실패 후 중단, 원본 명령 보존, raw index checksum 및 경로 충돌을 검증한다. 정확한 실행 결과는 `verification-2a6029.json`을 참조한다.

전체 기간의 독립 원장·누출 검증, ADJUST/CORRECT, 실제 추천 Python, 100명 전체 행동 이력, canonical bundle/CSV, selective apply/restore 및 대표 UI·Owner 콘솔 보존·현재 demo 적용은 이 변경의 완료 증거가 아니다. 제품 HTTP/OpenAPI·main source set·운영 데이터는 이 액션에서 변경하지 않는다.

기간 종료 시점의 원본 원장·효과·위시·방문 대조와 raw evidence 연결은 `recap-period-verification.md`를 따른다. 이 대조만으로 peer 및 story 후보 전체 검증을 완료한 것으로 보지 않는다.
