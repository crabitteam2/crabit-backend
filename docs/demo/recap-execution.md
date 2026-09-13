# 로컬 리캡 Python 실행과 저장

`src/simulation/java/com/crabit/backend/recap/SimulationRecapExecution.java`는 simulation 소스셋 전용이다. `SimulationDomainRuntime.executeAt` 안에서 동결된 `Prepared`를 받아 기존 **RecapPythonClient**의 HTTP 전송·응답 크기 제한·시간 제한·결과 검증을 그대로 사용한다. 성공 응답은 기존 **RecapGenerationCoordinator.succeed**로 저장하고 repository에서 입력, 결과, 현재 버전과 논리 생성 시각을 다시 읽어 대조한다. 일반 서버 소스와 공개 API 계약은 변경하지 않았다.

서비스 주소는 명시적 `http://127.0.0.1:<port>/internal/v1/recap-generations`만 허용한다. 리디렉션·사용자정보·query·fragment를 허용하지 않는다. 토큰은 메모리로 전달하며 로그·원본 자료에는 쓰지 않는다. main 클라이언트가 실제 보낸 동결 request_json을 `request.json`에, 클라이언트의 bounded body subscriber가 받은 바이트를 **검증 전에** `response.json`에 저장한다. `http.json`에는 상태 코드, Content-Type, 요청·응답 크기만 기록한다. 성공 저장 확인은 `persisted.json`, 전송/검증 실패 분류는 `failure.json`에 남는다. 너무 큰 응답 또는 전송 중단처럼 완전한 bounded body가 없으면 완전한 response.json이 없는 것이 정상이며 성공으로 취급하지 않는다.

동결 입력/digest/version과 BUILDING 데이터셋 소유권이 일치해야 한다. 완료된 SUCCEEDED/NOT_ELIGIBLE을 다시 호출하면 저장 결과만 반환하고 Python을 다시 호출하지 않는다. PENDING 한 건만 실행하며 다른 claim 가능한 generation이 있으면 실행 전에 거부한다. 이는 현재 단일 스레드 시뮬레이션의 준비→즉시 실행 순서를 위한 제한이다. 실패는 기존 coordinator의 실패/재시도 가능성 정책을 저장하지만 이 helper는 자동 재시도하지 않는다. 실패나 증거 기록 오류가 난 replay를 성공으로 진행해서는 안 된다. 호출자는 미래 사건을 넣기 전에 같은 논리 시각에서 준비와 실행을 완료해야 한다.

`SimulationRecapExecutionIT`는 형제 crabit-data worktree의 실제 `python3 -u -m recap_service`를 OS 할당 loopback 포트에서 실행한다. 기본 소스 경로는 `../crabit-data`이며 `CRABIT_SIMULATION_DATA_ROOT`, `CRABIT_SIMULATION_PYTHON`으로 명시적으로 지정할 수 있다. 표준 라이브러리 기반 서비스이고 유료 API·외부 모델 호출은 없다. `PYTHONDONTWRITEBYTECODE=1`로 데이터 저장소에 pycache를 쓰지 않는다. 준비 메시지 timeout 10초, 종료 대기 5초 후 필요시 해당 자식 프로세스만 강제 종료한다. stderr와 테스트 DB는 임시 테스트 환경에만 남으며 테스트가 소유한 프로세스·컨테이너만 정리한다. 실제 서비스 경로가 없거나 실행이 실패하면 테스트도 실패하며 건너뛰지 않는다.

검사는 실제 DB에 계좌·위시를 생성하고 도메인 서비스를 통해 3회 입금한 뒤 주간·월간 Python 계산에서 각각 3,000원을 확인한다. HTTP 원본 view/metrics, 저장 결과, 실제 owner query 결과를 비교하고 타인 조회 거부와 중복 완료의 DB 행 불변성을 확인한다. 별도 테스트는 실제 Python의 401 응답 보존과 FAILED/view 없음, 월간 입금 부족 시 NOT_ELIGIBLE/HTTP 호출 없음, 데이터셋·입력 치환 거부, 외부 URL 거부를 확인한다. 주간 fixture에는 공유 story/사진이 없으며 이 검사는 사진 서명·story 권한 보강을 입증하지 않는다.

최신 실제 성공 자료는 `build/simulation-recap-execution/WEEKLY/`와 `MONTHLY/`에 저장된다. 각 폴더는 request.json, response.json, http.json, persisted.json, owner-response.json을 포함한다. 테스트가 재실행되면 이 로컬 검증 출력은 갱신된다. 공개 API 원본 요청 증거가 아니라 실제 서비스 메서드 조회 증거임을 구분해야 한다.

남은 작업: dispatcher의 CLOSE_WEEK/CLOSE_MONTH와 journal/artifact identity 연결, 리캡 논리 ID 정규화·독립 원장/기간 누출 검증, 실제 Python 추천, 100명 전체 이력 두 DB 재생, ADJUST/CORRECT binding, selective apply/restore, 대표 4명 실제 UI 및 Owner 콘솔 보존·현재 demo 적용. 이번 helper와 검사는 전체 Feature Run 완료 또는 적용 승인을 뜻하지 않는다.
