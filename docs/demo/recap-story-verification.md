# 리캡 성공 사례 후보의 원본 대조

액션 `act-a7b773ec0f468ae27ce16a34aa8ae4b6`은 주간·월간 종료 시점에 실제 DB의 공유 카드·팔로우·차단 행을 `period-source.json`의 `story_source`로 보존한다. 기존 `peer_source`의 학생 계좌·위시·회원 행과 함께 독립 검증기 `SimulationRecapStoryVerifier`가 후보를 재계산한다. production 후보 SQL이나 snapshot helper를 기대값 계산에 사용하지 않는다.

같은 학원의 본인 이외 활성 계좌·회원, 삭제되지 않은 완료 위시, ACADEMY 공개 또는 조회자에서 작성자로 이어지는 활성 FOLLOWERS 관계가 대상이다. 어느 방향이든 활성 차단이 있으면 제외한다. 완료 시각과 공유 카드 갱신 시각이 모두 한국 시간의 [기간 시작, 기간 종료) 안에 있어야 한다. 완료 시각 다음 PostgreSQL UUID 순으로 정렬한 첫 5개의 위시 ID와 ACADEMY_SUCCESS 유형을 요청 배열과 정확히 비교한다. 후보가 6개 이상일 때의 제한, 누락·추가·중복·순서 변조도 검사한다. 실제 완료 공유 카드의 COMPLETION 종류도 확인한다.

종료 시각에 캡처한 원본과 요청을 먼저 저장하고, 본인 기간·또래·성공 사례 검사가 모두 통과해야 `period-verification.json`을 기록하고 Python 호출로 진행한다. 검증 결과의 `stories`는 `eligibleBeforeLimit`와 `selected`를 제공한다. 실패 전에 만들어진 원본 증거를 삭제하거나 실패를 무효과로 보고하지 않는다. 기존 원본 journal 해시·길이 검증은 그대로 적용된다.

7개 단위 테스트는 팔로우 방향·종료 상태, 양방향 차단과 해제, 비공개·본인·삭제·탈퇴·닫힌 계좌·다른 학원·미완료 제외, 양쪽 기간 경계, unsigned PostgreSQL UUID 정렬과 정확한 상위 5개 선택을 검사한다. 추가 실제 PostgreSQL/Python 테스트는 기존 서비스로 작성자의 1,000원 위시를 입금·완료·공유하고 조회자의 주간 리캡에서 후보 1개와 HTTP 200을 확인한다. 저장 요청에서 후보를 제거하면 독립 검증이 실패하며 원본 바이트는 유지된다. 원본 증거는 `build/simulation-recap-stories/`, 전체 검사 결과는 `verification-a7b773.json`을 따른다.

이 검사는 입력 후보의 완전성과 권한·기간 조건을 다룬다. 후속 액션 `act-4be61e71fb1130b57286f0abcf6bf4f1`에서 `author_previous_month` 지표의 독립 재계산을 추가했다. 현재 검증은 [작성자 지표 대조](recap-author-verification.md)를 함께 따른다. Python의 최종 story 선택과 조회 시 재권한 확인·사진 서명은 아직 남아 있다. 익명 또래 배열 및 같은 시각 UUID 정렬의 두 DB 정규화, 전체 100명 혼합 이력 재현성, 실제 추천 Python, 최종 canonical bundle/CSV, selective apply/restore, 대표 UI, 최신 Owner 콘솔 보존과 현재 demo 적용은 남아 있다. ADJUST/CORRECT는 현재 승인된 사건 union에서 실행 명령으로 지원하지 않는다.

이번 액션의 변경은 simulation 소스·테스트와 문서에 한정한다. 기존 미커밋 구현을 이어 받았으며 main 코드, 승인된 OpenAPI와 dataset schema bytes, Feature Run·액션 상태, commit·원격 상태는 변경하지 않았다. JAR 검사는 구조적 제외 증거이며 제품 E2E 증거가 아니다.
