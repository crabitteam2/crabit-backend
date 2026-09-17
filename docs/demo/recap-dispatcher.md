# 리캡 종료 명령 실행

`SimulationCommandDispatcher`는 명시적으로 설정한 loopback Python 서비스와 증거 폴더를 사용하여 `CLOSE_WEEK`와 `CLOSE_MONTH`를 실행한다. `configureRecap`은 첫 명령 전에 한 번만 호출할 수 있으며 credential은 출력하지 않는다. 일반 서버에는 이 simulation 소스셋이 포함되지 않는다.

종료 명령은 KST `endExclusive` 자정에만 실행할 수 있다. 동일 시각에는 주간 마감 → 월간 마감 → 일반 활동 순서를 강제하여 이미 실행된 경계 시각 활동 뒤에 마감을 끼워 넣지 못하게 한다. 기간의 주간/월간 형식, 시뮬레이션 범위, 가입한 실행자와 계좌 소유권을 검사한다. 실제 `RecapSnapshotService` 입력을 동결하고 `RecapGenerationCoordinator`와 실제 `RecapPythonClient`를 통해 완료한다. 실행 결과의 UUID는 `RECAP_GENERATION:<generationId>`에 연결하여 실제 저장 행의 typed id-map에 포함한다. 같은 eventId/동일 명령은 저장된 명령 결과를 반환하고 도메인이나 HTTP를 다시 실행하지 않는다. 별도 명령에서 생성 ID 또는 이미 마감한 계좌/기간을 재사용하면 실행 전에 거부한다.

명시적 증거 폴더의 `event-<sequence>/`에는 실제 request.json, response.json, http.json, persisted.json 및 PostgreSQL `to_jsonb`로 읽은 stored-state.json을 저장한다. NOT_ELIGIBLE 월간 상태에는 동결 request.json과 stored-state.json만 있으며 HTTP 응답을 합성하지 않는다. Python 전송/검증 실패는 실행기를 poisoned 상태로 만들어 이후 사건을 처리하지 못하게 한다. 기존 helper가 보존한 원본 실패 응답은 그대로 남는다.

이 폴더 구조는 dispatcher 로컬 실행 증거다. runner의 참조별 raw index 연결은 `recap-replay-export.md`, 입력 digest와 내부 신원 정규화는 `recap-normalization.md`를 따른다. 명시적인 로컬 리캡 설정이 없으면 runner는 DB 시작 전에 `RECAP_CONFIGURATION_REQUIRED`로 거부한다. 전체 100명·모든 기간·추천 모델까지 포함한 재현성과 최종 canonical bundle 검증은 아직 완료되지 않았다.

검증은 실제 로컬 Python 서비스 및 임시 PostgreSQL을 사용한다. 주간 종료 직전 1마이크로초 입금 포함, 종료 직후 입금에 대한 기존 요청 불변, 3,000원 주간/3,500원 월간 결과, 중복 실행의 전체 저장 상태 불변, 생성 ID의 저장 행 연결, 타인 계좌 거부, 월간 NOT_ELIGIBLE/HTTP 없음 등을 다룬다. 100명 전체 이력, 전체 기간 누출, 추천 Python, ADJUST/CORRECT binding, 선택 적용/복원, 대표 UI 및 Owner 보존/현재 demo 적용은 별도 후속 작업이다.
