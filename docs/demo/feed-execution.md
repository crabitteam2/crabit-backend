> 후속 액션 765495에서 명시적 feed session을 가진 dispatcher의 실제 페이지·행동 연결을 구현했다. 현재 경계는 [feed-pipeline.md](feed-pipeline.md)를 따른다. 아래는 helper를 추가한 액션 당시의 기록이다.

# 실제 Python 피드 추천 실행

액션 `act-49cd74bf04f90ee9ae04b43b641fc24c`에서 simulation 전용 `SimulationFeedExecution`을 추가했다. 동기식 역사 시계 callback 안에서 활성 BUILDING 데이터셋의 조회자·열린 계좌·학원 membership을 확인한다. 실제 `FeedRankingRequestAssembler`, 월 지표 repository/service, 방문 신호와 bundled classifier를 사용해 현재 PostgreSQL 데이터에서 입력을 만들고 기존 `FeedRankingClient`로 로컬 Python을 호출한다. 입력 준비부터 기존 500ms deadline을 공유한다.

호출 대상은 명시적인 `http://127.0.0.1:<port>/internal/v1/feed-rankings`만 허용한다. user-info, query, fragment, 다른 host·scheme·path는 거절한다. 새 출력 디렉터리만 사용하며 기존 디렉터리 충돌과 잘못된 데이터셋 소유자는 HTTP 전에 실패한다. credentials는 증거에 저장하지 않는다. source set은 simulation 전용이고 main 및 승인 OpenAPI/schema bytes는 변경하지 않았다.

`request.json`은 실제 production request serializer의 입력 바이트다. 후보가 없으면 HTTP를 호출하지 않고 LATEST를 반환하므로 이 파일을 전송 완료 증거로 해석하지 않는다. HTTP를 호출하면 기존 bounded subscriber가 수신한 원본 `response.json`과 status/content-type/byte-length의 `http.json`을 검증 전에 저장한다. `result.json`은 production 클라이언트의 RECOMMENDED 또는 LATEST 결과·HTTP 시도 여부와 후보 수를 기록한다. 입력 digest, 버전, 신원, 중복·목록 외 ID 및 응답 개수 검증은 기존 클라이언트를 그대로 사용한다. 인증·전송·deadline·프로토콜 실패에는 정상 결과를 만들거나 재시도하지 않는다. 원본 증거 파일 쓰기 실패는 fallback 성공으로 숨기지 않고 오류로 전파한다. 입력 준비 deadline 초과는 LATEST 진단을 남긴 뒤 기존 예외를 전파한다.

실제 테스트는 일회용 PostgreSQL에서 학생·계좌를 만들고 WishLifecycleService로 공개 위시를 생성한다. sibling crabit-data의 실제 `python3 -m feed_service --host 127.0.0.1 --port 0`을 실행한다. HTTP 200의 한 후보 순위 및 원본 요청의 SHA-256과 응답 input_digest 일치, 6월 COMPLETE 지표 coverage, feed_page_context가 생성되지 않음을 확인한다. 별도 실제 Python 401 응답의 바이트 보존과 LATEST, 빈 후보의 HTTP 미호출, 잘못된 소유자·디렉터리 충돌·비로컬 endpoint 거부를 검사한다. 정상/인증 실패 원본은 `build/simulation-feed-execution/`에 보존한다. 테스트 fixture 토큰은 외부 자격증명이 아니다.

## 현재 경계와 남은 작업

이 helper는 실제 추천 실행 진단이며 아직 dispatcher FEED_QUERY, SharedCardQueryService 페이지 생성, behavior context/노출·클릭과 연결하지 않았다. 따라서 기존 simulation FEED_QUERY는 여전히 LATEST 경로다. 후보 집합·지표·기간 누출 및 Python 순위 알고리즘의 독립 검증, feed 원본의 bundle journal 및 논리 ID 정규화도 후속 작업이다. 현재 검사는 1개 후보의 실제 호출로, 100명 혼합 이력이나 ranking 품질·전체 후보 completeness 증거가 아니다.

리캡/또래/이체의 여러 UUID 정렬 재현성, 전체 100명 두 DB 재생, 최종 canonical bundle/CSV, selective apply/restore, 대표 UI·Owner 콘솔 최신 보존·현재 demo 적용은 미완료다. ADJUST/CORRECT는 승인된 command union의 실행 명령이 아니다. 이전 main feed-history 시각 역행 원인 수정도 이번 범위에 없다. Feature Run, action, commit, 원격 provider 상태는 변경하지 않았다.

정확한 실행 결과와 파일 해시는 `verification-49cd74.json`을 따른다. 구조 격리 검사는 배포 JAR 검사이며 제품 E2E를 뜻하지 않는다.
