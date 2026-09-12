# DATA / FRONTEND 실행 인터페이스

아래 명령은 backend 저장소 루트에서 실행한다. 출력 디렉터리는 존재하지 않아야 한다. `api/demo-simulation-v1.schema.json`은 외부 신뢰 schema이며 bundle 안의 같은 bytes와 비교한다. `scripts/demo/`의 fixture/schema 편집 스크립트는 생성기 인터페이스가 아니다.

## 1. 실제 결과를 받는 정책 세션

DATA는 UTF-8 JSON `policy-session.json`을 만든다. 정확한 키는 `schemaVersion:1`, `datasetId`, `inputDigest`, `students`다. students는 승인 `$defs.students` 전체 100명 배열이며 inputDigest는 정책 입력의 digest다. 최종 manifest digest와 같다고 주장하지 않는다.

```sh
./gradlew --quiet simulationSession --args='api/demo-simulation-v1.schema.json /private/tmp/policy-session.json /private/tmp/discovery-1' < /private/tmp/steps.ndjson
```

실제 정책 오케스트레이터는 위 프로세스의 stdin/stdout을 열어 유지한다. 각 요청은 한 줄이다.

```json
{"operation":"STEP","event":{"eventId":"join-3-00","sequence":1,"kind":"JOIN","actorStudentId":"student-3-00","occurredAt":"2026-05-31T15:00:00Z","causes":[],"command":{"studentId":"student-3-00","accountId":"account-3-00","academyId":"academy-1","grade":3},"outcome":{"status":"APPLIED","resultRef":"raw/results/1.json"},"artifactRefs":["raw/results/1.json"]}}
```

위 JOIN 예시는 policy-session의 해당 학생 신원·가입 시각과 일치할 때 사용할 수 있다. 다른 종류도 승인 `$defs.event`의 필드를 모두 채운다. stdout의 `CRABIT_SIMULATION_V1 ` 접두사를 가진 줄만 프로토콜이다. `READY` 후 각 STEP은 `STEPPED`, 실현된 `event`, 실제 `result`, 추가 `newIdentities`, `completedEvents`를 반환한다. 실제 거절도 실현된 outcome에 기록된다. FEED_QUERY intent의 `orderedCardIds`는 빈 배열이어야 한다. 반환 event에는 실제 논리 카드 순서가 채워진다. 이 순서를 읽고 IMPRESSION/CLICK/후속 결정을 만든다. 커서 참조는 `event:<eventId>:nextCursor`, 원인은 `causes`에 이전 feed event를 포함한다.

마지막에 `{"operation":"FINISH"}`를 보내면 공통 replay finalizer를 실행한다. FINISH 없는 EOF는 실패한다. 원본 요청은 raw에, 실현된 명령은 `events.ndjson`에, export·독립 검증은 고정 replay와 같은 경로에 남는다. `DISCOVERY_COMPLETED`는 fixed replay가 추가로 필요하다는 뜻이다. 한 STEP과 프로토콜 줄은 1 MiB 이하다.

실제 Python을 쓰려면 DATA가 준비한 로컬 서비스를 다음 환경변수에 지정한다.

| 변수 | 값 |
|---|---|
| `CRABIT_SIMULATION_FEED_URL` | 실제 feed_service의 `/internal/v1/feed-rankings` URL |
| `CRABIT_SIMULATION_FEED_TOKEN` | 서비스의 `FEED_RANKING_CREDENTIAL`과 같은 값 |
| `CRABIT_SIMULATION_RECAP_URL` | 실제 recap_service의 `/internal/v1/recap-generations` URL |
| `CRABIT_SIMULATION_RECAP_TOKEN` | 서비스의 `CRABIT_RECAP_TOKEN`과 같은 값 |

서비스 시작은 DATA 저장소의 `python3 -u -m feed_service --host 127.0.0.1 --port 0`, `CRABIT_RECAP_HOST=127.0.0.1 CRABIT_RECAP_PORT=0 python3 -u -m recap_service`를 사용할 수 있다. 실제 ready 출력의 포트/URL을 사용한다. 토큰은 환경으로 전달하고 산출물에 넣지 않는다. 테스트에서 쓰는 `CRABIT_SIMULATION_DATA_ROOT`/`CRABIT_SIMULATION_PYTHON`은 서비스 subprocess 위치를 지정한다.

## 2. 확정 bundle의 두 DB 재생

