# 추천 입력 검증

시뮬레이션 실행에서 실제 추천 요청을 원본 DB 행과 대조한다. 운영 집계 함수를 검증기로 다시 호출하지 않는다. `SimulationFeedSession`의 실제 HTTP 요청과 `SimulationFeedExecution` 진단 실행에 연결되어 있다. 진단 실행은 후보가 없는 요청도 검증하고 HTTP 요청·응답을 만들지 않는다. Session의 HTTP 없는 빈 페이지와 continuation은 아직 이 입력 검증의 대상이 아니다.

## 후보와 관측 기간

원본 shared card, wish, account, academy membership, follow, block에서 현재 접근 가능한 후보를 계산한다. 본인·탈퇴·닫힌 계좌·삭제·포기·차단 관계를 제외하고 FOLLOWERS 접근을 확인한다. content updated time 및 UUID의 내림차순 상위 100개와 요청의 개수·ID·순서를 대조한다. 카드 생성/변경/완료 시각, 상태, 목표 날짜도 비교한다.

뷰어는 추천 시각의 서울 기준 전월을 사용하며, 완료 카드 작성자는 완료 시각의 전월을 사용한다. 계좌 개설 시각, 행동 수집 시작과 90일 보존기간의 5분 clock-skew 여유, 월 경계로 COMPLETE/PARTIAL/UNOBSERVED를 독립 계산한다. PARTIAL/UNOBSERVED에 지표 객체를 채울 수 없고 COMPLETE에는 객체가 필요하다.

## 월별 지표

`SimulationFeedMetricVerifier`는 원본 ledger event/effect, wish, behavior event로 계좌 전체 월별 지표를 계산한다. 정정은 추천 시각까지의 체인을 합산하고 원본 사건의 월에 귀속한다. 저축 합계·입금 수·평균·입금 날짜 간격 표준편차·월 전후반 편향·이체·포기·방문 수를 대조한다. 계좌 소유권, 중복 effect, 정정 부모 누락·분기, 이체 합계와 시각도 확인한다. 검증한 COMPLETE 월 개수를 따로 기록하므로 PARTIAL만 있는 실행을 수치 계산 검증으로 해석하지 않는다.

실제 PostgreSQL 서비스로 6월 입금 1,000/2,000/3,000원, 출금 500원, 이체 100원, 도착 위시 포기, 상호 프로필 방문을 실행하고 7월 Python 추천을 호출하는 통합 테스트가 있다. 작성자의 순저축 5,500원, 입금 3회, 입금 간격 표준편차 0.5일, 이체·포기·방문 각 1회와 일치해야 한다. 캡처 요청의 각 지표를 하나씩 변조하면 실패해야 한다.

## 과거 방문 신호

`SimulationFeedVisitVerifier`는 원본 프로필 방문과 해당 actor/event에 묶인 `feed_visit_evidence`를 대조한다. 추천 시각 직전 90일의 양 끝은 포함하고, 미래 발생·미래 수신·다른 학원·다른 방문자의 사건은 제외한다. 작성자 방문 신호는 프로필 방문에서, 카테고리 방문 신호는 COMPLETE 상태의 당시 category IDs에서 계산한다. 현재 후보 접근 권한은 후보 검증에서 별도로 확인한다. 이 검증은 카테고리 분류 모델 자체나 과거 category IDs의 생성 정확성을 증명하지 않는다.

## 증거와 제한

`input-source.json`과 `input-verification.json`은 실제 요청·응답과 별도 파일로 기록한다. CLI의 실제 HTTP 요청은 `raw/feed/event-N-input-source.json`, `raw/feed/event-N-input-verification.json`으로 해시·길이가 있는 raw index에 포함된다. 검증 실패에도 이미 기록한 요청·응답·페이지·원본 행은 남는다. 진단 helper는 HTTP 처리 이후에 검증하므로 원본 행 캡처가 추천 HTTP deadline을 소비하지 않는다.

이 파일은 일회용 로컬 합성 DB 검증용이며 공개 HTTP 응답이나 배포 JAR에 포함하지 않는다. 카테고리·제목·기본 유사도와 대표 선택의 독립 대조는 `feed-similarity-verification.md`에 설명한다. Python 순위 규칙 자체의 독립 검증은 미완료다. `rankingAlgorithmVerified`는 false다. 100명 전체 사건 재생, 최종 canonical export/CSV, selective import/apply/restore, 대표 UI 및 현재 demo 적용도 미완료다. 이전 월 지표·방문 신호 검사 결과는 `verification-a83246.json`, 카테고리·유사도 추가 결과는 `verification-df2b05.json`에 기록한다.
