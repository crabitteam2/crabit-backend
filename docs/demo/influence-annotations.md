# 실제 재생 증거에 연결한 합성 영향 주석

`INFLUENCED_DECISION`은 이미 승인된 사건 스키마를 그대로 사용한다. `SimulationCommandDispatcher`는 이 주석을 기존 DB에 새 도메인 명령으로 저장하지 않는다. 현재 재생에서 실행을 마친 명령과 실제 응답을 읽어 `simulation-influence-annotation` 응답을 만들고, 다른 명령처럼 원본 요청·응답 journal과 정규화된 backend projection에 포함한다. 현금·위시·행동 행을 추가하거나 수정하지 않는다.

주석은 동일 학생의 선행 신호와 그 뒤의 CREATE 또는 DEPOSIT 시도를 직접 causes로 참조해야 한다. sequence와 실제 사건 시각을 모두 검사한다. 결정이 REJECTED/FAILED였어도 시도에 대한 합성 원인은 기록할 수 있으며 실제 decisionStatus를 그대로 남긴다. 주석의 APPLIED는 원인 주석을 기록했다는 의미이며 저축이나 생성이 성공했다는 뜻이 아니다.

IMPRESSION은 실제 APPLIED 응답의 FEED_EXPOSURE 유형과 발생 시각을 확인한다. CLICK은 같은 학생·학원·컨텍스트·카드·위치·impressionId에 먼저 수집된 노출까지 연결한다. 클릭보다 나중에 수집된 노출로 선행 노출을 꾸밀 수 없다. 노출 없는 클릭의 일반 수집 의미는 유지되지만, 그 클릭을 노출 영향의 근거로 쓰는 주석은 실패한다. PROFILE_VISIT은 원본 sourceEventId/causes를 따라 수집된 클릭·노출 또는 동일 작성자의 프로필 방문을 거슬러 올라가야 한다. 학원·작성자 연결과 순환을 검사하며, 노출로 연결되지 않는 직접 프로필 방문은 이 영향 주석의 근거로 인정하지 않는다.

`SimulationInfluenceVerifier`는 실제 응답과 기대 outcome의 일치를 확인하며 응답을 만들어 도메인 결과를 보정하지 않는다. 재생 완료 시 기존 behavior 원장·접근권한 검증 다음에 주석을 다시 재구성하고 원본 주석 응답과 정확히 비교한다. 성공 시 `influenceAnnotationsVerified=true`와 검증 개수를 관측에 기록한다. 신호가 수집될 당시의 접근권한은 실제 서비스 및 기존 독립 behavior verifier가 검증한다. 신호의 현재 공개 여부로 과거 수집 사실을 소급 변경하지 않는다.

이는 합성 생성기가 명시한 인과관계의 참조·실행 증거 검사다. 실제 학생의 인과 효과, 취향 또는 추천 정확도 검증이 아니다. 영향 비율·관심사·자금 제약에 따른 생성기 의사결정, 전체 기간의 신호 보존 정책은 별도 의무다. RETURN_FROM_DORMANCY, CLOSE_WEEK/CLOSE_MONTH, ADJUST_ALLOCATION/CORRECT, 실제 Python 서비스, 100명 전체 이력 재현성과 canonical CSV/bundle, selective apply/restore, 대표 UI/Owner 보존·현재 demo 적용은 이번 변경으로 완료되지 않는다.

실제 PostgreSQL 검사는 공개 카드의 노출→클릭→위시 생성을 실행하고 주석 기록 전후의 전체 도메인 테이블과 현금 export가 같음을 확인한다. 동일 사건 재시도는 원본 응답을 반환한다. 단위 검사는 잘못된 학생/순서/결정 종류, 누락·변조된 실제 응답, 노출 없는 클릭·뒤늦은 노출, 다른 작성자 프로필 참조와 주석 응답 변조를 거부한다. 정확한 실행 증거는 `verification-0891c3.json`에 기록한다.
