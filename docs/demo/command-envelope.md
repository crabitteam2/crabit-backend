# 현재 실행 경로 안내

입구·현금 검사에 이어 21종 실제 명령 dispatcher와 `simulationRun` 원본 기록 경로가 구현됐다. CLI의 입력·출력·검증 한계는 `replay-recording.md`를 따른다. 아래 각 액션 설명은 단계별 이력이다. 전체 관계형 export/typed ID 정규화/독립 검증·Python·100명 두DB·apply/restore·제품 적용은 미완료다.

# 시뮬레이션 파일/검증 명령

아래 명령은 backend worktree에서 실행한다. 외부 서비스 또는 DB를 실행하는 명령은 별도로 명시했다. 이 문서는 이전 provisional manifest 설명을 대체한다.

## 번들 입구 검사

- `./gradlew simulationInspect --args='<bundle-directory> <trusted-schema-path> <expected-manifest-sha256>' --console=plain`
- `./gradlew simulationTest --console=plain`: simulation 소스셋 테스트. 현금 서비스 비교 테스트는 disposable PostgreSQL 16 컨테이너를 실행한다.
- `node scripts/demo/verify-admission-schema.cjs`: 기존 Ajv2020와 ajv-formats로 공유 schema의 정상 문서 4개와 schema 거부 벡터 10개를 독립 검증한다. 네트워크 또는 패키지 설치 없음.
- `python3 scripts/demo/create-admission-fixture.py`: 지정된 `src/test/resources/simulation/bundle-contract-valid` 테스트 fixture만 재작성한다. 실제 재생 데이터 생성 명령이 아니다.

`simulationInspect`는 로컬 파일만 읽는다. Java 프로세스 외 DB/Python/Next/브라우저를 시작하지 않는다. 성공은 ADMITTED이며 domainValidationPerformed=false, readyForApplication=false, databaseWritesPerformed=false다. 입력 거부 Java exit 2, 읽기 실패 exit 4이며 Gradle의 자식 exit 변환과 구분한다. manifest 원본 digest를 별도로 제공해야 한다. 토큰/UUID 또는 shell 명령을 인자로 받지 않는다.

`api/demo-simulation-v1.schema.json`은 manifest와 $defs의 config/students/personas 입구 계약이다. 다른 저장소에는 아직 복사하지 않았다. 전체 도메인 import schema 또는 적용 승인을 의미하지 않는다. JSON은 UTF-8, 중복 키/뒤따르는 문서/잘못된 Unicode/강제 형 변환을 거부한다. canonical JSON은 객체 키 정렬, 공백 없음, UTF-8, 배열 순서 유지, JS-safe 정수만 허용한다. Python fixture와 Java reader는 독립적으로 같은 datasetId를 계산한다.

manifest의 schemaKind는 demo-simulation-manifest다. datasetId는 canonical `{schemaVersion,configDigest,codeShas,normalizationVersion}`의 SHA-256이다. configDigest는 canonical config의 hash이며 Java/PostgreSQL/Python/Node/feedModel/recapModel 버전도 config와 manifest에 동일하게 묶는다. logicalDigest는 공급된 normalized.json의 canonical hash를 확인한다. 이것이 원래 사건/상태를 충실히 정규화했는지는 아직 검사하지 않는다. manifestDigest는 manifest 전체 원본 bytes의 hash로 외부에서 전달하며 자기 참조하지 않는다.

files는 path 오름차순, 각각 path/role/byteLength/sha256/recordCount를 요구한다. 고정 파일/역할은 config.json/CONFIG, students.json/STUDENTS, personas.json/PERSONAS, events.ndjson/EVENTS, id-map.json/ID_MAP, state/export.json/STATE, validation.json/VALIDATION, normalized.json/NORMALIZED, demo-simulation-v1.schema.json/SCHEMA다. RAW는 raw/ 아래 복수 파일을 허용한다. RAW는 문법을 바꾸지 않는 불투명한 원본 한 기록(recordCount=1)이며 transport sidecar 내용 검증은 미구현이다. JSON 배열의 count는 요소 수, 객체는 1, NDJSON은 비어 있지 않은 object 줄 수다. NDJSON 내부 빈 줄은 거부하고 마지막 newline은 허용한다.

