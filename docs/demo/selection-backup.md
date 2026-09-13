# 로컬 재생 DB의 선택 행 백업

`SimulationCommandDispatcher.backupSelection(selection, expectedFingerprint)`는 dispatcher가 만든 일회용 로컬 DB에서 지정한 primary key 행의 원본 값을 직렬화한다. 신원 변환, import, restore, 외부 DB 연결을 입력받지 않는다. 외부 콘솔도 호출하지 않는다.

호출자는 먼저 `preservationFingerprint()`로 현재 지문을 얻는다. 캡처는 선택 키를 복사하고 40개 공개 테이블에 transaction-level SHARE ROW EXCLUSIVE 잠금을 얻은 뒤 지문을 다시 비교한다. 같은 잠금 트랜잭션에서 최신 관계형 export·도메인 검증·직렬화와 전후 지문 비교를 수행한다. 잠금 대기 중 writer가 commit한 변경은 stale revision으로 거부한다. 정상 종료와 실패 모두 잠금을 해제한다. 상세한 동시성 검증과 sequence·관리자 DDL·외부 콘솔의 제한은 preservation-window.md를 따른다.

백업의 `schemaKind`는 `simulation-local-selection-backup`, `schemaVersion`은 1이다. dataset/catalog/selection digest, 현재 catalog, 전체 DB의 beforeFingerprint, 선택한 원본 행, 선택/보존 양방향 SQL FK 및 기존 typed 의미 참조 경계를 함께 담는다. 선택을 자동 확장하지 않는다. 참조 경계가 열린 선택도 백업할 수 있지만 경계 보고서가 그대로 남고 `readyForApplication=false`, `restoreSupported=false`다. 빈 선택, 중복·누락·잘못된 키, 알려지지 않은 테이블은 거부한다.

선택하지 않은 행은 백업의 tables에 포함하지 않는다. 원본 UUID와 SQL text에 저장된 JSON 문자열은 그대로 보존한다. JSON 객체 키와 선택 행의 primary key 순서는 정렬하며 값, 배열 순서, 문자열 공백은 바꾸지 않는다. PK 선택 순서를 바꿔도 백업 bytes/digest는 같다. 별도로 반환하는 SHA-256은 최종 원본 bytes의 해시이며 self-reference를 만들지 않는다. byte 배열은 생성과 반환 때 복사된다. 산출물은 64 MiB 이하로 제한하며 금액·숫자에는 기존 JS-safe canonical JSON 제약이 적용된다.

`verifySelectionBackup(bytes, expectedDigest)`는 64 MiB 제한과 외부에서 전달한 원본 digest를 먼저 확인하고 strict JSON 파서를 사용한다. 입력 catalog를 신뢰하지 않고 현재 DB catalog에서 PK를 얻어 같은 선택을 다시 캡처한 뒤 전체 bytes를 비교한다. 따라서 같은 건수의 값 변조, 메타데이터 변경, 추가 필드, 비정규 JSON 표현도 거부한다. 캡처 후 새로운 정상 도메인 명령이 생겼다면 `SELECTION_BACKUP_REVISION_CONFLICT`로 거부하고 새 명령을 보존한다. 이 메서드는 원본 상태가 아직 같은지 확인하는 read-back이며 적용 이후의 restore 검증기는 아니다.

cursor 서명키는 export 대상에 없다. 미디어·기존 application journal 행이 있는 DB는 기존 export 정책에 따라 거부한다. 따라서 이 산출물을 기존 데모 DB의 완전한 복원 백업이라고 부를 수 없다. Owner UUID/신원과 외부 콘솔의 최신 전체 기록 보존, 대상 identity/revision/manifest 바인딩, 원자적 공개 domain 교체 및 restore, immutable trigger의 전용 관리 경로와 실제 동시성 검증은 계속 필요하다.

이번 테스트는 실제 PostgreSQL에서 네 도메인 명령을 실행한 graph 전체 및 학생 한 행의 백업을 검사한다. 원본 값 전체·배열 순서·JSON 문자열, 부분 선택의 참조 경계, 반환 배열 격리, 반복 캡처 해시, 재검증, 손상/승격된 메타데이터 거부와 새 지급 이후 stale backup 거부를 확인한다. 실행 결과와 정확한 파일 해시는 `verification-87ed9e.json`과 `action-files-87ed9e.json`에 기록한다.
