# 원본 위시에서 추천 카테고리·유사도 검증

액션 `act-df2b050a976c2f3c5c3c8e9f5cd69f24`는 simulation의 실제 추천 요청을 만드는 데 쓰인 카테고리·기본 유사도·제목 유사도를 독립 대조한다. `SimulationFeedInputVerifier`의 원본 캡처에 `representative_wish_selection`을 추가했다. 원본 요청·응답·페이지·DB 캡처를 먼저 보존하고 검증 결과를 별도로 기록한다. 검증 실패는 해당 재생을 실패시키며 정상 검증 파일을 만들지 않는다.

`SimulationFeedSimilarityVerifier`는 제품의 `FeedCategoryClassifier`, `FeedTitleSimilarity` 또는 대표 선택 SQL을 호출하지 않는다. 저장소의 `api/recommendation/feed-classifier-v1.json`을 별도로 읽고 고정 SHA-256 `de23b80260907e3d818892c0ea6ba2d9d28251e49d75a812fb925e1a47733f61`과 일치하는지 검사한다. 소문자·Python 공백 처리, 단어 경계를 포함한 2~3자 n-gram, TF-IDF와 cosine 점수, 순서에 따른 동점 선택 및 0.05 임계값으로 category를 계산한다. 이 검사는 고정 분류 모델의 구현 일치를 검증하며 실제 학생 취향이나 분류 정확도를 입증하지 않는다.

대표는 원본 선택 행의 계좌 소유권과 유일성을 확인한 후, 삭제되지 않은 IN_PROGRESS 또는 AMOUNT_REACHED 선택을 사용한다. 선택이 유효하지 않으면 가장 오래된 IN_PROGRESS 위시를 생성 시각·UUID 오름차순으로 고른다. 자동 선택에는 AMOUNT_REACHED를 포함하지 않는다. 대표가 없으면 두 유사도는 0이다. 카테고리·목표 금액 구간·서울 날짜 기준 목표 기간 구간의 일치 개수를 3으로 나눈 기본 유사도를 검증한다. 목표 날짜가 없으면 별도 구간을 쓴다.

제목은 Unicode code point 단위의 별도 전수 일치 탐색을 사용한다. 가장 긴 구간, 좌측/우측 순서의 동점 처리, 재귀적인 앞뒤 구간 및 Python SequenceMatcher의 200자 autojunk를 재현한다. 검증은 기존 Python oracle과 추가 표준 라이브러리 `difflib.SequenceMatcher`의 128개 고정 벡터를 사용한다. 추가 벡터의 임의 입력은 Python `random.Random(71)`로 생성했으며, 한글·이모지·반복 문자열·비대칭 동점·autojunk 경계를 포함한다. 이는 실행 환경의 Python 호출을 대체하는 모형이 아니라 독립 함수 결과를 고정한 검증 자료다.

단위 검사에는 명시적인 목표달성 대표, 완료·삭제 시 fallback, 같은 시각의 ID 선택, 다른 계좌 대표 거부, 대표 없는 요청, 금액·기간 경계 및 feature/classifier 버전 변조가 포함된다. 실제 일회용 PostgreSQL에서 두 학생의 위시와 6월 금융·방문 이력을 만들고 로컬 Python 추천을 호출하는 통합 검사에는 뷰어의 대표 위시를 추가했다. 도서·기본 유사도 1·제목 유사도 1인 실제 요청을 확인하고 세 필드를 각각 변조하면 거부하는지 검사한다. 원본 행·HTTP 요청·응답·검증 결과는 `build/simulation-feed-monthly-metrics/`에 남긴다.

`categoryAndSimilaritiesVerified: true`는 실제 요청 입력과 원본 위시의 일치를 뜻한다. Python 순위 규칙 자체는 아직 독립 검증하지 않아 `rankingAlgorithmVerified`는 false다. Session에서 HTTP가 발생하지 않는 빈 페이지·continuation은 기존 입력 검증 대상에 포함되지 않는다. 동일 시각 선택의 실행 간 재현성, 100명 전체 재생, canonical dataset/CSV, selective import/apply/restore, 대표 UI, Owner 콘솔 보존과 현재 demo 적용도 남아 있다. 이번 액션은 승인 OpenAPI·bundle schema·일반 backend 코드를 수정하지 않았다. 전체 구현 완료나 gate 승인으로 보고하지 않는다.

정확한 검사 수·결과·파일 해시는 `verification-df2b05.json`을 따른다.