manifest는 128MiB, trusted schema는 1MiB, 개별 artifact는 128MiB, artifact 합계는 8GiB, 파일은 최대262144개/파일 기록은 최대1000000개다. 합계 검사는 artifact bytes를 읽기 전에 수행한다. 기존 4096개/manifest 1MiB 제한은 필수 1120회 주간 마감의 RAW만으로 초과하므로 확장했다. 실제 전체 DATA 실행의 bytes·파일 수·peak memory는 별도 측정한다. 절대경로, dot segment, 경로 이동, symlink 파일/부모/루트, 대소문자 충돌, 목록 외 파일/디렉터리와 크기/건수/해시 불일치를 거부한다. 파일은 검증한 bytes를 방어적 복사로 반환한다. bundled schema는 신뢰하는 schema와 exact bytes가 같아야 한다.

학생 100명·학년별25명·초기20명/늦은가입5명·Owner1명·단일학원·고유 학생/계좌와 학년별 비Owner 대표4명을 검증한다. 대표의 계좌도 해당 학생 계좌와 같아야 한다. 가입일은 계약 기간 및 7월 이후 늦은가입 조건을 따른다. archetypeInputs는 가정 이름/문자열·정수·boolean 값 배열이며 이름 중복을 거부한다.

## 현금 전용 독립 검사

`./gradlew simulationCashCheck --args='<cash-projection.json> <expected-sha256>' --console=plain`은 cash-oracle-v1.schema.json의 닫힌 projection을 검증한다. 로컬 파일만 읽으며 성공 CASH_VERIFIED와 fullDatasetValidationPerformed=false/readyForApplication=false를 함께 반환한다. 초기 잔액은 0이며 GRANT/PURCHASE 원장만 현금을 바꾼다. 원장/명령 순서를 정렬하여 차이를 숨기지 않는다. 실패 사건은 원장을 만들 수 없다.

GRANT는 budgetMonth/scheduledAt가 필수다. budgetMonth는 예정 시각의 한국 월과 같아야 한다. 월별 실제 지급 합계는 occurredAt의 한국 월을 사용한다. 6월 예정 지급이 7월 1일에 발생했다면 7월에 합산한다. verifyFullMonthBudget는 호출자가 활성·완전 관측 월임을 별도로 확인한 경우 10000..30000원을 검사한다. CLI는 membership/dormancy 자료가 없어 이 함수를 자동 실행하지 않는다.

## 남은 제품 경계

EVENTS의 ADJUST_ALLOCATION/CORRECT command와 실제 실행 결합, id-map의 bijection, state의 고정 테이블/FK/열 schema, RAW sidecar, validation per-rule, normalized의 실제 원본 대응, legacy CSV 호환 projection과 독립 도메인 invariants는 미구현이다. 빈 events/state 테스트 fixture는 실제 100명 데이터가 아니다. simulationRun, Java/PostgreSQL 실제 역사 시계, 인과적 도메인 재생, 실제 Python 추천/리캡, 두DB 재현성, management apply/restore, 브라우저 및 최신 Owner 콘솔 보존/원격 적용은 남아 있다.

`./gradlew test bootJar --console=plain`과 `bash scripts/demo/verify-runtime-isolation.sh`는 일반 백엔드 및 bootJar 구조 검사다. 구조 검사는 런타임 시계/DB/제품 E2E 증명이 아니다. simulation source set은 일반 bootJar에 포함되지 않는다.


## 사건 입력 및 인과관계 입구

canonical schema의 `$defs.event`는 JOIN, RETURN_FROM_DORMANCY, GRANT/PURCHASE, BALANCE_LOOKUP, CREATE/DEPOSIT/WITHDRAW/TRANSFER/COMPLETE/ABANDON/DELETE, FOLLOW/UNFOLLOW/BLOCK/UNBLOCK, SHARE/VISIBILITY_CHANGE, FEED_QUERY/IMPRESSION/CLICK/PROFILE_VISIT/INFLUENCED_DECISION, CLOSE_WEEK/CLOSE_MONTH의 25개 닫힌 변형을 정의한다. `kind`가 command 형식을 결정한다. ID는 logical ID이며 HTTP 경로/본문 UUID와 구별한다. 날짜는 실제 달력 날짜, 시각은 UTC microseconds, KRW/sequence/version은 JS-safe 정수다. visibility는 현재 domain의 PRIVATE/FOLLOWERS/ACADEMY다. CREATE의 nullable 날짜/사진은 wire에서 명시적으로 표현한다. COMPLETE의 confirmed=true는 시뮬레이션 의사결정 증거이며 새로운 HTTP 필드가 아니다. DELETE의 expectedVersion은 기존 If-Match에 연결할 입력이다. `outcome`은 닫힌 `{status: APPLIED|REJECTED|FAILED, resultRef}` 구조다.

