# 공개 위시 작성자와 계획 날짜: B07 · B17

이 변경은 이슈 #43(B07)을 먼저 구현하고 #44(B17)를 같은 백엔드 변경에 포함한다. 공개 카드 `ownerId`는 학생의 안정적인 UUID이며 `ownerNickname`은 현재 표시 이름이다. 카드 UUID, 비공개 위시 UUID와 구별된다. 동명이인과 닉네임 변경 시에도 이 ID로 작성자를 조회한다. 실명, 위시 ID, 계정 정보와 현재 배분액은 공개하지 않는다.

## 조회와 권한

`GET /v1/academies/{academyId}/students/{studentId}`는 현재 같은 학원에 속하고 양방향 차단이 없는 학생의 `studentId`, `nickname`, `isFollowing`, `isFollowedBy`를 반환한다. 자신의 ID도 조회 가능하며 두 관계 플래그는 false다. 카드 계정이나 공개 카드가 없어도 학생 조회는 가능하다. 없는 학생, 다른 학원, 탈퇴와 차단은 동일한 `STUDENT_NOT_FOUND` 404다. 조회자의 학원 접근 실패는 기존 `ACADEMY_NOT_FOUND`다. 응답은 `Cache-Control: no-store`다.

`GET /v1/academies/{academyId}/shared-cards?ownerId={studentId}`는 SQL LIMIT 전에 작성자를 필터링한다. 필터 생략 시 기존처럼 자신의 카드를 제외하고, 자신을 명시하면 현재 공개 중인 자기 카드만 반환한다. PRIVATE 카드 조회 권한을 추가하지 않는다. 존재하지 않거나 숨겨진 작성자 및 공개 카드가 없는 작성자는 모두 `items: [], nextCursor: null`이다. 이름을 얻기 위해 학생 조회를 조합한다.

모든 페이지에서 현재 양쪽 학원 소속, 열린 계정, 삭제/포기 상태, 공개 범위와 양방향 차단을 평가한다. FOLLOWERS는 조회자→작성자 팔로우로 허용하며 역방향만으로는 허용되지 않는다. 사진 전달 및 서명 실패 정책은 기존 동작을 유지한다.

## 페이지와 호환성

정렬은 `contentUpdatedAt DESC, sharedCardId DESC`, 기본 limit 20 및 범위 1~100이다. HMAC 서명 커서는 버전, operation, 조회자, 학원, 작성자 또는 무필터 표식과 마지막 정렬 경계를 묶는다. 기존 `relationship_cursor_key`의 키를 사용한다. 다른 문맥의 커서, 변조, 구형 무서명 커서와 잘못된 형식은 `MALFORMED_REQUEST` 400이다. 클라이언트는 커서를 지우고 첫 페이지부터 다시 조회한다. 같은 문맥에서 limit 변경은 가능하다. 빈 `ownerId`, `null` 문자열과 잘못된 UUID도 400이다.

안정된 데이터는 중복/누락 없이 순회한다. 페이지 사이 수정과 완료 전환으로 정렬 키가 바뀌는 경우 전체 목록 스냅샷을 보장하지 않는다. 현재 권한은 다음 페이지에도 다시 적용한다. 학생 조회와 목록 조회는 독립 요청이다.

## 날짜와 생명주기

PROGRESS와 COMPLETION 모두 `startDate`, `targetDate`를 필수 nullable 필드로 제공한다. 저장된 `wish.start_date/target_date`를 LocalDate의 `YYYY-MM-DD` 또는 null로 그대로 투영한다. 둘 다 null, 시작만, 목표만, 둘 다 있는 경우를 지원하고 누락 날짜를 생성 시각/오늘/다른 날짜로 채우지 않는다. 타임존 변환이 없다.

기존 위시 수정 API로 날짜를 변경하거나 null로 해제하고 완료할 수 있다. `createdAt`은 실제 생성 시각, `completedAt`은 실제 완료 시각이며 `actualDurationSeconds`는 `max(0, completedAt-createdAt)`의 초다. 계획 날짜 차이로 계산하지 않는다. 새 persistence, migration, 인덱스, 설정, 의존성을 추가하지 않는다. 기존 낙관적 버전 및 명령 멱등성, 트랜잭션 의미를 유지한다. 이 변경의 조회 자체는 데이터를 쓰지 않는다.

## 프론트 F15/F17 통합

canonical 원본은 `crabit-backend`, `feature/public-wish-author`의 `api/openapi.yaml`이다. 승인된 raw-byte digest는 `sha256:de6206dc1bea471c626ba18497f572facd05f1c9ccf38e4bc96904555ec45b57`이다. 변경을 포함한 소스 커밋은 최종 컨트롤러 커밋/PR HEAD에서 확인한다. 구현 시작 기준점은 새 계약의 소스 커밋이 아니다.

승인된 백엔드 커밋을 준비한 뒤 프론트 저장소에서 다음을 실행한다.

```sh
npm run openapi:refresh -- --source <approved-backend>/api/openapi.yaml --repository-sha <approved-backend-sha> --source-path api/openapi.yaml
npm run openapi:generate
npm run openapi:check
```

F15는 카드의 ownerId로 학생 단건과 작성자별 카드 목록을 조합한다. F17은 nullable 계획 날짜를 그대로 표시한다. 신규 응답 필드가 추가되므로 정확한 속성 목록을 검사하는 소비자도 갱신해야 한다. 프론트 변경과 별도 PR, 병합/배포, 알림·추천·리캡 변경은 이 백엔드 작업에 포함하지 않는다.

## 실행 검증

`PublicWishAuthorIT`는 실제 PostgreSQL에서 작성자 필터/페이지, 동명이인/닉네임 변경, 자기 조회, 정상 빈 목록과 숨겨진 학생, 커서 재사용·변조, limit와 빈 ownerId, 권한 변경을 검사한다. `PublicWishAuthorCursorTest`는 유효한 HMAC이 있어도 다른 operation/version/학원/조회자/필터의 커서를 거절하는지 검사한다.

`PublicWishAuthorDemoHttpIT`는 demo profile의 실제 random-port Spring HTTP 서버에 Java HttpClient로 호출하고 PostgreSQL 원본과 비교한다. 두 변형 각각 네 null 조합과 같은 날·과거·연도 경계 날짜, API를 통한 날짜 변경/한쪽 및 양쪽 해제 후 완료, 목록/상세의 날짜, 생성/완료 시각과 실제 소요 초를 검증한다. 테스트 전용 persona 토큰은 실행 중 생성하며 외부 서비스를 호출하지 않는다.

최종 실행 명령과 결과는 컨트롤러 검증 및 PR의 정확한 HEAD 근거와 함께 기록한다.
