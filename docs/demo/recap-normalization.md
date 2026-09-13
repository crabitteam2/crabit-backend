# 리캡 재현성 비교 정규화

`SimulationRecapNormalization`은 simulation source set에서만 실행된다. 리캡의 원본 요청, 실제 Python 응답, PostgreSQL 저장 행은 변경하지 않는다. 비교 projection에서는 타입별 논리 ID로 generation/student/account/academy/wish/ledger root를 연결한다. 사용자 문구에 UUID가 포함되어 있어도 변경하지 않으며, 배열 순서·금액·기간·시각·모델 결과·null을 보존한다.

원본 요청에서 generation_id와 input_digest를 제외한 자료를 제품의 RFC 8785 encoder로 계산하여 저장된 input_digest와 대조한다. 요청의 identity/period/version, 저장 행의 frozen request, 실제 HTTP response의 header/view/internal_metrics가 일치해야 한다. 검증된 원본 digest만 `logical:sha256:...` 비교 digest로 변환한다. 논리 digest에도 금액·시각·순서·모델 입력 전체가 포함된다. ID 누락, 원본 입력 손상, 잘못된 소유자나 기간, 응답과 저장 상태의 불일치는 실패한다.

저장 행의 request_json/view_json/internal_metrics_json 문자열은 비교 projection에서 JSON 구조로 해석한다. 원래 저장 행은 그대로 보존한다. SUCCEEDED는 실제 응답이 필요하며 NOT_ELIGIBLE은 응답이 없어야 한다. 생성되지 않은 HTTP 응답을 만들어 비교에 넣으면 거부한다. FAILED/PENDING/RUNNING은 성공 정규화로 취급하지 않는다.

이 정규화는 `SimulationRelationalNormalizer`의 recap_generation 행과 dispatcher의 CLOSE_WEEK/CLOSE_MONTH 응답에 연결되어 있다. `exchange`는 실제 request/response/stored-state 파일을 함께 검증한다. 두 독립 PostgreSQL DB와 실제 로컬 Python recap service에서 같은 주간 이벤트를 재생하여 원본 request bytes는 다르고 정규화 결과는 같은 것을 검사한다. 월간 NOT_ELIGIBLE의 null response도 검사한다.

명시적인 로컬 서비스 설정이 있는 runner의 CLOSE 명령과 원본 snapshot/request/HTTP/stored-state raw index 연결은 `recap-replay-export.md`를 따른다. 전체 100명·모든 기간·추천 Python까지 포함한 두 번의 재생, 독립 기간 및 후보 검증과 최종 canonical export는 남아 있다. 이 비교 projection은 importer, application manifest, 운영 적용 또는 게이트 승인 근거가 아니다.