reader가 모든 event를 schema로 검사하고 `SimulationEventTimeline`에 전달한다. unique ID/증가 sequence/역행 없는 기간 내 시각, 선행 causes, manifest에 존재하는 artifactRefs와 결과·command 참조, 성공 JOIN의 학생/계좌/학원/학년/가입시각 일치, 활동 이전 성공 JOIN, 성공 사건의 계좌 소유자, GRANT 예정/실제 시각과 KST 예정 월, 이체의 서로 다른 root/effect ID, FOLLOW의 viewer 방향, 명시한 영향 신호의 선행 성공과 같은 actor를 검사한다. outcome이 REJECTED/FAILED인 사건도 그대로 보존한다. CLICK에 선행 IMPRESSION 조건을 추가하지 않는다. PROFILE_VISIT은 독립 방문을 허용한다.

기간 마감은 KST 경계와 정확한 주/월 길이를 검사하고 동일 경계에서 WEEK → MONTH → 일반 사건 순서를 요구한다. 이미 성공한 account/period를 다시 성공 처리할 수 없으며 마지막 완료 주간은 8월31일~9월7일이다. 9월 월간 및 9월7일 시작 주간을 cutoff 전에 완료할 수 없다. 이 검사는 마감의 완전성, 실제 snapshot/Python 결과 존재 또는 DB 시간 동기화를 증명하지 않는다.

`simulationInspect`는 eventAdmissionPerformed=true를 반환하되 domainValidationPerformed=false와 readyForApplication=false를 유지한다. 아직 ADJUST_ALLOCATION/CORRECT는 명령 경로가 구현되지 않아 거부한다. RETURN_FROM_DORMANCY의 실제 dormancy 상태, 접근권한/컨텍스트·위시 잔액·완료 조건, influence 신호의 현재 접근/retention 적격성, 원본 resultRef의 실제 실행 여부는 후속 replay/state validator가 검증해야 한다. 이벤트가 없는 admission fixture를 실제 데이터로 사용하면 안 된다. shared event-schema-vectors.json은 Java 및 독립 Ajv2020에서 같은 결과를 요구한다.

## Replay identity and raw evidence admission

The canonical schema now includes `idMap`, `rawIndex`, and `validation` definitions. `id-map.json` is a versioned `demo-simulation-id-map` object bound to `datasetId`. Its entries are sorted by `entityKind:logicalId`, are bijections within each entity kind, and contain `replayUuid` only. Deployment/target UUID fields are rejected. The student/account/academy mappings exactly cover `students.json`; account mappings own themselves, derived records reference an existing account, and academy/student identity entries have null `ownerAccountId`. These checks do not replace the still-required relational export foreign-key validation.

`raw/index.json` has manifest role `RAW_INDEX` and contains exactly one sorted typed record for every `RAW` payload. Each record binds its path, raw byte length and SHA-256, content type, service (BACKEND/POSTGRESQL/FEED/RECAP), nullable model version, source event ID, and REQUEST/RESPONSE/STORED_DOCUMENT/RUNTIME_OBSERVATION kind. The source event must list the raw path in its artifact references. FEED/RECAP model versions must match the manifest runtime versions; BACKEND/POSTGRESQL have null modelVersion. Opaque bytes are retained without JSON parsing, normalization or UUID substitution. Metadata digest mismatch, missing/unlisted records and references to unrelated events are rejected. This proves evidence integrity and linkage, not that a claimed remote service really ran.

`validation.json` binds dataset/config/schema digests, rule version, named counts and independent aggregates, and per-rule PASS/FAIL/NOT_RUN reports. Each rule has `checkedCount`, typed errors and artifact references. NOT_RUN requires zero checks and no errors; FAIL requires errors; PASS cannot hide errors. Duplicate rules/count names and unresolved artifact references fail. Claimed PASS remains an input report; the reader does not elevate it to independent domain validation.

The admission fixture contains 201 deterministic test-only identity mappings, no historical events and an explicit NOT_RUN domain-replay rule. It is unsuitable for application. Full typed relational state/legacy CSV acceptance, ADJUST_ALLOCATION/CORRECT execution, actual replay clocks and Python transport, selective application/restore and product verification remain required. Existing domain code does not expose a correction command merely because its ledger includes a correction reference column; adding an accepted CORRECT tag before implementing that binding would misrepresent support.


## Bundle cash state reconciliation

`state/export.json` currently has the closed `cashState` definition: schemaVersion=1, schemaKind=demo-simulation-cash-state, datasetId, ledger, balances. This is explicitly a cash-only sub-export; it must be replaced/extended with the final frozen relational export before application is implemented. Unknown tables, SQL, fields, versions, fractional/negative/unsafe amounts are rejected. Ledger fields follow the existing independent cash oracle (id, eventId, accountId, sequence, occurredAt, kind, amountKrw, balanceAfter). Balances contain accountId, amountKrw and sequence in ascending logical account ID order.

