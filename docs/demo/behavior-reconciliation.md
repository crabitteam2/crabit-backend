# 행동 export와 실행 명령의 대조

액션 `act-d7842d20ec883401fff6bb162c79a92f`는 `SimulationBehaviorVerifier`를 추가했다. 재생기는 실제 DB의 관계형 export를 파일에 보존하고 기존 참조 검사를 통과한 뒤, 성공한 FEED_QUERY·PROFILE_VISIT·IMPRESSION·CLICK 명령과 저장된 문맥·행동·방문 증거의 정확한 집합을 대조한다. 성공 명령 목록은 결과 일치 확인을 통과한 dispatcher 실행 기록이며 입력의 PASS 보고서를 신뢰하지 않는다. 누락·추가 행, 다른 actor/academy/target, 시각, 항목 순서나 노출 연결이 있으면 성공 정규화를 생성하지 않는다. 실패 시 원본 증거를 보정하지 않는다.

노출은 필수가 아니다. 노출 없이 클릭만 남은 impression도 허용한다. 클릭 뒤 같은 시각에 노출이 기록된 경우에도 명령 순서상 앞선 클릭은 `clicksWithoutPriorExposure`에 포함한다. 각 actor의 논리 impression과 실제 UUID가 일대일이어야 하고, exposed_event_id는 실제 성공한 유일한 노출 event를 정확히 가리켜야 한다. 이후 비공개 전환으로 shared_card 현재 행이 사라져도 feed_source_history의 불변 card→wish→account→author 연결로 과거 작성자를 검증한다.

프로필 방문에는 정확히 하나의 방문 증거가 있어야 한다. 현재 동기 replay 범위의 발생·수신·캡처 시각과 target/academy, COMPLETE 의미, baseline, 정렬된 중복 없는 category 목록 형식, source version의 존재·종류·시각·high-water 범위 및 card/wish/account 소유 관계를 검증한다. 가입부터 끝까지 같은 시각을 쓰는 현재 runtime에 한정되며 legacy/future/비동기 수집을 지원한다고 주장하지 않는다. 실제 classifier 결과 재계산과 당시 모든 접근 권한·후보 집합의 완전성은 이 검사의 범위가 아니다. 기존 source의 같은 시각 변경은 논리 명령 순서와 함께 보존되므로 과거 source 구간의 끝과 사건 시각이 같은 것은 허용한다.

검증은 simulation source set과 기존 replay에 한정된다. 새로운 main 서비스·마이그레이션·OpenAPI/schema 변경은 없다. 외부 provider·Feature Run·action state·원격 저장소·현재 데모를 변경하지 않았다. 결과의 `behaviorReconciliationPerformed=true`는 이 부분 대조만 뜻하며 `fullDatasetValidationPerformed=false`, `readyForApplication=false`를 유지한다.

전체 canonical 정규화/CSV, ADJUST_ALLOCATION/CORRECT·기간/annotation 명령, 전체 월 예산·도메인 대조, 실제 Python feed/recap, 100명 전체 행동 이력의 독립 두 DB 재현성, selective apply/restore, 대표 UI/API/DB 및 Owner 외부 콘솔 보존 검증과 별도 승인된 현재 데모 적용은 남아 있다. 기존 간헐적 feed-history 시각 역행 문제를 수정한 것은 아니다. 정확한 실행 결과와 파일 digest는 `verification-d7842d.json`에 기록한다.
