# Python 최종 성공 사례와 조회 권한 검증

액션 `act-81147b3528eb04010ba4e0d8e90a1aba`는 simulation 전용 `SimulationRecapResultVerifier`를 추가했다. 주간 리캡 응답의 성공 사례가 동결된 입력 후보와 개수·wish ID·순서까지 정확히 같아야 한다. 누락, 추가, 중복, 다른 ID, 순서 변경을 거부한다. 기존 production 응답 형식 검사는 그대로 사용한다.

`recap-1`의 작성자 유형도 입력의 독립 검증된 `core-metrics-v1`에서 재계산한다. 입금 8회 이상이며 표준편차가 4 미만이면 불도저형, 그 다음 평균 입금 2,000원 미만·5회 이상이면 꾸준형, 그 다음 입금 5회 미만·후반기 편향 0.3 초과이면 단기 집중형, 나머지는 탐색형이다. 우선순위와 엄격한 경계를 검사한다. 예전 지표 분기는 입력 type_title을 그대로 유지해야 한다. 미지의 알고리즘/지표 버전은 거부한다. 월간 리캡에서는 성공 사례 검증을 수행했다고 주장하지 않는다.

실제 Python HTTP 응답을 먼저 원본으로 기록한 다음 이 검사를 수행한다. 통과하면 `result-verification.json`을 만들고 실제 coordinator가 성공 상태를 저장한다. 실패하면 원본 HTTP 200 바이트와 실패 코드를 남기고 FAILED로 저장하며 view는 저장하지 않는다. 정규화 시에도 저장된 성공 결과를 다시 대조한다. bundle journal은 결과 검증 파일을 `raw/recap-result/event-<sequence>.json`으로 색인한다. 결과·기간 검증 산출물의 예약 경로와 입력 경로 충돌은 DB 또는 출력 디렉터리 생성 전에 거부한다. 원본 request/response를 수정하지 않는다.

실제 PostgreSQL/Python 통합 테스트에서 두 작성자가 도메인 서비스를 통해 위시 생성·입금·완료·공유를 수행한다. 실제 Python이 같은 순서의 두 사례와 유형을 반환하는지 검사한다. 실제 RecapQueryService와 DB 저장소를 이용해 작성자의 역방향 차단으로 첫 사례가 숨겨지는지, 두 번째 사례를 FOLLOWERS로 바꾸면 숨겨지고 조회자가 팔로우하면 다시 보이는지 확인한다. 생성 이후 추가한 공개 완료 위시는 기존 목록에 채워 넣지 않는다. 잘못된 계좌 소유자는 거부하며 생성 행과 원본 응답은 바뀌지 않는다. 사진 서비스는 호출 순서를 관찰하는 mock으로만 사용하므로 사진 저장·서명 또는 외부 provider 검증 증거가 아니다. 차단된 사례에는 사진 조회조차 하지 않아야 한다.

별도 로컬 실패 proxy는 실제 Python이 반환한 정상 응답의 story UUID만 바꾼다. production 형식 검사를 통과하는 HTTP 200이라도 독립 결과 검사가 거절하고 FAILED/null view를 저장하는지 확인한다. 이 응답은 명시적인 실패 주입 fixture이며 정상 Python 계산 결과로 주장하지 않는다.

실행 명령, 정확한 테스트 수·생략·실패 및 파일 digest는 `verification-81147.json`과 `action-files-81147.json`에 기록한다. 실제 원본/조회 증거는 `build/simulation-recap-result-query/`, 실패 주입 증거는 `build/simulation-recap-result-failure/`에 남긴다.

## 남은 범위

전체 백엔드 구현은 아직 미완료다. 익명 또래 배열 및 같은 시각 UUID 정렬의 두 DB 재현성, 실제 추천 Python, 100명 전체 혼합 이력 재생, 최종 canonical bundle/CSV와 selective apply/restore, 대표 UI·최신 Owner 보존·현재 demo 적용이 남아 있다. ADJUST/CORRECT는 승인된 사건 union의 실행 명령이 아니다. HTTP API 및 실제 사진 서명에 대한 별도 통합 검증은 이번 검사에서 추가하지 않았다. main 코드·승인 계약 bytes·Feature Run/action·commit·원격 상태를 변경하지 않았다.