The reader derives accounts and join times from students.json and GRANT/PURCHASE commands directly from events.ndjson, preserving event order, outcome, occurredAt, scheduledAt and budgetMonth. No caller-supplied substitute command projection exists in this sub-export. The oracle compares every applied command with exactly one corresponding ledger entry, requires no entry for failed/rejected commands, and compares all 100 final balances, including Owner and zero balances. Non-cash wish operations cannot change cash. The state dataset ID must equal the manifest ID. Cash reconciliation does not prove monthly active-membership budgets, raw outcome execution, whole relational integrity, historical DB clocks, Python or replay/application readiness.

`simulationInspect` adds cashReconciliationPerformed=true on successful admission, while keeping domainValidationPerformed=false and readyForApplication=false. The existing zero-history test fixture remains unsuitable for application. Shared cash-state-schema-vectors.json is checked by Java and Ajv2020; bundle tests recompute transport checksums before testing semantic corruption.


## Local process clock preflight

`./gradlew simulationClockCheck --console=plain` accepts no database URL or other arguments. It builds the local-only clock image and creates a disposable PostgreSQL database, verifies actual SQL time against the injected Java Clock at the start, a microsecond boundary and the last included instant, then closes it. CLOCK_VERIFIED is only clock evidence; fullDatasetReplayPerformed and readyForApplication remain false. See simulation-clock.md for transaction boundaries, image pinning, isolation and remaining whole-runner obligations. The bundle reader does not use this result as admission or application approval.

## Synchronous domain service host

`SimulationDomainRuntime` is a Java-only, simulation-source-set runtime with no CLI configuration, existing database URL, arbitrary shell, web server or scheduler. `simulationTest` starts its freshly owned local PostgreSQL databases and closes each Spring context before stopping its container. It injects the database-controlled Clock into real transactional JPA services. Its BUILDING cash provider has no network client and covers Owner as well as other mapped accounts; live demo routing remains separate. Nested calls, close during a callback, ambient transactions, backwards time and out-of-range/sub-microsecond instants fail. Callbacks are synchronous and must not escape services/JDBC/Clock references. Service transaction boundaries remain intact, including committed PRE_DEPOSIT observations on rejected deposits. It does not yet consume a complete event bundle, run Python, export relational state, or authorize application.


## In-process command dispatcher

`SimulationCommandDispatcher` now executes the 17 cash/wish/social/membership commands described in `command-dispatcher.md` against its owned database. It is not a `simulationRun` CLI, full bundle execution, or an apply entry point. Outcome artifacts and all runtime identity normalization still require orchestration; full domain validation remains false.

## 실제 Python 리캡 검사 추가

`./gradlew simulationTest --tests '*SimulationRecapExecution*' --console=plain`은 backend worktree에서 실제 로컬 PostgreSQL과 형제 data worktree의 Python recap 서비스를 실행한다. 서비스 cwd, 환경키, timeout, 증거 위치와 정리 범위는 [recap-execution.md](recap-execution.md)에 정의했다. 전체 simulationTest에도 포함되며 실제 서비스를 찾지 못하면 실패한다. 이는 독립 실행 helper 검증이며 아직 CLOSE_WEEK/CLOSE_MONTH dispatcher 전체 replay 증거는 아니다.

## 로컬 검증 보존 지문 (현재)

`simulationRun`은 사건 재생 완료 후 독립 export/검증 전후에 자신의 일회용 DB에 read-only/repeatable-read 연결을 열어 보존 지문을 만든다. 각 연결은 query timeout 15초, transaction timeout 30초이며 UTC/ISO 세션 설정만 변경한다. 읽기 대상은 public의 고정 40개 테이블, 열/제약/trigger/function/index/sequence metadata 및 sequence 현재값이다. 테이블 원문과 비밀키/미디어 내용은 PostgreSQL 내부에서 해시하며 클라이언트로 반환하지 않는다. 추가 프로세스, 외부 DB 설정 또는 원격 연결 옵션은 없다. `replay-observation.json`의 validationPreservationBefore/After/Difference와 validationDatabaseUnchanged에 기록한다. 차이는 `REPLAY_VALIDATION_DATABASE_DRIFT`로 실패한다. 이는 현금 export를 전체 백업으로 바꾸거나 selective apply/restore를 구현한 것이 아니다. 자세한 범위는 `preservation-fingerprint.md`를 따른다.
