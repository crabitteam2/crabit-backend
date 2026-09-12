# 실제 추천 순서의 독립 대조

`SimulationFeedRankingVerifier`는 실제 HTTP 요청의 후보와 feature 값으로 `feed-rules-v1`의 최종 카드 순서를 Java에서 독립 재계산한다. Python 순위 함수를 호출하거나 응답 순서를 기대값으로 사용하지 않는다. 앞선 후보·지표·유사도 원본 검증과 구성 조건 검증은 그대로 유지한다.

검증 대상은 기본/제목 유사도, 작성자/카테고리 방문, 저축 유형 관련도, 입금 빈도 유사도, 완료 적합도, 14일 최신성의 가중합이다. COMPLETE 전월 지표만 유형과 속도를 계산하며 분류 우선순위·엄격한 임계값·누락 지표를 구분한다. 동률은 요청 후보 순서로 결정한다. 상위 40개 중간 후보와 보호 후보, 0.7 점수/0.3 중복 감점의 MMR, 카테고리 및 작성자 중복, 최대 20개 선택, 최근 완료·상위 10개 롤모델 보정, 카테고리 간격과 최종 보정까지 대조한다.

추천 순서만 바뀌었고 후보 집합·개수·구성 조건이 모두 유효해도 `FEED_RANKING_ORDER`로 실패한다. 결과를 정렬하여 차이를 숨기지 않는다. 원본 request/response와 저장된 page는 변경하지 않는다. 이 검사는 주어진 요청의 순위를 검증하며 서로 다른 실행에서 같은 시각의 후보 입력 순서까지 동일하게 생성됨을 보장하지 않는다.

실제 Python 결과를 수락한 `SimulationFeedSession`과 진단용 `SimulationFeedExecution`에서 기존 입력·구성 검증 후 순위를 검사한다. 성공했을 때만 `ranking-verification.json`을 생성하고, CLI는 이를 원본 feed evidence index에 길이·digest·event ID와 함께 기록한다. 잘못된 순서는 원본과 앞선 검증을 보존한 채 실패한다. 401, timeout, LATEST 및 HTTP 없는 continuation/빈 페이지에는 새로운 순위 검증 성공 파일을 만들지 않는다. 일반 main/bootJar에는 이 도구가 들어가지 않는다.

테스트는 수작업 기대 순서로 동률, 카테고리/작성자 중복 감점, 최신성의 14일 경계·미래 clamp, 빈 후보 및 집합을 보존한 순서 변조 거부를 확인한다. 실제 로컬 Python HTTP 서비스에 1·3·19·20·39·40·41·60개 후보와 세 지표 조합, 총 24개 시나리오를 보내 정확한 순서를 대조한다. 이 서비스 입력 검사는 합성 단위 입력이며 100명 실제 이력의 DB 재생 증거와 구분한다. 실제 PostgreSQL/Java/Python pipeline과 두 DB replay 검사는 새 검증 파일 및 raw index를 확인한다.

액션 `act-0c60030dd17a29fee95bf3376bce0935`의 명령, 결과, 생략 검사와 정확한 파일 digest는 `verification-0c6003.json`, 변경 목록은 `action-files-0c6003.json`을 따른다. 전체 100명 이력·canonical 데이터/CSV·selective apply/restore·대표 UI·Owner 콘솔 보존과 현재 demo 적용은 아직 미완료다. Feature Run/action state, 승인 계약, 다른 저장소는 변경하지 않는다.
