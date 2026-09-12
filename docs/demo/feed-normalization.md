# 실제 추천 재생의 비교 정규화

`act-5a81145c679303e43ecead94c3075cf2`는 실제 Python 추천을 사용한 재생의 마지막 UUID 정규화 단계를 연결한다. 공개 OpenAPI와 dataset schema는 변경하지 않는다. 추천 요청 ID는 실행 진단 매핑의 `FEED_RECOMMENDATION:<첫 요청 eventId>`이며 승인된 id-map entity enum을 확장하지 않는다.

원본 request/response, HTTP 상태, backend 페이지와 DB export는 그대로 둔다. HTTP 200 추천에 대해 원본 요청 **바이트**의 SHA-256, 요청·문맥 ID, DB viewer/academy/request/model/순위 및 backend 페이지를 대조한다. 후보 중복, 순위 중복·누락 수, 후보 밖 ID, 알 수 없는 학생·카드 ID는 거부한다. 확인된 요청 ID만 관계형 UUID 정규화에 전달한다. 저장된 추천에 대응하는 원본 교환이 없으면 실패한다.

별도 `normalized-feed.json`은 UUID가 지정된 필드만 논리 ID로 바꾼다. 자유 텍스트, 시간, null, 금액, 후보/순위 배열 순서는 유지한다. 소수인 추천 유사도 점수도 보존하기 위해 기존 RFC 8785 encoder를 simulation 전용 adapter로 사용한다. `logical:` digest는 비교용이며 실제 Python input_digest를 대체한 운영 증거가 아니다. 그 digest의 원본 바이트 검증은 정규화 전에 수행한다.

Continuation은 같은 추천 ID를 유지하고 교환을 추가하지 않는다. 401, 비추천 fallback, HTTP 호출 없는 빈 페이지는 원본 증거만 보존하며 성공한 추천 교환으로 세지 않는다. 정규화 성공도 여전히 `REPLAYED_PARTIAL_VALIDATION`, `readyForApplication: false`이다.

검증은 실제 로컬 Python + 두 독립 PostgreSQL의 9개 명령(학생 2명, 카드 2개, 첫 페이지와 continuation 재조회)에 대해 원본 요청 bytes는 다르고 네 비교 산출물(feed/backend/relational/responses) bytes는 동일한지 확인한다. 변조 테스트는 요청 공백 변경, 응답 ID 불일치, 저장 순위 변화, 원본 응답 누락, 미등록 작성자 ID를 거부한다. 정확한 실행 결과는 `verification-5a8114.json`에 기록한다.

이는 후보 완전성·전월 지표·Python 순위 규칙의 독립 검증을 대신하지 않는다. 100명 전체 이력의 두 환경 재현성, 또래/이체의 동일 시각 UUID 순서 문제, 최종 canonical CSV, selective import/apply/restore, 대표 4명 UI, 최신 Owner 콘솔 보존 및 현재 demo 적용은 후속 작업이다.
