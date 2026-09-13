# 실제 리캡 입력 준비

`SimulationRecapPreparation.prepare`는 `SimulationDomainRuntime.executeAt` 안에서 호출하는 로컬 준비 경로다. 현재 재생 DB의 BUILDING 데이터셋, 학생 소유권, 활성 계좌·membership, 기간 종료 전 가입을 확인하고 실제 `RecapSnapshotService`의 입력을 생성한다. 실제 `RecapGenerationCoordinator`를 통해 원본 요청과 input digest를 동결·저장한 뒤 저장소에서 다시 읽는다. main 서버의 빈 구성과 승인된 OpenAPI/schema를 변경하지 않는다.

주간은 월요일부터 다음 월요일, 월간은 월초부터 다음 월초의 KST 완료 기간만 허용한다. 종료일을 임의로 줄이거나 진행 중인 기간을 마감할 수 없다. 입력 신원, 기간, snapshot_at, 종료일 직전 reference_date, 거래의 endExclusive 상한, 기간 내 유효 입금 수를 대조한다. 누적 이력 계산에 필요한 시작일 이전 거래는 유지한다. 이 검사는 모든 peer/방문/위시 상태의 기간 누출 검증을 대체하지 않는다.

월간 유효 입금이 3회 미만이면 기존 NOT_ELIGIBLE 경로가 현재 버전으로 저장된다. 주간 및 적격 월간은 PENDING으로 남는다. view/internal metrics를 채우거나 Python 성공을 만들어내지 않는다. 같은 generation UUID와 대상·기간으로 재호출하면 이미 동결된 원본 request_json을 그대로 반환하며, 다른 대상·기간의 UUID 재사용은 거부한다. 새 UUID에 대한 동일 입력 deduplication은 coordinator가 반환한 실제 generation UUID를 그대로 노출한다.

실제 PostgreSQL 검증은 도메인 서비스로 현금 지급·위시 생성·입금을 실행한다. 마감 1마이크로초 전의 입금은 포함되고, 마감 시각의 입금은 제외된다. 마감 이후 재호출에서 원본 입력 불변성, 새 준비에서 종료 시각 거래 제외, 외부 학생 거부, 월간 입금 2회/3회의 상태·view 부재를 확인한다. 단위 검사는 미완료 기간과 변조된 신원·기간·시각·거래·입금 수를 거부한다.

이 액션은 준비 기반을 구현한다. `CLOSE_WEEK`/`CLOSE_MONTH`의 dispatcher 지원은 아직 활성화하지 않았다. 실제 Python 호출·원본 HTTP 요청/응답 기록·결과 저장·공개 조회, generation 논리 ID 정규화와 전체 replay journal 연결을 완료한 뒤 지원해야 한다. 현재 helper 반환값은 마감 완료나 적용 준비 증거가 아니다. 100명 전체 이력·두 DB·전체 기간 누출·selective apply/restore·4명 UI·Owner 콘솔 보존·현재 demo 적용은 미검증이다.

후속 구현: 동결 입력 이후의 실제 Python 전송·원본 기록·coordinator 결과 저장 helper와 해당 통합 검사가 추가됐다. 최신 범위와 한계는 [recap-execution.md](recap-execution.md)를 따른다. dispatcher 및 전체 재생 결합은 여전히 미완료다.
