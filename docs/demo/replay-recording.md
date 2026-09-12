# 실제 서비스 재생과 원본 기록

`simulationRun`은 읽기 전용 bundle admission을 통과한 입력을 실제 Java 도메인 서비스와 새 전용 PostgreSQL DB에서 실행한다. 입력 manifest의 정확한 SHA-256과 별도 신뢰 schema가 필요하다. 현재 지원하는 21종 명령 전체와 인과관계·원본 경로를 실행 전에 검사한다. 지원하지 않는 명령, 빈 사건 목록, CREATE 사진, 중복·대소문자 충돌·부모 파일 충돌 경로는 DB 실행 전에 실패한다.

```sh
./gradlew simulationRun --console=plain --args='<bundle-directory> <trusted-schema-path> <expected-manifest-sha256> <new-output-directory>'
```

출력 디렉터리의 부모는 존재해야 하며 새 디렉터리 자체는 없어야 한다. 입력 bundle 내부 출력은 거부한다. 출력은 소유자 전용 디렉터리에서 CREATE_NEW로 기록하고 파일을 fsync한다. 기존 실행을 덮어쓰거나 이어서 재실행하지 않는다. 파일 경로는 raw/ 하위만 사용하며 symlink 부모·파일, traversal, 예약한 raw/index.json을 거부한다. 개별 파일 기록 후 프로세스가 비정상 종료되면 불완전 출력이 남을 수 있다. 완성된 index/observation이 없는 디렉터리는 완료 증거로 사용할 수 없다.

각 REQUEST 파일은 원본 events.ndjson의 해당 행 바이트다. NDJSON LF 구분자만 제외하며 공백·필드 순서·논리 ID를 변경하지 않는다. 이는 논리 Java 명령 envelope로, HTTP 요청 또는 Python 모델 요청의 원본이라고 주장하지 않는다. 명시한 requestRef가 없으면 raw/requests/event-<sequence>.json을 사용한다. RESPONSE는 dispatcher가 실제 도메인 반환값을 처음 직렬화한 바이트이며 재작성하거나 UUID를 정규화하지 않는다. outcome.resultRef와 responseRef/observationRef가 있다면 해당 경로에 같은 실제 결과 바이트를 기록한다. 같은 사건 내 응답 별칭은 한 번 기록하며 사건 사이 경로 재사용은 거부한다.

raw/index.json은 기존 닫힌 schema를 따른다. 각 경로·길이·SHA-256·eventId·REQUEST/RESPONSE를 기록하고 경로순 정렬한다. 현재 모든 레코드는 BACKEND이고 modelVersion은 null이다. 기대 응답 또는 기존 bundle 원본을 실제 새 실행 결과로 복사하지 않는다. 원본 요청은 서비스 호출 전에 기록하고, 예상 outcome 또는 피드 순서가 다르면 실제 불일치 응답을 기록한 뒤 다음 사건을 실행하지 않는다. 실제 도메인 거절이 예상과 일치하면 정상적으로 기록하고 다음 사건으로 진행한다.

replay-observation.json의 REPLAYED_PARTIAL_VALIDATION은 지원 명령이 예상 status/현재 피드 순서 검사까지 완료됐다는 의미다. fullDatasetValidationPerformed, readyForApplication, pythonInvoked, relationalStateExported는 모두 false다. 실패한 실행은 FAILED와 마지막 시도 사건, 결과가 확인·기록된 완료 건수를 남긴다. 예외 메시지는 입력·환경 값 노출을 피하려고 저장하지 않는다. execution-identities.json은 진단용 실제 UUID 매핑이며 완성된 typed id-map.json이 아니다. 출력 디렉터리는 완성된 canonical bundle 또는 apply/restore 입력이 아니다.

실행기는 컨트롤러 상태·액션 상태를 변경하지 않는다. 로컬 새 DB만 사용하며 기존 DB URL·원격 apply·provider·Python을 호출하지 않는다. 원본 source bundle은 읽기 전용이다. ADJUST_ALLOCATION/CORRECT·주월 마감·annotation·전체 상태/FK/legacy CSV export·전체 독립 oracle·100명 두 DB 재현성·apply/restore·대표 UI·Owner 콘솔 보존·현재 demo 적용은 후속 구현/검증이 필요하다.

새 검사는 실제 PostgreSQL에서 JOIN→GRANT→잔액 부족 거절의 실제 응답·시각·원본 공백 보존, outcome 불일치의 응답 보존·후속 중단, output 재사용 거부, 사전 unsupported/case collision 검출, source 내부 출력 거부, symlink/기존 파일 보존, opaque 바이트 무변환, 빈 NDJSON 거부를 검사한다. 전체 suite의 정확한 실행 수·시각·digest는 verification-94bde2.json을 따른다.


## 실제 응답 비교 파일

전체 요청 실행과 종류별 신원 정규화가 성공하면 `normalized-responses.json`을 새 파일로 기록하고 replay 관측에 `responseNormalizationPerformed=true` 및 `normalizedResponseDigest`를 넣는다. raw bytes는 변경하지 않는다. 실패·불일치에는 이 파일이 없다. 이 자료는 최종 bundle의 `normalized.json`과 구별되며 전체 DB/state/100명 정규화는 미완료다. 자세한 검증·제한은 `response-normalization.md`를 따른다.
