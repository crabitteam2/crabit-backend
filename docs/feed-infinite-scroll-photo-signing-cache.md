# 피드 사진 서명 URL 재사용

## 동작

`WishPhotoService.view`는 서비스 인스턴스가 소유하는 `WishPhotoSigningCache`로 완전한 세 변형 URL과 실제 만료 시각을 재사용한다. 키는 photo ID와 불변 objectPrefix이며 저장소·서명자가 다른 서비스 사이에 전역 공유하지 않는다. 새 발급의 정수 초 기준 300초 SigningWindow는 유지한다. 반환 직전 실제 만료까지 정확히 30초 이상 남으면 기존 URL과 expiresAt을 그대로 반환하며, 30초 미만은 갱신한다. 서명 완료와 공유 대기 완료도 이 경계를 검사하고 유효한 결과를 확보하지 못하면 기존 503 PHOTO_DELIVERY_UNAVAILABLE로 실패한다. 자동 재서명 반복은 하지 않는다.

캐시는 최대 1024개 성공 결과를 LRU로 보관하며 진행 중 작업은 별도로 128개로 제한한다. 포화 상태에서 새 키는 즉시 503으로 실패하고 이미 진행 중인 동일 키는 합류할 수 있다. 외부 서명은 메타데이터 잠금 밖에서 수행하므로 다른 사진의 서명이 병렬로 진행된다. 부분 변형, 예외, 부족한 잔여 수명은 성공 결과로 저장하지 않는다. 실패한 작업은 정리하여 다음 요청이 재시도할 수 있다.

공유 대기자는 자기 요청의 남은 monotonic deadline만 기다린다. 한 대기자의 timeout이나 interrupt는 producer 작업을 취소하지 않는다. 캐시 적중도 요청 예산 만료를 우회하지 않는다. 일반 요청 8초, 업로드 28초와 Google RPC 시작 전 최소 2초 규칙은 유지한다. 서명 대기나 사진마다 HTTP request deadline을 재설정하지 않는다.

## 권한과 재생

캐시는 서명 결과만 보관한다. 기존 endpoint의 현재 학원 구성원·계정·공개 범위·방향성 팔로우·양방향 차단·삭제 및 ATTACHED 조회는 캐시 접근 전에 계속 실행된다. 사진 교체·삭제·첨부 해제는 이전 캐시 결과를 다시 선택하지 않는다. 이미 발급한 URL은 원래 만료까지 접근 가능할 수 있으며 즉시 폐기나 수명 연장은 제공하지 않는다.

업로드 PENDING/ATTACHED replay와 Wish mutation ACTIVE_PHOTO replay에도 동일 정책을 적용한다. 업로드 replay는 photo row의 owner 일치를 추가로 검증하며 불일치 시 409로 거부하고 다른 소유자의 receipt를 변경하지 않는다. 기존 NO_PHOTO·PHOTO_REVOKED·잠금 순서·원자적 receipt redaction 및 transfer 전체 실패 의미는 유지한다. 유효한 warm hit는 signer outage 중에도 성공하며, 만료 후 실패는 receipt와 mutation 부수 효과를 변경하지 않는다. 사진 런타임 활성화 검사와 Cache-Control no-store는 유지한다.

## 검증과 성능 해석

캐시 단위 테스트는 실제 만료 보존, 정확한 30초 경계와 1ns 초과, 키·서비스 분리, LRU 축출, 불완전 결과·예외·느린 발급 후 복구, 동일 키 공유, 다른 키 병렬 진행, 진행 중 용량 제한, 개별 대기 timeout과 producer 생존, 완료 시 수명·예산 재검증을 확인한다.

PostgreSQL + MockMvc 통합 테스트는 warm cache를 채운 뒤 feed/detail 및 cursor replay, 공개 범위·계정 종료·owner/viewer 학원 탈퇴·언팔로우·양방향 차단·삭제·교체·첨부 해제와 upload owner mismatch를 확인한다. 기존 업로드/변경/cleanup 재생 테스트는 warm outage 성공과 캐시 만료 후 실패를 구분한다. 서명 latch로 잠금 경합을 검사하는 기존 테스트는 먼저 271초를 진행하여 실제 서명을 유도하며 원자성·rollback 검사를 유지한다.

실제 GoogleCloudWishPhotoStorage 코드에 가짜 IAM 서명자와 논리 시간을 주입한 10사진 실험:

| 조건 | 외부 서명 호출 | 주입된 서명 지연 | 결과 |
| --- | ---: | ---: | --- |
| 180ms/signature, cold 10사진 | 30 | 5.4초 | 전체 페이지 성공 |
| 위 결과의 warm 10사진 | 추가 0 | 추가 0초 | 같은 URL/만료로 성공 |
| 300ms/signature, cold 10사진 | 21 | 6.3초 | 다음 RPC의 2초 예산을 확보할 수 없어 부분 페이지 없이 실패 |

이는 가짜 provider의 결정적 지연 실험이며 실제 IAM 네트워크 지연이나 운영 응답 시간 측정이 아니다. 캐시는 반복 조회의 서명 호출을 제거하지만 cold 10사진의 첫 로딩 성능 해결은 보장하지 않는다. 실제 IAM 측정, 배포 후 다중 인스턴스 cache hit율과 first-page 관측은 남아 있다. DB migration, 새 endpoint, 공개 버킷, CDN, 서버 서명 병렬화, TTL 연장, 배포는 포함하지 않는다.

2026-09-18 로컬 검증에서 wishphoto 단위/통합, WishPhoto API/replay, FeedPhotoSigningCacheIT, FeedResultApiPostgresIT, SharedCardOpenApiDocumentationTest, OpenApiContractTest, OpenApiExamplesTest를 함께 실행하여 152개 모두 통과했다(실패·오류·건너뜀 0). 승인된 api/openapi.yaml의 SHA-256은 `f060a17c394678fa512d059be745be3f9d3991582d65168a0e2365cec1305adb`로 구현 전후 동일하다. 전체 backend suite와 controller gate/commit/원격 read-back은 후속 controller 단계에서 별도로 확인한다.
