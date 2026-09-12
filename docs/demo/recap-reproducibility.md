# 또래 통계·이체·이력 참조의 재현성

액션 `act-bfc3e9ee73f4b0b98acc63af94bca45f`는 기존 simulation 재생기의 비교 결과에서 임의 UUID 정렬과 데이터베이스 이력 sequence에 의존하던 부분을 수정했다. 승인된 계약과 production 코드는 변경하지 않았다.

원본 요청 digest, 요청과 저장 행 일치, HTTP 응답과 저장 결과 일치, 독립 원장·또래 검증을 그대로 수행한다. 그 뒤 비교용 `input.peer_metrics`의 두 배열만 각각 숫자 순서로 정렬한다. Python은 두 배열을 독립적인 백분위 모집단으로 사용하며 배열 간 위치 대응을 사용하지 않는다. 중복 개수와 실제 값은 유지된다. `effective_transactions`는 발생 시각을 먼저 비교하고 같은 시각에서는 논리 ID를 포함한 전체 행으로 정렬한다. 금액·종류·시각·출처를 지우지 않는다. 순위와 성공 사례 배열은 그대로 보존한다.

소수형 리캡 지표가 포함되면 기존 정수 전용 직렬화가 실패했다. 동결 입력 사본과 정규화 리캡·관계형 상태·백엔드 projection, 해당 digest에 기존 RFC 8785 인코더를 적용했다. 원본 HTTP 요청·응답과 DB JSON 문자열은 그대로 기록한다. 승인 bundle 입력의 정수 검증 정책은 변경하지 않았다.

동일 이체의 두 위시 갱신은 UUID 잠금 순서에 따라 feed history의 전역 sequence를 서로 다르게 배정했다. 비교용 history version은 `HISTORY:<source kind>:<logical source ID>:<source-local ordinal>`로 표현한다. 같은 출처의 원래 sequence 순서는 유지하고, 방문 증거의 wish/card/account version 참조도 같은 표로 연결한다. 존재하지 않는 참조는 거부한다. 원본 history row, payload, 기간, 원본 version은 원본 export에 남으며 방문 baseline과 전역 high-water 값은 유지한다. 이는 import 파일이 아닌 비교용 projection이다.

새 통합 검사는 3명의 실제 가입·지급, 4개의 위시, 서로 다른 또래 지표, 실제 배분·이체·주간 마감의 14개 명령을 동일하게 두 번 재생한다. 각 실행은 새 PostgreSQL을 만들고 실제 로컬 Python 리캡 서비스를 호출한다. 원본 요청 바이트는 다르고 `normalized-recaps.json`, `normalized-backend.json`, `normalized-relational.json`, `normalized-responses.json`은 완전히 같아야 한다. 각 raw index의 해시와 길이도 실제 파일과 대조한다. `build/simulation-recap-mixed-repro/`에 비교한 파일과 원본 요청을 남긴다. 단위 검사는 익명 배열의 중복 제거·금액 변조가 계속 차이로 드러나며 원본이 변경되지 않는지 확인한다.

이 검사는 100명 전체 이력의 재현성을 의미하지 않는다. 동일 시각의 다른 후보·대표 선택, 전체 모집단의 과거 피드와 리캡, 최종 canonical bundle/CSV, selective import/apply/restore, 대표 UI, 최신 Owner 콘솔 보존과 현재 demo 적용은 후속 작업이다. 독립 피드 월 지표 숫자·유사도·방문 신호·순위 규칙 검증도 남아 있다. 검증 결과는 `verification-bfc3e9.json`에 기록한다.
