# 실제 재생 응답의 논리 신원 정규화

`SimulationCommandDispatcher.normalizedResponses()`는 실제 Java 서비스에서 받은 응답을 별도 비교 자료로 변환한다. `SimulationReplayRun`은 요청한 모든 명령의 실제 outcome이 일치하고 정규화가 성공했을 때만 `normalized-responses.json`을 새 출력 디렉터리에 기록한다. 원본 request/response 파일과 SHA-256은 그대로 유지한다. `replay-observation.json`의 `normalizedResponseDigest`는 이 새 파일의 canonical JSON UTF-8 bytes 해시이며 원본 응답 해시와 다르다.

닫힌 알려진 응답 신원 필드만 변환한다. 학생/Owner, 계좌, 학원, 위시, shared card, balance observation, ledger root와 behavior event는 각각 다른 종류의 역방향 매핑을 사용한다. 같은 UUID라도 entity kind가 다르면 섞이지 않는다. 같은 종류의 다중 논리 별칭과 알려지지 않은 실제 UUID는 거부한다. 위시 생성/배분/종료의 실제 eventId를 첫 실행 명령에 연결하며 idempotent 응답은 처음의 root identity를 유지한다. 이체의 root kind는 schema와 같은 `LEDGER_ROOT`다. 기존 simulation 진단용 `LEDGER_EVENT` 키는 이 이름으로 정정했다. 위시 금액·실제 현금·공개 상태·낙관적 version·replayed·HTTP 상태·null/0·거절 code·시각·배열 순서는 제거하거나 정렬하지 않는다. 설명 문자열 속 UUID는 신원 필드가 아니므로 치환하지 않는다.

non-null nextCursor는 현재 DB의 `SharedCardCursor.decodeV2`로 서명, viewer, academy 바인딩을 확인한다. 별도 정규화 자료에는 version/operation, 논리 student/academy, 최초 관측 명령에 연결된 feed session/page state와 실제 expiresAt을 기록한다. 서명 키와 서명 문자열은 비교 자료에 들어가지 않으며 원본 응답에는 원래 커서를 유지한다. 페이지 순서·컨텍스트 만료·실제 continuation 실행 결과는 보존한다. 입력 커서의 wire 의미를 바꾸거나 다른 DB 커서를 현재 DB용으로 재서명하지 않는다. 명령 계획에서 재생별 continuation을 참조하는 완전한 형식은 아직 구현하지 않았다.

결과 불일치나 재생 실패에는 성공 정규화 파일을 만들지 않고 원본 실패 응답과 FAILED 관측을 남긴다. 알 수 없는 신원이나 검증되지 않은 커서도 정상 비교 결과로 승격하지 않는다. 현재 피드는 LATEST다. Python recommendationResultId가 존재하면 지원하지 않는 정규화로 거부하며 이를 임의 문자열 제거로 숨기지 않는다.

검증은 두 개의 독립 소유 PostgreSQL에서 같은 17개 명령을 실제 실행한다. 두 학생 가입, 지급, 위시 생성/동일 idempotency 재사용, 배분 성공/거절, 잔액 관측, 두 peer 위시 공유, 첫 페이지/선행 노출 없는 클릭/팔로우/서명된 다음 페이지/위조 커서 거절을 포함한다. 원본 페이지 해시는 달라야 하고 정규화된 17개 실제 응답 해시는 같아야 한다. 정확한 해시는 `response-normalization-observation.json`, 전체 검사는 `verification-7538a1.json`을 따른다. 별도 테스트는 정규화 파일과 관측 해시의 일치, 실패 출력의 부재, 원본 bytes 보존, 종류별 신원·null/0/순서/시각 및 알 수 없는 ID 거부를 검사한다.

이 파일은 최종 bundle의 `normalized.json`, 완성된 typed `id-map.json`, relational state 또는 전체 100명 행동 이력이 아니다. `fullDatasetNormalizationPerformed=false`, `fullDatasetValidationPerformed=false`, `readyForApplication=false`를 유지한다. 자동 생성되는 PRE_DEPOSIT 관측·조정·history/checkpoint·feed state graph 전체를 정규화한 것도 아니다. 이러한 DB 상태, FK/CSV, 월 예산/전체 domain oracle, 실제 Python/리캡, 전체100명 재현성과 apply/restore 검증은 계속 필요하다. main 코드·migration·OpenAPI와 bundle schema bytes는 이번 변경에서 수정하지 않았다.