DATA는 실현된 이벤트를 [bundle 계약](command-envelope.md)의 manifest/files/raw index/validation/normalized 파일과 함께 조립한다. manifest SHA-256은 실제 파일 bytes에서 계산한다. 각 호출은 독립된 새 local PostgreSQL을 만든다.

```sh
./gradlew --quiet simulationRun --args="$BUNDLE_DIR api/demo-simulation-v1.schema.json $MANIFEST_DIGEST /private/tmp/fixed-replay-1"
./gradlew --quiet simulationRun --args="$BUNDLE_DIR api/demo-simulation-v1.schema.json $MANIFEST_DIGEST /private/tmp/fixed-replay-2"
```

`BUNDLE_DIR`/`MANIFEST_DIGEST`는 DATA가 만든 실제 bundle 경로/`sha256:<64 hex>`다. raw bytes는 각 실행의 실제 UUID·커서를 보존한다. 비교에는 `normalized-backend.json`, `normalized-relational.json`, `normalized-responses.json`, 해당 실행이 만든 normalized feed/recap 산출물을 사용한다. 실제 import 입력은 `state/relational.json`이다. `state/export.json`은 현금 sub-export이므로 전체 관계형 복원 입력으로 사용하지 않는다. 전체 100명 품질·기간·월 예산·변동 정책·CSV 검증과 결과 조립은 DATA가 완료한다.

### 확장된 유한 용량 한도

| 대상 | 상한 |
|---|---:|
| manifest `files` | 262,144개 |
| manifest 원본 bytes | 128 MiB |
| 개별 artifact / raw / 관계형 export 입력 / 선택 백업 | 128 MiB |
| manifest에 선언한 artifact bytes 합계 | 8 GiB (manifest bytes 별도) |
| 외부 신뢰 schema | 1 MiB |
| 파일별 기록 수 | 1,000,000개 |

최소 80명 × 14주 = 1,120개 주간 마감은 마감당 최소 11개 RAW로 12,320개 파일을 요구한다. 월 마감·늦은 가입자·방문 전에도 기존 4,096개/manifest 1 MiB 제한을 넘는다. 새 파일 수 한도는 약 2,000회 마감과 100명×102일의 일일 feed/visit를 둔 보수적 파일 수 예산에도 여유를 둔다. 이는 생성 정책이나 실제 사용량을 대신하는 수치가 아니다. 모든 raw를 보존하며, 개별 파일은 writer/reader/schema/관계형 입력에서 같은 상한을 사용한다.

manifest 상한은 최대 길이의 파일 항목을 최대 개수만큼 나열하는 metadata를 수용한다. 합계 초과는 artifact를 읽기 전에 거부한다. 이 값들은 허용 상한이며 8 GiB를 실제 heap에서 처리했다는 성능 증거가 아니다. Reader는 검증 bytes를 메모리에 보유하므로 DATA는 실제 전체 run의 bytes·파일 수·peak memory를 기록하고 실행 JVM 자원을 맞춰야 한다. STEP/HTTP 요청 등의 별도 메시지 제한은 artifact 크기와 다른 용도다.

API schema 파일의 변경은 이 용량 상한 세 곳뿐이다. DATA는 이전 schema digest를 재사용하지 말고 현재 `api/demo-simulation-v1.schema.json` 원본 bytes와 새 digest를 복사해 bundle을 조립한다.

## 3. 백업, 적용과 복원

이는 HTTP API가 없는 별도 `simulationImport` 프로세스다. runtime bootJar에는 포함하지 않는다. V21이 적용된 target PostgreSQL과 `crabit_demo_manager` 역할을 사용할 관리 로그인, 현재 Owner 기준 그래프가 필요하다. 일반 애플리케이션 로그인은 manager/DB owner/superuser가 아니어야 한다. V21의 역할 생성에는 migration 권한 또는 사전 역할 준비가 필요하다. 해당 환경 작업과 실제 대상 쓰기는 exact controller action으로 실행한다.

JDBC 접속은 명시적인 `jdbc:postgresql://127.0.0.1:<port>/<database>` 또는 `localhost` URL만 받는다. 외부 대상에는 별도로 승인된 tunnel/접속 준비가 필요하다. `CRABIT_SIMULATION_TARGET_JDBC_URL`, `CRABIT_SIMULATION_TARGET_DB_USER`, `CRABIT_SIMULATION_TARGET_DB_PASSWORD`를 환경으로 제공한다.

