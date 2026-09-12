# 멱등성 기록의 요청·응답 대조

`SimulationIdempotencyVerifier`는 새 일회용 DB에서 실제 실행한 CREATE, DEPOSIT, WITHDRAW, TRANSFER, COMPLETE, ABANDON, DELETE의 성공 결과와 `student.wish_idempotency_records`의 정확한 집합을 대조한다. 입력 bundle의 PASS 표시는 사용하지 않는다. 실행기가 보관한 명령과 실제 응답을 사용하며, 거절·실패한 명령은 새 기록을 요구하지 않는다. 같은 학생과 키의 최초 성공이 저장 기록을 소유한다. 후속 성공은 같은 요청 fingerprint·대상과 최초 응답을 유지하고 replayed=true여야 한다.

fingerprint는 현재 도메인 형식의 길이 접두 UTF-8 SHA-256을 simulation 코드에서 독립 계산한다. CREATE v3의 계좌, NFC와 경계 공백 정규화한 목적, 목표액, 시작·목표일, null 사진을 포함한다. 나머지는 실제 계좌·위시 UUID와 금액·낙관적 버전을 포함한다. 실제 export의 원본 fingerprint가 먼저 일치해야만, 같은 필드 중 신원만 논리 ID로 바꾼 hash를 별도 비교 projection에 넣는다. 원본 요청·응답·relational export bytes와 해시는 유지한다. 잘못된 hash를 지우거나 고정값으로 바꾸지 않는다.

저장 기록의 닫힌 필드 집합, operation/target, HTTP 상태, 원장 ID, 양쪽 snapshot, 최초 기록·원장 시각 및 NO_PHOTO 상태도 실제 최초 성공 응답과 대조한다. 누락·추가 키와 변조를 거부한다. 검증 결과는 정확한 원본 record 내용에 바인딩되므로 그 뒤 바뀐 record를 정규화에 넘기면 실패한다. 비교할 record를 찾기 위해 현재 mutable wish 상태를 사용하지 않는다. 이 검사는 새 DB의 현재 서비스 형식에 한정되며 legacy fingerprint 및 사진 업로드/폐기를 지원하지 않는다.

`simulationRun`은 raw/state/id-map을 저장한 후 검증하고 `idempotencyReconciliationPerformed`, `idempotencyRecordsVerified`를 기록한다. 검증 실패 때 원본은 남고 성공한 normalized-relational 파일은 만들지 않는다. 명시적 검증 없이 normalizer를 호출하는 기존 진단 경로는 fingerprint를 그대로 보존하고 opaque로 표시한다. `allRuntimeValuesNormalized=true`는 그 projection의 runtime 신원을 처리했다는 뜻이며 최종 bundle·전체 도메인·실제 적용 승인이 아니다.

실제 두 DB의 17개 금전·위시·재시도·거절·소셜·클릭·서명된 페이지 명령에서 38개 테이블 모두를 비교한다. 이전 student 제외는 제거했다. 별도 전체 금전 시나리오에서 7종 멱등 명령을 검증하고 fingerprint·target·operation·HTTP 상태·snapshot·event/time·photo 상태·누락·추가 기록 변조를 거절하며 DB와 원본 export 보존을 확인한다. 추가 검사는 NFC·경계 Unicode 공백·시작/목표일, 학생별 동일 키, 검증 후 변경된 record의 정규화 거부를 실제 DB에서 확인한다. 정확한 실행 결과는 `verification-af65ee.json`에 기록한다.

최종 canonical bundle/legacy CSV, 나머지 명령·기간·annotation·월 예산, 실제 Python 추천/recap, 100명 전체 이력의 두 DB 재현성과 cutoff 격리, selective apply/restore, 대표 API/UI/DB·Owner 외부 콘솔 보존 및 현재 demo 적용은 후속 구현/검증 범위다. 이번 변경은 simulation 코드와 검증 문서에 한정되며 main 서비스·migration·승인 OpenAPI/schema bytes는 변경하지 않는다.
