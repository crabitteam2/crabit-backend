# 성공 사례 작성자의 이전 달 지표 대조

액션 `act-4be61e71fb1130b57286f0abcf6bf4f1`은 종료 시점에 보존한 실제 `peer_source` 원장·위시·계좌 행과 `behavior_event`를 사용해 각 성공 사례의 `author_previous_month`를 독립 재계산한다. `SimulationRecapAuthorVerifier`는 production snapshot이나 `authorMetrics`를 호출하지 않는다. 완료일이 속한 한국 시간 달의 직전 달 [시작, 다음 달 시작)을 사용하며, 조회자의 리캡 기간을 작성자의 월로 대체하지 않는다.

정정은 종료 시점 이전에 실제 존재한 연결 이력을 합산하고 원본 사건 시각에 귀속한다. 잘못된 부모·분기·순환·미래 사건·다른 계좌의 위시 효과는 거절한다. 계좌 전체의 양수 입금 효과를 입금 횟수와 고유 입금일에 반영하고 인출을 차감한다. 이체 OUT은 횟수만, 완료·포기·삭제 반환은 저축액에 합산하지 않는다. 평균 금액, 고유 입금일 간격의 모집단 표준편차, 16일 0시 전후 순저축 편향을 계산한다. 입금일이 두 개 미만이면 regularity는 null, 순저축이 양수가 아니면 pace는 null이다.

포기는 작성자 계좌에서 직전 달에 포기된 현재 ABANDONED 위시를 센다. 방문은 같은 학원에서 작성자가 수행한 PROFILE_VISIT 중 직전 달에 발생하고 종료 시점까지 수신된 행을 센다. 중복 방문도 각각 포함하며, received_at이 종료 시각과 같으면 포함하고 1마이크로초 뒤이면 제외한다. 지표 버전과 정확한 필드 집합, 정수 개수·금액, 실수·null 의미를 대조한다.

종료 dispatcher가 기존 기간·또래·후보 검증에 이어 이 검사를 실행한다. 모두 통과해야 `period-verification.json`에 `authors.authorsVerified`를 쓰고 실제 Python 호출로 진행한다. 실패 전에 보존한 요청과 원본 행, 이미 준비된 generation은 남으며 검증 실패를 무효과로 보고하지 않는다.

7개 단위 테스트는 월 경계, 계좌 전체 금액, 반환·이체 제외, 모든 지표 변조, 정정의 원본 시각 귀속, 같은 날 복수 입금, 표준편차, 비양수 저축, 방문 발생·수신 시각과 잘못된 원장을 다룬다. 실제 PostgreSQL/Python 통합 테스트를 추가해 6월 1,000원 입금 후 7월 완료·공유한 사례를 7월 첫 주 리캡에서 확인한다. 작성자의 6월 지표는 입금 1회·순저축 1,000원이고 실제 Python HTTP 200을 확인한다. 저장 요청의 total_savings를 9,999원으로 바꾸면 검증이 실패하며 원본 바이트는 유지된다. 증거는 `build/simulation-recap-author-metrics/`, 정확한 전체 검사는 `verification-4be61.json`을 따른다.

현재 원본 검증은 Python 입력 지표에 대한 증거다. Python 최종 story 선택·조회 시 재권한 확인과 사진 서명에 대한 독립 증명은 남아 있다. 익명 또래 배열·같은 시각 UUID 정렬의 두 DB 재현성, 실제 추천 Python, 100명 전체 혼합 이력, 최종 canonical bundle/CSV, selective apply/restore, 대표 UI와 최신 Owner 보존, 현재 demo 적용도 아직 완료하지 않았다. ADJUST/CORRECT는 승인된 사건 union에서 여전히 실행 명령으로 지원하지 않는다.

이번 액션은 simulation 소스·테스트와 문서만 수정했다. 기존 미커밋 변경을 보존했고 main 소스, 승인된 계약 bytes, Feature Run·action, commit과 원격 상태를 변경하지 않았다. JAR 검사는 구조적 제외만 확인한다.