PREPARE config의 정확한 키는 `schema`, `relational`, `students`, `personas`(각 파일 경로), `manifestDigest`, `targetIdentity`, `consoleBaselineDigest`, `codeSha`다. 대상 신원 tag와 digest/SHA는 실행자가 실제 관측/코드에서 제공한다.

```sh
./gradlew --quiet simulationImport --args='PREPARE /private/tmp/import-config.json /private/tmp/import-plan'
./gradlew --quiet simulationImport --args='APPLY /private/tmp/import-plan/prepared-request.json /private/tmp/import-result'
```

PREPARE는 같은 잠금 창에서 target snapshot을 잡고, 원본 backup을 먼저 저장한 후 SQL 변경을 실제 실행하고 rollback하는 dry-run을 수행한다. 성공 시 `backup.json`, `unbound-request.json`, `dry-run.json`, `prepared-request.json`, `preparation.json`이 생긴다. target 타입으로 정규화된 후보에 현재 Owner 그래프와 네 대표 metadata가 포함된다. source의 raw export를 바꾸지 않는다.

APPLY는 `submitted-request.json`을 먼저 기록하고 단 한 번 실행한다. SQL은 revision/전체 snapshot/후보 행/스키마/Owner 그래프/합성 99명 provenance/현금 cache/FK/고정 제외 대상을 검증하고 journal을 같은 transaction에 기록한다. 반환 뒤 별도 authoritative DB read-back에서 journal ID와 after fingerprint를 대조한다. 실패 후 자동 재시도하지 않는다. 정확히 같은 요청과 변화 없는 DB의 명시적 재호출은 `NO_OP`지만, transport/commit 불명확 상태에서는 먼저 INSPECT로 확인한다.

INSPECT 입력은 `{"targetIdentity":"실제 준비 때 사용한 tag"}`다.

```sh
./gradlew --quiet simulationImport --args='INSPECT /private/tmp/inspect-config.json /private/tmp/import-inspection'
```

복원 config의 정확한 키는 `backup`, `backupDigest`, `consoleBaselineDigest`, `codeSha`다. checksum을 검증한 원본 backup과 현재 적용 journal/전체 fingerprint가 일치해야 한다.

```sh
./gradlew --quiet simulationImport --args='RESTORE_PREPARE /private/tmp/restore-config.json /private/tmp/restore-plan'
./gradlew --quiet simulationImport --args='APPLY /private/tmp/restore-plan/prepared-request.json /private/tmp/restore-result'
```

RESTORE도 먼저 dry-run을 실행한다. 원래 도메인 행과 sequence state를 복원하되 감사 journal과 `RESTORED` dataset metadata를 남긴다. 적용 후 같은 건수의 변경, 새 행, sequence 증가, schema 변화도 drift로 거부한다. 기존 Owner 관측·원장·위시·공유·관계·방문과 media/키/전역 수집 시작은 유지한다. Owner 외부 콘솔 확인은 이 프로세스가 수행하지 않으며 모든 실제 적용 보고서의 `externalConsoleVerified`는 false다. 별도 console read-back을 수행해야 한다.

## 4. FRONTEND 대표 신원

Backend는 `demo` profile과 `crabit.demo.simulation.enabled=true`에서 APPLIED 100명/학년별25명/Owner1명/학원1개/활성 계좌·membership/대표4명을 검증한다. 기존 여섯 persona 자격증명과 별도로 `CRABIT_DEMO_TOKEN_GRADE_3`~`CRABIT_DEMO_TOKEN_GRADE_6`을 서버 환경에 설정한다. `personas.json`의 grade alias와 실제 logical account 매핑을 사용하고, 서버가 import된 UUID로 principal을 해석한다.

대표용 새 데이터 API나 UUID 선택 API는 없다. FRONTEND는 기존 demo의 서버 측 persona 인증 경로에 네 alias를 연결하고, 기존 Authorization 방식으로 `/v1/me/card-balance-accounts`를 조회해 계좌 ID를 얻는다. 이어 기존 `/v1/card-balance-accounts/{id}`, 위시/대표 위시/피드/주간·월간 recap API를 사용한다. 계약은 변경하지 않은 `api/openapi.yaml`이다. 토큰을 화면·CSV·공개 bundle에 넣지 않는다. 대표 전환 후 실제 backend 응답/DB 저장/화면이 같은 학생을 가리키는지는 FRONTEND가 검증한다.
