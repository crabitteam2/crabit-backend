# 재현 가능한 피드 다음 페이지 명령

시뮬레이션 `FEED_QUERY.command.cursor`는 기존 null/실제 서비스 커서와 함께 `event:<선행 eventId>:nextCursor` 문자열을 지원한다. 예를 들어 `event:feed-001:nextCursor`는 이번 재생에서 실제로 성공한 `feed-001` 응답의 `nextCursor`를 사용한다. 참조된 eventId는 현재 명령의 `causes`에도 있어야 한다. eventId 내부의 콜론은 보존된다.

이는 simulation 전용 입력 해석이다. 기존 JSON schema의 문자열 필드 안에서 처리하며 공개 API, 승인된 OpenAPI와 schema bytes는 변경하지 않았다. 일반 서비스에 이 문자열을 직접 보내는 HTTP 기능을 추가하지 않았다. `event:`는 이 실행기의 논리 참조 접두사로 예약된다. 그 외 문자열은 그대로 서비스에 전달하므로 잘못된 실제 커서의 거절 증거도 재생할 수 있다.

입구 timeline과 dispatcher는 참조의 구문, 더 작은 sequence, 명시적 원인, 같은 학생/학원, 성공한 FEED_QUERY를 검증한다. 실행 단계는 실제 저장된 응답에도 APPLIED와 비어 있지 않은 nextCursor가 있는지 확인한다. 마지막 페이지의 null 커서는 첫 페이지 요청으로 변환하지 않고 실패한다. 임의 DB 키, 기대 결과, 외부 파일로 커서를 구성하지 않으며 서명을 만들거나 고치지 않는다. 만료/페이지 문맥 검증은 실제 BehaviorService/SharedCardCursor가 수행한다.

원본 명령 객체와 journal의 request bytes는 논리 참조를 그대로 보존한다. 서명 커서는 앞선 실제 response artifact에 남고 실제 서비스 호출 때만 사용된다. 정규화된 응답은 기존 검증된 cursor projection을 이용한다. raw request 파일은 실제 HTTP wire 요청이 아니라 기존 규약대로 원본 논리 명령이다.

`SimulationFeedContinuationIT`는 같은 9개 명령 바이트를 두 독립 disposable PostgreSQL에 재생한다. 실제 학생 둘, 위시 두 개와 공유, 첫 페이지, 다음 페이지, 정확히 5분 뒤의 만료 거절을 실행한다. 서로 다른 실제 서명 커서와 동일한 정규화 응답 digest, 원본 request bytes 보존, 관계형/금융/행동 검증을 확인한다. 원인 참조가 빠진 입력은 DB/journal 생성 전 거절한다. 추가 단위 테스트는 잘못된 구문·미래/누락 선행 명령·다른 학생/학원·잘못된 종류/실패 선행 결과·실제 다음 커서 부재를 거절한다.

정확한 테스트 및 파일 증거는 `verification-ca37db.json`에 기록한다. 전체 100명 행동 이력, Python 추천/리캡, canonical 관계형/CSV export, 월 예산, ADJUST_ALLOCATION/CORRECT, 기간/annotation 실행, 선택 apply/restore, 실제 대표 UI 및 demo 적용은 여전히 미완료다. 기존 간헐적 feed-history 시각 역행의 근본 원인을 수정한 작업은 아니다. Feature Run/action state, commit, 원격 상태는 변경하지 않았다.
