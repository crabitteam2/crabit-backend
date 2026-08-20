# 친구 요청·차단 관리

> 문서 지도는 [backend README](../../README.md)에서 시작한다. 공개 요청·응답의 정확한 형식과
> 오류 inventory는 [OpenAPI 계약](../../api/openapi.yaml)이 기준이며, 이 문서는 관계 규칙과 운영
> 경계를 설명한다.

## 관계 상태와 소유권

친구는 같은 학원에 현재 소속된 두 학생 사이의 상호 관계다. `FriendRequest`는 `PENDING`으로
생성되고 수신자가 수락해야만 `Friendship`이 된다. 처리된 요청은 `ACCEPTED`, `REJECTED`,
`CANCELED` 중 하나로 끝나며 다시 `PENDING`으로 돌아가지 않는다. 친구 해제나 차단 해제도 과거
요청을 되살리지 않는다.

모든 공개 operation의 행위자는 Bearer 인증으로 만든 `CurrentPrincipal.subjectId`다. 학원 범위는
`CurrentPrincipal.academyId`와 path의 `academyId`가 일치하고 현재 membership이 존재할 때만
유효하다. body에는 상대 `studentId`만 들어가며 sender, receiver owner, blocker, account owner를
클라이언트가 지정할 수 없다. 기존 `RelationshipCommandService.befriend`는 Seed와 내부 준비용이고
제품 HTTP API에는 노출하지 않는다.

## 공개 API 흐름

- `GET /v1/academies/{academyId}/students`: 닉네임 부분 일치 검색과 현재 관계 상태
- `GET|DELETE /v1/academies/{academyId}/friends[/{studentId}]`: 현재 친구 조회·해제
- `POST /v1/academies/{academyId}/friend-requests`: 요청 생성
- `GET .../friend-requests/sent|received`: 현재 `PENDING` 보낸·받은 요청
- `DELETE .../friend-requests/{friendRequestId}`: 발신자 요청 취소
- `POST .../{friendRequestId}/acceptance|rejection`: 수신자 수락·거절
- `GET|POST /v1/me/student-blocks`: 내가 만든 현재 차단 조회·생성
- `DELETE /v1/me/student-blocks/{studentId}`: 내가 만든 현재 차단 해제

검색은 NFC 정규화 후 양 끝의 Unicode `Space_Separator`를 제거한다. 1~80 code point만 허용하고
`Cc`, `Cf`, `Zl`, `Zp` 문자는 거부한다. 저장된 닉네임에 대한 case-sensitive contiguous substring
검색이며 자기 자신, 현재 membership이 없는 학생, 어느 방향이든 차단된 학생은 제외한다.

목록은 opaque cursor를 사용한다. cursor는 operation, principal, academy, 검색 filter, ordering
version과 마지막 tuple에 묶인다. 다른 operation·사용자·학원·filter의 cursor는 `400
MALFORMED_REQUEST`다. page 크기는 1~100이고 continuation에서는 다른 유효 limit을 쓸 수 있다.

| 목록 | 고정 정렬 |
|---|---|
| 학생 검색 | `nickname ASC, studentId ASC` |
| 친구 | `friendsSince DESC, studentId DESC` |
| 보낸·받은 요청 | `createdAt DESC, friendRequestId DESC` |
| 내가 차단한 학생 | `blockedAt DESC, studentId DESC` |

## 개인정보와 오류 경계

응답은 관계 관리에 필요한 학생 ID, 닉네임, 관계 상태·시각만 포함한다. 실명, 카드, Wish, 인증,
membership 내부값은 반환하지 않는다. 다른 학원 학생, 현재 소속이 아닌 학생, 차단으로 숨겨진 학생은
동일한 `404 STUDENT_NOT_FOUND`로 정규화한다. 권한 없는 request ID는
`FRIEND_REQUEST_NOT_FOUND`, 현재 친구나 차단의 부재는 각각 `FRIENDSHIP_NOT_FOUND`,
`STUDENT_BLOCK_NOT_FOUND`로 숨긴다.

- `401 AUTH_REQUIRED`: 인증 없음·실패
- `403 FORBIDDEN`: 인증됐지만 학생이 아닌 principal
- `404`: 학원 또는 관계 resource가 현재 principal에게 보이지 않음
- `409`: 자기 관계, 이미 친구, 같은·반대 방향 pending, 처리 완료 요청, 이미 활성인 차단

## 차단 우선순위와 transaction

차단은 학원과 무관한 단방향 관계이고 친구·공개 범위보다 우선한다. 모든 관계 command는 UUID로
정렬한 canonical student pair의 두 `student` row를 같은 순서로 잠근다. 차단 transaction은 다음을
원자적으로 수행한다.

1. 두 학생 사이의 모든 학원 현재 `Friendship`을 종료한다.
2. 두 방향·모든 학원의 `PENDING FriendRequest`를 `CANCELED`로 처리한다.
3. 행위자 방향의 `StudentBlock`을 활성화한다.

수락은 같은 pair lock 안에서 두 학생의 현재 membership, 정확한 pending 요청, 현재 friendship 부재,
양방향 block 부재를 다시 확인하고 요청 처리와 friendship 생성을 한 transaction으로 묶는다. 따라서
중복·역방향 요청, 동시 수락, 수락 대 차단, 친구 해제 대 재요청은 pair 단위로 직렬화된다. DB의
`uk_friend_request_active_academy_pair` partial unique index가 애플리케이션 잠금 외에도 같은 학원
canonical pair의 pending 요청을 하나로 제한한다.

## Migration과 E2E reset

`V6__friend_management.sql`은 `friend_request`와 상태·시각·canonical pair·membership 제약, pending
partial unique index, 조회 index를 추가한다. V1~V5는 수정하지 않는다. PostgreSQL의 partial index와
deferrable membership FK 검증은 H2로 대체하지 않는다.

`SeedFixtureService.resetAndInitialize()`는 Seed 학생이 sender 또는 receiver인 `friend_request`를 먼저
삭제한 뒤 friendship, block, membership과 학생을 기준 상태로 되돌린다. 초기 persona의 friendship과
block은 유지되지만 friend request는 0건이다. `FriendManagementApiIT`는 실제 HTTP로 요청·수락 후
FRIENDS 공유카드가 보이고, 차단 즉시 숨겨지며, 차단 해제로 friendship이 복구되지 않고 새 요청이
필요함을 검증한다.

집중 검증:

```shell
./gradlew test --tests '*FriendManagement*' --tests '*Relationship*' \
  --tests '*SharedWishCardVisibilityIT' --tests '*OpenApi*' \
  --tests '*PostgresMigrationIT' --tests '*DatabaseConstraintIT' \
  --tests '*SeedFixtureIT' --console=plain
```
