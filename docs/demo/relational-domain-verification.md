# 관계형 재생 상태의 시간·가입·피드 이력 대조

액션 `act-f6d498e73fb9444ab8dd2eeae2d29c00`은 기존 38개 테이블의 PK/UNIQUE/FK 검사 다음에 `SimulationRelationalDomainVerifier`를 실행한다. `simulationRun`은 이미 실제 DB에서 읽은 `state/relational.json`과 catalog를 파일에 보존한 뒤 검사한다. 실패해도 원본 export를 수정하지 않고 성공 정규화 출력을 생성하지 않는다.

검사 입력의 학생·계좌·학원·membership 신원과 가입 시각은 성공한 JOIN 및 실행기의 실제 신원 바인딩에서 가져온다. 실제 계좌 소유자와 학원, 열린 상태, membership의 정확한 가입 시각, 합성 logical ID·학년·Owner 플래그를 대조한다. 관계형 FK만 통과하는 다른 학생 소유자나 잘못된 가입 시각도 실패한다. 성공한 JOIN이 없는 학생의 행은 허용하지 않는다.

신뢰 catalog의 `timestamptz` 컬럼은 PostgreSQL JSON 시각을 UTC instant로 해석하며 마이크로초 정밀도를 검사한다. 사건·저장 시각은 데이터셋 시작 이상, 마지막 실행 명령 시각 이하, 데이터셋 종료 미만이어야 한다. 계좌·참여 학생이 있는 행의 사건 시각은 해당 학생 가입 전일 수 없다. 종료 시각이 시작보다 이른 lifecycle 구간도 거부한다. 데이터셋 `ends_at`은 고정 계약 종료값이고, feed page의 `expires_at`은 생성 시각 + 5분이어야 하므로 미래값을 일괄 거부하지 않는다. 날짜형 목표일에는 이 사건 시각 규칙을 적용하지 않는다. 현재 검사는 지원된 동기 재생 경로 전용이며 향후 recap retry 예약 시각 같은 다른 의미의 시간 필드를 연결할 때는 명시적 규칙과 검증이 필요하다.

피드 이력의 6종 source는 고정 집합이다. 각 payload의 source ID·컬럼 집합·FK·해당 버전 경계 이전 시각을 검사한다. 같은 source의 연속 버전 종료와 다음 시작이 일치해야 하며, 현재 source 행은 정확히 마지막 열린 payload와 일치해야 한다. 삭제된 source의 닫힌 이력은 보존할 수 있다. 모든 현재 source에는 이력이 있어야 하고 behavior/feed 수집 baseline은 합성 시작 시각이어야 한다. 원래 snapshot을 현재 값으로 덮어쓰지 않는다.

이 검사는 금액 oracle, SQL 참조 검사, 기존 시각 동기화에 추가한 독립 검증이다. feed-history 시각 역행을 탐지할 수 있지만 기존 일반 API 테스트에서 발생했던 역행 원인을 고친 것은 아니다. 전체 id-map/JSON 내부 참조/canonical CSV, 모든 도메인 invariant, 월 예산 적용, 실제 Python, 100명 전체 이력 재현성, apply/restore, UI/Owner 콘솔 보존과 현재 데모 적용은 여전히 남아 있다. 결과의 `fullDatasetValidationPerformed`는 false다.

새 회귀 사례는 실제 PostgreSQL 재생 export에서 미래 wish 시각·마지막 명령 이후 updated_at·잘못된 가입/논리 ID·누락 이력·변조된 현재 payload·끊긴 구간·잘못된 source ID를 거부한다. 별도 늦은 가입 사례는 전역 기간 안이더라도 가입 전 wish를 거부한다. 두 독립 DB의 기존 돈·소셜·피드 페이지 재생에도 새 검사를 연결하여 정상 미래 만료 시각과 역사적 변경을 검사한다. 정확한 실행 결과와 파일 digest는 `verification-f6d498.json`을 따른다.
