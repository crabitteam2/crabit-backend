# 백엔드 재생 결과의 통합 비교

`simulationRun`은 실제 DB export와 기존 현금·배분·관측·관계·이력·checkpoint·행동·멱등성 검사가 끝나면 `normalized-backend.json`을 생성한다. `replay-observation.json`의 `backendLogicalProjectionExported`와 `backendLogicalDigest`가 생성 여부와 정확한 canonical UTF-8 bytes의 SHA-256을 기록한다. 실패하면 원본 진단 자료를 남기며 성공한 비교 파일은 생성하지 않는다.

파일은 버전 1의 `simulation-backend-logical-projection`이며 dataset ID, canonical schema digest, 전체 학생 설정, 실제 실행한 논리 명령, typed 논리 신원 목록, 현금 export, catalog digest, 38개 테이블 및 정규화한 실제 응답을 포함한다. 명령과 응답의 개수·순서·event ID·sequence·시각·종류·결과가 일치해야 한다. 모든 구성요소의 dataset ID가 일치해야 하고 누락된 테이블이나 미처리 opaque runtime 값이 있으면 거부한다. 기존 검증기들을 대신하는 독립 import validator는 아니다.

원본 요청의 공백과 bytes는 `raw/`에 그대로 남는다. 비교 파일에는 파싱한 논리 명령을 넣으므로 JSON object key 순서와 공백 차이는 해시에 영향을 주지 않는다. 명령/응답 배열, 피드 순서, 금액, 시각, 일반 문자열, 권한과 인과관계 필드는 유지한다. 객체 키와 이미 정의된 관계형 행 순서만 canonical 규칙으로 정렬한다. 서명 커서는 기존 검증된 응답 normalizer에서 의미로 변환한다. 입력 명령의 literal 커서를 임의로 지우지 않으므로 재현 가능한 다음 페이지에는 기존 `event:<eventId>:nextCursor` 참조를 사용한다.

신원 치환은 기존 typed id-map과 관계형/응답 normalizer를 사용한다. 추가 transport 치환은 `demo_simulation_dataset.manifest_digest` 한 열이다. 원본 값이 실행에 전달한 manifest digest와 먼저 일치해야 한다. 이 digest는 raw UUID/서명 bytes의 영향을 받으므로 비교 projection에서만 `DATASET_MANIFEST:<datasetId>`로 표시한다. 원본 relational export, observation의 manifest digest 및 원본 파일 checksums는 변경하지 않는다. 다른 dataset, 검증되지 않은 manifest 값이나 opaque runtime 값을 같은 결과로 정규화하지 않는다.

실제 100명 가입·현금 400개 명령을 두 독립 DB에서 실행하여 전체 통합 bytes를 비교한다. 별도로 같은 9개 위시·공유·페이지·만료 명령을 두 독립 DB에서 실행하고 원본 UUID·커서와 manifest digest가 다른 조건에서도 통합 digest가 일치하는지 검사한다. 단위 검사는 금액·이름·스키마·추천 순서 변경이 digest에 드러나는지, 누락/불일치 응답·테이블·dataset·opaque 값과 미바인딩 manifest가 거부되는지 확인한다. 이번 정확한 결과는 `verification-2b8033.json`을 따른다.

이 파일은 백엔드 비교 산출물이다. `normalized.json`이나 배포 가능한 전체 canonical bundle로 이름을 바꾸지 않는다. 전체 config/personas, 실제 Python 결과와 모델 버전, 월 예산, 전체 기간과 100명 행동 이력, legacy CSV 및 apply/restore는 후속 작업이다. 최종 canonical artifact 조립은 승인된 계획에서 crabit-data의 책임이다. 모든 산출물의 `fullDatasetValidationPerformed`와 `readyForApplication`은 false다. Feature Run/action 상태·main 서비스·승인 schema/OpenAPI·원격 대상은 이번 액션에서 변경하지 않는다.
