# 위시 규범 백엔드 E2E 추적표

이 문서는 Riido 2-42 `위시 기능 기획서를 확정한다`의 섹션 5~18을 실제 백엔드 자동화 테스트에 연결하는 비규범적 구현 근거다. 제품 의미와 범위의 권위는 Riido에 있고, 이 표는 저장소에서 그 의미를 어떻게 검증하는지만 기록한다.

- 위시 기획 원문 digest: `sha256:981df81188f09a7d7388b9b520ccf496d310d58f73cb67bba29b7fb9ec198a38`
- 규범 E2E Task digest: `sha256:59c7bb431b8d918f4a76b2a12f0197f34b1227750fabb54b934647a80a770a08`
- `Covered`: 명시한 테스트가 HTTP 응답, PostgreSQL 상태, 원장 사건 또는 공유 projection으로 규칙을 검증한다.
- `N/A`: 제외 또는 결정 보류이므로 구현 완료로 계산하지 않는다. 사유를 반드시 함께 적는다.

선행 결정에서 확정된 두 가지 해석도 그대로 적용한다.

1. 앱 실행과 수동 새로고침은 공개 Balance Refresh API의 `USER_REQUESTED` 관측으로 합쳐지고, `PRE_DEPOSIT`과 `AUTO_DAILY`는 별도 audit method로 남는다.
2. 카드 잔액을 아직 모르는 상태의 첫 성공 관측은 감소 사건 없이도 불일치 case를 열 수 있다. 이후 case는 음수 원장상 사용 가능 잔액을 만든 성공 관측에 근거한다.

각 행의 테스트 ID는 `테스트클래스#테스트메서드` 형식이며 실제 테스트 메서드로 해석되어야 한다.

## 5. 위시 데이터

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 5.1 목적은 자유 형식 한 줄 문구이며 별도 이름 없이 위시를 식별한다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 정규화된 purpose와 문자 경계를 도메인에서 검증 | - |
| 5.1 목표 금액은 필수다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 생성 HTTP 응답과 저장된 target amount 검증 | - |
| 5.2 목표일은 선택 입력이고 생성일은 시스템이 기록한다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | PostgreSQL의 선택 target date와 created/completed time 검증 | - |
| 5.2 완료일과 실제 소요 기간은 시스템이 계산한다. | Covered | `WishSharingE2EIT#completedVisibilityChangesAtInjectedTimeAndCardNeverAutoExpires` | injected Clock 기반 completedAt과 duration projection 검증 | - |
| 5.2 사진은 보류한다. | N/A | - | 백엔드 완료 범위에 포함하지 않음 | 섹션 18 결정 보류 |
| 5.3 새 위시는 0원으로 생성된다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 생성 응답과 DB wish amount가 0 | - |
| 5.3 목표 금액은 0보다 커야 한다. | Covered | `OpenApiRuntimeCompatibilityIT#lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError` | 도메인 및 DB 경계 거절 | - |
| 5.3 목표 금액이 실제 카드 잔액보다 커도 생성할 수 있다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 잔액 배분 없이 큰 목표를 생성하고 영속화 | - |
| 5.3 새 위시의 기본 공개 범위는 PRIVATE다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 초기 visibility와 amount 검증 | - |
| 5.3 0% 위시도 공개할 수 있다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | 0% 진행 카드 HTTP projection 검증 | - |
| 5.3 위시는 생성 당시 계정과 학원에 영구 귀속된다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | account/academy 식별자 불변 검증 | - |
| 5.4 활성 위시는 목적, 목표 금액, 목표일을 수정할 수 있다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 한 PATCH의 응답과 DB 원자적 갱신 검증 | - |
| 5.4 활성 위시는 공개 범위를 변경할 수 있다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | 같은 카드 identity와 visibility projection 검증 | - |
| 5.4 불일치 중 공개 범위 변경은 잠긴다. | Covered | `WishMismatchE2EIT#blocksCreationDepositTransferAndEveryPatchButReplaysPriorSuccess` | HTTP 409와 제품 오류, 무변경 DB 검증 | - |
| 5.4 목표 금액을 현재 위시 금액 아래로 낮출 수 없다. | Covered | `OpenApiRuntimeCompatibilityIT#lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError` | PATCH 전체 rollback 검증 | - |
| 5.4 낮은 목표를 쓰려면 먼저 출금해야 한다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 출금 후 amount/state 재계산 검증 | - |
| 5.4 목표 금액을 위시 금액과 같게 하면 AMOUNT_REACHED다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | amount/target 조합에 따른 상태 검증 | - |
| 5.4 목표 금액을 늘리면 다시 IN_PROGRESS가 된다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | PATCH 후 상태와 version 검증 | - |
| 5.4 현재 금액에 맞춰 목표를 낮춘 뒤 명시적으로 완료할 수 있다. | Covered | `OpenApiRuntimeCompatibilityIT#allElevenWishOperationsExecuteDeclaredSuccessesAgainstPostgres` | AMOUNT_REACHED에서만 completion 성공 | - |
| 5.4 완료·포기 상태에서는 본문을 수정하지 않고 공개 범위와 삭제만 허용한다. | Covered | `WishNormativeE2EIT#terminalPurposePatchesAreRejectedWithoutSideEffects`, `WishNormativeE2EIT#terminalTargetAmountPatchesAreRejectedWithoutSideEffects`, `WishNormativeE2EIT#terminalTargetDatePatchesAreRejectedWithoutSideEffects`, `WishNormativeE2EIT#terminalVisibilityPatchesApplyOnlyMetadataAndExactCardEffects` | 완료·포기 각각에서 purpose·targetAmount·targetDate를 격리한 PATCH 409와 저장 위시·version·원장·공유 카드 무변경을 검증하고, visibility-only PATCH는 메타데이터 version만 올리며 COMPLETED 카드만 정확히 갱신·제거하고 ABANDONED 카드는 만들지 않음을 검증 | - |

## 6. 위시 상태

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 6.1 IN_PROGRESS는 위시 금액이 목표 금액보다 작은 활성 상태다. | Covered | `OpenApiRuntimeCompatibilityIT#lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError` | 상태-금액 조합 검증 | - |
| 6.1 AMOUNT_REACHED는 위시 금액과 목표 금액이 같은 자동 상태다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 입출금 후 자동 상태 계산 | - |
| 6.1 AMOUNT_REACHED는 출금 또는 목표 증가로 IN_PROGRESS로 돌아간다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | HTTP 응답과 저장 상태 검증 | - |
| 6.1 COMPLETED는 사용자가 AMOUNT_REACHED에서 명시적으로 완료한 최종 상태다. | Covered | `OpenApiRuntimeCompatibilityIT#allElevenWishOperationsExecuteDeclaredSuccessesAgainstPostgres` | 명시적 completion과 최종 상태 검증 | - |
| 6.1 ABANDONED는 두 활성 상태에서 진입 가능한 최종 상태다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | abandonment 상태와 불가역성 검증 | - |
| 6.3 금액 달성과 실제 목적 완료는 서로 다른 사건이다. | Covered | `OpenApiRuntimeCompatibilityIT#lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError` | 금액 미달 completion 거절과 원장 무변경 | - |
| 6.3 목표일 경과는 상태를 자동 변경하지 않는다. | Covered | `WishNormativeE2EIT#passingTargetDateDoesNotAutomaticallyTransitionLifecycleState` | injected Clock을 목표일 이후로 이동한 뒤 HTTP·PostgreSQL 상태/version 무변경 검증 | - |
| 6.3 완료와 포기는 되돌릴 수 없다. | Covered | `WishNormativeE2EIT#terminalStatesRejectReversalWithoutSideEffects` | 완료→포기와 포기→완료 HTTP 409, 위시·원장·카드 무변경 검증 | - |
| 6.3 완료·포기는 남은 전액을 사용 가능 잔액으로 반환한다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | completion/abandonment 반환 원장과 projection 검증 | - |
| 6.4 삭제는 상태가 아니며 모든 lifecycle 상태에서 가능하다. | Covered | `WishNormativeE2EIT#deletingEveryNondeletedLifecycleStateReturnsFundsAndRemovesProjections` | IN_PROGRESS·AMOUNT_REACHED·COMPLETED·ABANDONED 각각의 DELETE 200과 tombstone 영속화 | - |
| 6.4 자금·공유 여부와 무관하게 활성 위시도 삭제 또는 포기를 선택할 수 있다. | Covered | `WishSharingE2EIT#abandonmentAndDeletionRemoveProgressCardsWithoutCreatingTerminalVariants` | 공개 카드 제거와 lifecycle 결과 검증 | - |
| 6.4 삭제는 본문과 공유 카드를 제거한다. | Covered | `WishNormativeE2EIT#deletingEveryNondeletedLifecycleStateReturnsFundsAndRemovesProjections` | 모든 lifecycle 상태에서 상세 404, tombstone 목적 snapshot, shared-card 0건 검증 | - |
| 6.4 삭제는 남은 전액을 한 번 반환한다. | Covered | `WishNormativeE2EIT#deletingEveryNondeletedLifecycleStateReturnsFundsAndRemovesProjections` | 활성 상태의 exact 음수 wish effect와 terminal 0원 상태의 no-event 검증 | - |
| 6.4 삭제 후에도 히스토리와 삭제 직전 목적을 보존한다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | owner history의 purpose snapshot과 no-link 검증 | - |
| 6.4 단순 비공개는 삭제가 아니라 PRIVATE 변경으로 처리한다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | wish 보존과 public projection 제거 검증 | - |
| 6.4 포기는 최종 상태를 보존하고 진행 카드를 제거하며 포기 카드를 만들지 않는다. | Covered | `WishSharingE2EIT#abandonmentAndDeletionRemoveProgressCardsWithoutCreatingTerminalVariants` | 카드 목록/상세에서 포기 카드 부재 검증 | - |

## 7. 잔액과 자금 모델

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 7.1 활성 위시는 IN_PROGRESS와 AMOUNT_REACHED다. | Covered | `OpenApiRuntimeCompatibilityIT#lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError` | lifecycle별 amount 조합 검증 | - |
| 7.1 원장상 사용 가능 잔액은 실제 잔액에서 활성 위시 합계를 뺀 값이다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | signed ledger 계산 검증 | - |
| 7.1 화면 표시 잔액은 원장 잔액을 0 이상으로 제한한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | display available 0 clamp 검증 | - |
| 7.1 부족액은 음수 원장 잔액의 절댓값이다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | HTTP shortage projection 검증 | 첫 성공 관측 case 허용 refinement |
| 7.1 실제·사용 가능 잔액은 사용자가 직접 수정할 수 없다. | Covered | `OpenApiRuntimeCompatibilityIT#canonicalGeneratedAndPostgresBackedRuntimeStayCompatible` | 공개 API에 read-only projection만 존재 | - |
| 7.1 음수 원장 잔액은 히스토리에 보존한다. | Covered | `WishNormativeE2EIT#projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem` | negative ledgerAvailableBalance projection 검증 | - |
| 7.2 입금은 사용자 명시 동작이며 직전에 최신 잔액을 조회한다. | Covered | `OpenApiRuntimeCompatibilityIT#allElevenWishOperationsExecuteDeclaredSuccessesAgainstPostgres` | PRE_DEPOSIT provider 소비와 HTTP command 검증 | - |
| 7.2 입금 전 조회 실패는 입금을 막는다. | Covered | `WishNormativeE2EIT#publicProviderFailurePreservesEveryForbiddenStateAndOnlyRecordsAuditAttempt` | 503, observation 보존, 금액/원장 rollback | - |
| 7.2 최대 입금액은 표시 잔액과 목표 잔여액의 최솟값이다. | Covered | `OpenApiRuntimeCompatibilityIT#fundMovementOperationsExerciseEveryRealizableSpecificCanonicalError` | 두 경계 초과의 제품 오류와 무변경 DB | - |
| 7.2 위시 금액은 목표 금액을 초과할 수 없다. | Covered | `OpenApiRuntimeCompatibilityIT#lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError` | domain/DB 불변 조건 검증 | - |
| 7.2 목표 도달 시 AMOUNT_REACHED가 된다. | Covered | `OpenApiRuntimeCompatibilityIT#allElevenWishOperationsExecuteDeclaredSuccessesAgainstPostgres` | 응답 amount/status와 단일 원장 사건 검증 | - |
| 7.2 외부 잔액 증가는 위시에 자동 배분하지 않는다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | 카드 변동은 card/account history에만 나타남 | - |
| 7.3 위시 금액 범위에서 원하는 금액을 출금할 수 있다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 전액 출금과 replay 검증 | - |
| 7.3 부족액보다 적게 출금해 불일치를 유지할 수 있다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | partial resolution 후 open case 유지 | - |
| 7.3 부족액보다 많이 출금하면 초과분이 사용 가능 잔액이 된다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | excess resolution 후 case close와 DB 원장 검증 | - |
| 7.3 전액 출금은 자동 포기가 아니며 0원 IN_PROGRESS를 유지한다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | amount 0, IN_PROGRESS 응답 검증 | - |
| 7.3 AMOUNT_REACHED에서 일부 출금하면 IN_PROGRESS로 돌아간다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 상태 재계산 검증 | - |
| 7.4 같은 계정의 활성 위시 사이에서 이동할 수 있다. | Covered | `WishNormativeE2EIT#transferRecordsOppositeWishEffectsWithoutChangingAccountBalances` | source/destination 응답과 단일 account event 검증 | - |
| 7.4 출발 잔액과 도착 목표 잔여액 경계를 넘을 수 없다. | Covered | `OpenApiRuntimeCompatibilityIT#fundMovementOperationsExerciseEveryRealizableSpecificCanonicalError` | 두 경계 거절과 부분 반영 없음 | - |
| 7.4 이동 후 두 위시 상태를 다시 계산한다. | Covered | `WishNormativeE2EIT#transferRecordsOppositeWishEffectsWithoutChangingAccountBalances` | source 감소·destination 증가 응답과 두 상태 검증 | - |
| 7.4 이동은 활성 위시 합계와 사용 가능 잔액을 바꾸지 않는다. | Covered | `WishNormativeE2EIT#transferRecordsOppositeWishEffectsWithoutChangingAccountBalances` | PostgreSQL 활성 합계와 실제·ledger·display 잔액 before/after 동일성 검증 | - |
| 7.4 다른 카드·학원 이동은 거절한다. | Covered | `OpenApiRuntimeCompatibilityIT#fundMovementOperationsExerciseEveryRealizableSpecificCanonicalError` | 403 제품 오류와 무변경 DB | - |
| 7.4 불일치 중 이동은 막는다. | Covered | `OpenApiRuntimeCompatibilityIT#fundMovementOperationsExerciseEveryRealizableSpecificCanonicalError` | 409 lock과 provider/ledger 무효과 | - |
| 7.5 완료·포기·삭제는 남은 전액을 반환한다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | 세 terminal reason과 signed effect 검증 | - |
| 7.5 반환 사건은 COMPLETION, ABANDONMENT, DELETION 사유를 구분한다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | account/wish history reason 검증 | - |
| 7.5 반환액이 0이면 0원 원장 사건을 만들지 않는다. | Covered | `WishNormativeE2EIT#deletingEveryNondeletedLifecycleStateReturnsFundsAndRemovesProjections` | 완료·포기 상태 삭제의 null eventId와 반환 사건 수 무증가 검증 | - |

## 8. 카드 잔액 조회와 동기화

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 8.1 하루 한 번 활성 계정을 자동 조회한다. | Covered | `WishNormativeE2EIT#automaticDailyRefreshPersistsAutoDailyObservationAgainstPostgres` | AUTO_DAILY 순회와 실패 격리 검증 | - |
| 8.1 앱 실행과 수동 새로고침은 사용자 요청 refresh로 처리한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | USER_REQUESTED observation과 HTTP projection | 선행 controller refinement |
| 8.1 위시 입금 직전 PRE_DEPOSIT 조회를 수행한다. | Covered | `OpenApiRuntimeCompatibilityIT#allElevenWishOperationsExecuteDeclaredSuccessesAgainstPostgres` | PRE_DEPOSIT proof와 idempotent replay 검증 | - |
| 8.2 성공 조회는 직전 성공 잔액과 비교해 순증가 또는 순감소 한 건을 기록한다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | nonzero card event와 balance observation 연결 | - |
| 8.2 거래 건수를 추정·분할하지 않고 조회별 순변동만 기록한다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | observation 수와 CARD_BALANCE_CHANGE 수를 별도 검증 | - |
| 8.2 조회 시각과 lookup method를 보존한다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | occurredAt/method/change provenance 검증 | - |
| 8.2 0원 변동은 observation만 남기고 원장 사건을 만들지 않는다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | zero-change observation과 no-event 검증 | - |
| 8.2 실패는 observation만 남기고 마지막 성공 잔액과 원장을 바꾸지 않는다. | Covered | `WishNormativeE2EIT#publicProviderFailurePreservesEveryForbiddenStateAndOnlyRecordsAuditAttempt` | persisted failure와 success state 무변경 | - |
| 8.2 실패만으로 조회·출금·완료·포기·삭제를 막지 않는다. | Covered | `WishMismatchE2EIT#allowsRefreshReadsWithdrawalCompletionZeroReturnDeleteAndAbandonment` | 허용 command/query HTTP matrix 검증 | - |
| 8.2 최신 확인에 실패한 입금만 차단하고 불일치 제한은 유지한다. | Covered | `WishNormativeE2EIT#publicProviderFailurePreservesEveryForbiddenStateAndOnlyRecordsAuditAttempt` | stale success proof 거절과 lock 유지 | USER_REQUESTED와 PRE_DEPOSIT 구분 |
| 8.3 최초 성공 잔액은 0원 기준 단일 카드 입금 사건이다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | first observation과 exact delta/time 검증 | - |
| 8.3 최초 사건은 카드·자금 히스토리에서 같은 event ID를 쓴다. | Covered | `WishNormativeE2EIT#externalBalanceChangesShareEventIdentityAcrossCardAndFundHistory` | 최초 증가와 후속 감소의 card/fund history event ID 순서·집합 동일성 검증 | - |

## 9. 잔액 불일치와 해결

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 9.1 활성 위시 합계가 마지막 성공 실제 잔액보다 크면 account mismatch다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | negative ledger와 shortage 계산 | - |
| 9.1 첫 성공 관측도 부족 상태면 case를 열 수 있다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | opening decrease event 없이 case/outbox 생성 | 선행 mismatch refinement |
| 9.1 이후 성공 관측의 감소로도 불일치를 연다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | card decrease, open case, shortage projection | - |
| 9.2 불일치 중 refresh와 위시·히스토리 조회를 허용한다. | Covered | `WishMismatchE2EIT#allowsRefreshReadsWithdrawalCompletionZeroReturnDeleteAndAbandonment` | 성공 HTTP status matrix | - |
| 9.2 일부·전액 출금과 완료·포기·삭제를 허용한다. | Covered | `WishMismatchE2EIT#allowsRefreshReadsWithdrawalCompletionZeroReturnDeleteAndAbandonment` | 허용 command의 상태/원장 결과 검증 | - |
| 9.2 완료는 전액 반환과 상태 변경을 원자적으로 수행한다. | Covered | `WishMismatchE2EIT#completionDuringMismatchResolvesExactExcessAndReplacesCardAtomically` | completion, return event, case close 동시 검증 | - |
| 9.2 완료는 기존 공개 범위를 상속한 완료 카드로 교체된다. | Covered | `WishSharingE2EIT#completedVisibilityChangesAtInjectedTimeAndCardNeverAutoExpires` | card identity/kind/visibility projection 검증 | - |
| 9.2 0원 삭제도 허용하며 해결하지 못하면 제한을 유지한다. | Covered | `WishMismatchE2EIT#allowsRefreshReadsWithdrawalCompletionZeroReturnDeleteAndAbandonment` | zero-return delete 후 open mismatch 검증 | - |
| 9.3 불일치 중 생성·입금·이동·본문 PATCH·공개 변경을 막는다. | Covered | `WishMismatchE2EIT#blocksCreationDepositTransferAndEveryPatchButReplaysPriorSuccess` | 각 HTTP 409, 제품 오류, DB 무변경 | - |
| 9.3 기존 공개 카드는 자동으로 숨기지 않는다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | 기존 progress card 유지와 flag만 변경 | - |
| 9.3 완료 카드 자동 교체는 새로운 공유가 아니고 범위를 유지한다. | Covered | `WishSharingE2EIT#completedVisibilityChangesAtInjectedTimeAndCardNeverAutoExpires` | 같은 sharedCardId의 kind 전환 | - |
| 9.4 사용자가 해제할 위시와 금액을 직접 선택한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | 서로 다른 명시 withdrawal command 검증 | - |
| 9.4 부분 해결은 case와 제한을 유지한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | same case remains open | - |
| 9.4 초과 해결은 case를 닫고 사용 가능 잔액을 남긴다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | over-resolution 후 ledger available과 closedAt 검증 | - |
| 9.4 외부 잔액 증가도 case를 자연 해결한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | refresh event와 case close 검증 | - |
| 9.5 모든 활성 공개 카드에 조정 중 표시를 적용하고 기존 공유를 유지한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | progress projection boolean과 identity 검증 | - |
| 9.5 완료 카드는 조정 중 표시 대상이 아니다. | Covered | `WishSharingE2EIT#progressAndCompletionCardsExposeOnlyTheirClosedPrivacySafeShapes` | progress/completion closed shape 차이 검증 | - |
| 9.5 해결 후 진행 카드 표시를 정상으로 되돌린다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | open/closed case의 boolean projection 검증 | - |
| 9.6 case당 일반 문구 알림을 한 번만 발송한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | outbox와 repeated dispatch 검증 | - |
| 9.6 알림에 정확한 부족 금액을 넣지 않는다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | delivered title/body에 금액 부재 검증 | - |
| 9.6 해결 후 재발하면 새 알림을 한 번 보낸다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | 두 case와 두 delivery 검증 | - |
| 9.7 정상에서 불일치가 될 때 case 하나를 연다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | 동시 refresh에도 case/outbox 하나 | - |
| 9.7 미해결 중 추가 카드 변동과 해결 동작을 같은 case에 연결한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | episode event linkage 검증 | - |
| 9.7 원장 잔액이 0 이상이 되면 case를 닫는다. | Covered | `WishMismatchE2EIT#completionDuringMismatchResolvesExactExcessAndReplacesCardAtomically` | resolution event와 closedAt 검증 | - |
| 9.7 닫힌 뒤 재발할 때만 새 case와 알림을 만든다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | case/outbox count 2 검증 | - |

## 10. 히스토리

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 10.1 실제 증감과 앱 내부 위시 이동을 자동 기록한다. | Covered | `WishNormativeE2EIT#projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem` | 모든 ledger kind projection 검증 | - |
| 10.1 히스토리는 수정·삭제할 수 없는 append-only 기록이다. | Covered | `WishNormativeE2EIT#projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem` | repository mutation API 부재 검증 | - |
| 10.1 정정은 기존 row 수정이 아니라 반대 방향 사건으로 남긴다. | Covered | `WishNormativeE2EIT#projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem` | immutable entity lifecycle guard 검증 | - |
| 10.1 모든 히스토리는 카드 소유자만 조회한다. | Covered | `WishAuthorizationE2EIT#unknownBlockedNonfriendAndOtherAcademyPrincipalsCannotDiscoverOwnerState` | auth/owner/cursor scope HTTP 검증 | - |
| 10.1 금액·시각·출처·앱 내부 사유를 보존한다. | Covered | `WishNormativeE2EIT#projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem` | event time, signed amount, context projection 검증 | - |
| 10.1 결제처·상품·카테고리·외부 사용 이유는 저장하거나 추정하지 않는다. | Covered | `OpenApiRuntimeCompatibilityIT#canonicalGeneratedAndPostgresBackedRuntimeStayCompatible` | 계약의 closed history field inventory 검증 | - |
| 10.2 카드 히스토리는 최초 잔액과 성공 조회 순변동, 시각, 방식, 결과 잔액을 표시한다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | card history HTTP item 전체 필드 검증 | - |
| 10.2 0원 변동은 카드 입출금 항목을 만들지 않는다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | observation은 보존하고 change event는 생략 | - |
| 10.3 최상위 자금 이동 히스토리는 카드 변동, 위시 입출금, 이동, terminal 반환을 시간순으로 합친다. | Covered | `WishNormativeE2EIT#projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem` | 통합 projection 종류와 순서 검증 | - |
| 10.3 각 사건 뒤 signed ledger available을 보존한다. | Covered | `WishNormativeE2EIT#projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem` | 음수 포함 after-ledger value 검증 | - |
| 10.3 카드와 통합 히스토리는 같은 변동에 동일 event ID를 쓴다. | Covered | `WishNormativeE2EIT#externalBalanceChangesShareEventIdentityAcrossCardAndFundHistory` | 두 실제 HTTP projection의 최초 증가·후속 감소 event ID 동일성 | - |
| 10.3 terminal·조정 출금은 중복 사건 없이 한 사건에 사유와 case를 연결한다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | event count, reason, adjustmentCaseId 검증 | - |
| 10.4 위시 히스토리는 해당 위시를 직접 바꾼 입금·출금·조정·이동·terminal 반환만 표시한다. | Covered | `WishNormativeE2EIT#projectsEveryLedgerKindWithSignedAvailabilityAndOneTransferItem` | wish effect 기반 projection 검증 | - |
| 10.4 외부 카드 순변동은 개별 위시에 귀속하지 않는다. | Covered | `WishNormativeE2EIT#externalBalanceChangesShareEventIdentityAcrossCardAndFundHistory` | CARD_BALANCE_CHANGE의 wish effect 0건과 모든 위시 row 무변경 검증 | - |
| 10.5 이동은 account 한 건, source 음수 effect, destination 양수 effect로 기록한다. | Covered | `WishNormativeE2EIT#transferRecordsOppositeWishEffectsWithoutChangingAccountBalances` | event ID 기준 account_delta 0 한 건과 -50000/+50000 두 effect 검증 | - |
| 10.5 이동은 카드 잔액과 사용 가능 잔액을 바꾸지 않는다. | Covered | `WishNormativeE2EIT#transferRecordsOppositeWishEffectsWithoutChangingAccountBalances` | 실제·ledger·display 잔액과 활성 위시 합계 before/after 동일성 검증 | - |
| 10.6 불일치 발견 카드 변동과 사용자 조정은 별도 사건으로 같은 case에 연결한다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | opening/resolution event identity와 case linkage 검증 | - |
| 10.6 완료·포기·삭제 반환이 해결에도 쓰이면 별도 조정 출금을 만들지 않는다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | terminal별 사건 수 1 검증 | - |
| 10.7 삭제 후 자금 기록과 삭제 직전 목적을 보존한다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | tombstone history HTTP 응답 검증 | - |
| 10.7 삭제된 위시 표시를 제공하고 존재하지 않는 상세 링크는 제공하지 않는다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | deletedWish와 null detail link 검증 | - |

## 11. 공유와 소셜 범위

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 11.1 입금마다 게시물을 만들지 않고 하나의 진행 카드가 현재 위시를 반영한다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | 동일 sharedCardId와 갱신 projection 검증 | - |
| 11.1 목적·목표·목표일·금액 변경은 같은 카드를 갱신한다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | contentUpdatedAt과 identity 검증 | - |
| 11.1 AMOUNT_REACHED는 같은 진행 카드 100%이고 출금 시 같은 카드 퍼센트가 내려간다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | identity/kind/progressPercent 검증 | - |
| 11.1 공개 범위 변경은 새 카드나 공유 이력을 만들지 않는다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | 카드 identity 보존과 visibility 재계산 | - |
| 11.1 완료 시에만 진행 카드가 완료 카드로 바뀐다. | Covered | `WishSharingE2EIT#completedVisibilityChangesAtInjectedTimeAndCardNeverAutoExpires` | kind 전환 시점 검증 | - |
| 11.2 공개 범위는 PRIVATE, FRIENDS, ACADEMY뿐이다. | Covered | `OpenApiRuntimeCompatibilityIT#canonicalGeneratedAndPostgresBackedRuntimeStayCompatible` | Visibility enum inventory 검증 | - |
| 11.2 전체 공개와 사용자 지정 그룹은 제공하지 않는다. | N/A | - | 제외 기능을 구현 완료로 계산하지 않음 | 섹션 17 제외 |
| 11.2 ACADEMY 범위는 위시 계정의 현재 학생 구성원만 볼 수 있다. | Covered | `WishSharingE2EIT#newFriendshipAndAcademyMembershipGrantCurrentAccessImmediately` | current membership 기반 access 검증 | - |
| 11.2 교사·관리자와 다른 학원은 대상이 아니다. | Covered | `WishSharingE2EIT#progressAndCompletionCardsExposeOnlyTheirClosedPrivacySafeShapes` | staff/cross-academy 403 또는 filtered 결과 검증 | - |
| 11.2 공개 카드는 닉네임을 쓰고 실명·카드 정보를 노출하지 않는다. | Covered | `WishSharingE2EIT#progressAndCompletionCardsExposeOnlyTheirClosedPrivacySafeShapes` | closed JSON key set 검증 | - |
| 11.3 친구는 같은 학원 학생 간 상호 관계다. | Covered | `WishSharingE2EIT#currentFriendshipMembershipAndReverseBlockApplyImmediately` | academy/current-membership invariant 검증 | - |
| 11.3 친구 해제는 현재·과거 FRIENDS 카드를 즉시 숨긴다. | Covered | `WishSharingE2EIT#currentFriendshipRevocationHidesHistoricalFriendsCompletion` | relation update 후 historical card 404 | - |
| 11.3 새 친구는 현재 FRIENDS 진행·완료 카드를 즉시 볼 수 있다. | Covered | `WishSharingE2EIT#newFriendshipAndAcademyMembershipGrantCurrentAccessImmediately` | current relation 재평가 검증 | - |
| 11.3 공개 대상은 공유 시점 snapshot이 아니라 현재 관계로 계산한다. | Covered | `WishSharingE2EIT#currentFriendshipMembershipAndReverseBlockApplyImmediately` | relation/block 변경 즉시 반영 | - |
| 11.4 새 학원 학생은 현재 ACADEMY 카드를 보고 탈퇴자는 과거 카드도 볼 수 없다. | Covered | `WishSharingE2EIT#ownerAcademyDepartureHidesHistoricalAcademyCompletionFromCurrentMembers` | membership 종료 후 historical access 제거 | - |
| 11.5 어느 한쪽 차단도 친구·학원 범위보다 우선한다. | Covered | `WishSharingE2EIT#currentFriendshipMembershipAndReverseBlockApplyImmediately` | reverse block 포함 양방향 차단 검증 | - |
| 11.5 차단은 기존 친구 관계를 끝내고 해제만으로 복구하지 않는다. | Covered | `WishSharingE2EIT#currentFriendshipMembershipAndReverseBlockApplyImmediately` | 관계 row 종료와 access 무복구 검증 | - |
| 11.5 차단 중 친구 요청을 보낼 수 없다. | Covered | `WishSharingE2EIT#currentFriendshipMembershipAndReverseBlockApplyImmediately` | block/friend 경쟁의 fail-closed 결과 | - |
| 11.5 양쪽 모두 상대의 현재·과거 카드를 볼 수 없다. | Covered | `WishSharingE2EIT#progressAndCompletionCardsExposeOnlyTheirClosedPrivacySafeShapes` | list/detail filtering과 block priority 검증 | - |

## 12. 공유 카드의 종류와 정보

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 12.1 IN_PROGRESS와 AMOUNT_REACHED는 하나의 진행 카드를 쓴다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | 상태 왕복에도 card identity 보존 | - |
| 12.1 진행 카드는 목적, 목표 금액, 진행률을 제공한다. | Covered | `WishSharingE2EIT#progressAndCompletionCardsExposeOnlyTheirClosedPrivacySafeShapes` | progress JSON exact key set | - |
| 12.1 정확한 위시 금액은 노출하지 않고 진행률만 제공한다. | Covered | `WishSharingE2EIT#progressAndCompletionCardsExposeOnlyTheirClosedPrivacySafeShapes` | privacy-safe progress shape 검증 | - |
| 12.1 0원 위시는 0% 진행 카드로 공개할 수 있다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | progressPercent 0 검증 | - |
| 12.1 AMOUNT_REACHED만 100%이고 IN_PROGRESS는 100%로 직렬화하지 않는다. | Covered | `OpenApiRuntimeCompatibilityIT#canonicalGeneratedAndPostgresBackedRuntimeStayCompatible` | closed variant와 percentage invariant 계약 검증 | 반올림 형식 자체는 섹션 18 보류 |
| 12.1 불일치 중 진행 카드에 조정 중 표시를 유지한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | progress boolean과 identity 검증 | - |
| 12.2 완료는 진행 카드를 완료 카드로 교체한다. | Covered | `WishSharingE2EIT#completedVisibilityChangesAtInjectedTimeAndCardNeverAutoExpires` | kind/completedAt/duration projection 검증 | - |
| 12.2 완료 카드는 목적, 목표 금액과 실제 소요 기간을 제공한다. | Covered | `WishSharingE2EIT#progressAndCompletionCardsExposeOnlyTheirClosedPrivacySafeShapes` | completion JSON exact key set | - |
| 12.2 실제 소요 기간은 시스템이 계산하고 사용자가 수정하지 않는다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | created/completed timestamps 영속 검증 | - |
| 12.2 완료 카드는 완료 직전 공개 범위를 상속하고 PRIVATE를 자동 공개하지 않는다. | Covered | `WishSharingE2EIT#completedVisibilityChangesAtInjectedTimeAndCardNeverAutoExpires` | visibility와 list access 검증 | - |
| 12.2 완료 후 공개 범위를 변경할 수 있고 자동 만료하지 않는다. | Covered | `WishSharingE2EIT#currentFriendshipRevocationHidesHistoricalFriendsCompletion` | historical completion이 현재 visibility/관계로 조회됨 | - |
| 12.3 포기는 진행 카드를 제거하고 포기 카드를 만들지 않는다. | Covered | `WishSharingE2EIT#abandonmentAndDeletionRemoveProgressCardsWithoutCreatingTerminalVariants` | former ID 404와 새 카드 부재 | - |
| 12.3 삭제는 진행·완료 카드를 모두 제거한다. | Covered | `WishNormativeE2EIT#deletingEveryNondeletedLifecycleStateReturnsFundsAndRemovesProjections` | 공개 진행·완료 상태를 포함한 모든 lifecycle 삭제 후 shared_card 0건 검증 | - |
| 12.3 공개 중단은 PRIVATE 변경으로 처리할 수 있다. | Covered | `WishSharingE2EIT#oneProgressCardTracksZeroReachedWithdrawnAndEditedPublicStates` | wish 보존과 public visibility 제거 | - |

## 13. 알림

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 13.1 확정 알림은 새 잔액 불일치 최초 발견 알림이다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | case outbox kind와 delivery 검증 | - |
| 13.1 한 불일치 건당 한 번만 발송한다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | repeated dispatch가 0건 | - |
| 13.1 정확한 부족 금액은 알림에 넣지 않는다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | generic title/body와 amount 부재 | - |
| 13.1 같은 case에는 반복 알림이 없고 해결 후 재발하면 새 알림이 있다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | case/delivery count 2 | - |
| 13.1 목표일·달성·완료·친구 활동 알림은 추후 결정한다. | N/A | - | 결정 전 기능을 구현 완료로 계산하지 않음 | 섹션 18 보류 |

## 14. 제품 검증 가설

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 14.1 핵심 백엔드 재현 행동은 위시 생성 후 다른 날 같은 위시에 두 번째 입금하는 것이다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | Clock을 하루 이동하고 두 deposit event의 occurredAt 구분 | - |
| 14.1 첫 생성이나 같은 날 반복만으로 재방문을 판단하지 않는다. | Covered | `WishNormativeE2EIT#firstBalanceObservationAndNextDaySecondDepositRemainDistinctFacts` | 두 번째 사건이 정확히 다음 날임을 검증 | - |
| 14.1 공유가 재방문의 원인인지와 성공 임계값은 아직 확정하지 않는다. | N/A | - | 제품 분석·인과 가설이며 백엔드 conformance가 아님 | 섹션 18 관찰 기간·임계값 보류 |
| 14.1 소비 성향 분석과 통계는 검증 범위에 포함하지 않는다. | N/A | - | 제외 기능을 구현 완료로 계산하지 않음 | 섹션 17 제외 |

## 15. 대표 시나리오

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 15.1 정상 입금은 위시 금액과 활성 합계를 늘리고 사용 가능 잔액을 같은 금액만큼 줄이며 실제 잔액은 바꾸지 않는다. | Covered | `OpenApiRuntimeCompatibilityIT#allElevenWishOperationsExecuteDeclaredSuccessesAgainstPostgres` | HTTP wish/account snapshot과 ledger effect 검증 | - |
| 15.2 사용 가능 잔액 안의 외부 카드 감소는 위시를 바꾸지 않고 카드 출금과 사용 가능 잔액만 갱신한다. | Covered | `WishNormativeE2EIT#externalBalanceChangesShareEventIdentityAcrossCardAndFundHistory` | -100000 card/account available 변동, wish effect 0건, 위시 row 무변경 검증 | - |
| 15.3 위시 합계를 침범한 카드 감소는 signed ledger, display 0, shortage와 mismatch를 만든다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | 실제/원장/display/shortage HTTP shape 검증 | 첫 성공 관측 refinement 적용 |
| 15.3 사용자가 정확 또는 초과 출금으로 해결할 수 있다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | partial/over resolution과 case lifecycle | - |
| 15.4 외부 사용 후 사용자가 완료하면 전액 반환, mismatch 재계산, 완료 카드 교체를 원자적으로 수행한다. | Covered | `WishMismatchE2EIT#completionDuringMismatchResolvesExactExcessAndReplacesCardAtomically` | completion/case/ledger 상태 검증 | - |
| 15.4 실제 결제 성공을 추정하지 않고 완료는 사용자 명시 동작이다. | Covered | `OpenApiRuntimeCompatibilityIT#lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError` | 명시 completion과 금액 선조건 검증 | - |
| 15.5 목표보다 적은 금액으로 목적을 이룬 경우 목표를 현재 금액으로 맞춘 뒤 완료한다. | Covered | `OpenApiRuntimeCompatibilityIT#allElevenWishOperationsExecuteDeclaredSuccessesAgainstPostgres` | AMOUNT_REACHED 선행과 explicit completion | - |
| 15.6 이동은 source 감소, destination 증가, 두 상태 재계산, 실제·사용 가능 잔액 무변경이다. | Covered | `WishNormativeE2EIT#transferRecordsOppositeWishEffectsWithoutChangingAccountBalances` | 두 effect·응답 상태와 account HTTP/PostgreSQL invariant 검증 | - |

## 16. 도메인 불변 조건

| 규칙 | 처리 | 테스트 ID | 관찰 근거 | 해석 근거 |
|---|---|---|---|---|
| 16.1 목표 금액은 양수이고 위시 금액은 0 이상 목표 이하이다. | Covered | `OpenApiRuntimeCompatibilityIT#lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError` | 모든 경계 조합 거절 | - |
| 16.1 원장상 사용 가능 잔액은 실제 잔액에서 활성 위시 합계를 뺀 값이다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | signed formula 검증 | - |
| 16.1 IN_PROGRESS와 AMOUNT_REACHED는 amount/target 관계로 결정된다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | allocation/withdrawal 후 상태 검증 | - |
| 16.1 완료는 AMOUNT_REACHED에서만 가능하다. | Covered | `OpenApiRuntimeCompatibilityIT#lifecycleOperationsExerciseEveryRealizableSpecificCanonicalError` | 409과 원장 무변경 | - |
| 16.1 완료·포기 후 위시 금액은 0이다. | Covered | `WishNormativeE2EIT#uninterruptedLifecycleKeepsLedgerAccountWishAndSharedCardConsistent` | 반환액과 terminal amount 검증 | - |
| 16.1 완료·포기는 되돌릴 수 없다. | Covered | `WishNormativeE2EIT#terminalStatesRejectReversalWithoutSideEffects` | 양방향 terminal 재전이 409와 모든 영속 projection 무변경 검증 | - |
| 16.1 삭제는 상태가 아니며 모든 상태에서 가능하다. | Covered | `WishNormativeE2EIT#deletingEveryNondeletedLifecycleStateReturnsFundsAndRemovesProjections` | 네 lifecycle 상태의 DELETE 200과 기존 state 보존 tombstone 검증 | - |
| 16.1 삭제는 히스토리를 삭제하지 않는다. | Covered | `WishNormativeE2EIT#projectsAllTerminalReturnsWithReasonsAndAdjustmentLinkage` | 삭제 후 owner history 조회 | - |
| 16.1 외부 카드 변동을 임의 위시에 자동 귀속하지 않는다. | Covered | `WishNormativeE2EIT#externalBalanceChangesShareEventIdentityAcrossCardAndFundHistory` | external card events의 wish effect 0건과 위시 snapshot 무변경 검증 | - |
| 16.1 음수 원장 잔액을 숨기지 않고 account mismatch로 다룬다. | Covered | `WishMismatchE2EIT#partialAndExcessResolutionNotifyOnceThenRecurrenceNotifiesOnceAgain` | signed ledger와 clamped display 동시 검증 | - |
| 16.1 불일치 중에는 조회·자금 해제·terminal 동작 외 변경을 막는다. | Covered | `WishMismatchE2EIT#blocksCreationDepositTransferAndEveryPatchButReplaysPriorSuccess` | blocked HTTP matrix와 무변경 DB | - |
| 16.1 모든 money command와 새 잔액 반영은 계정 최신값을 검증한 원자적 변경이다. | Covered | `WishNormativeE2EIT#sameSeedAndClockProduceSameNormalizedEndToEndSnapshot` | wish, ledger, projection 동시 rollback | - |

## 17. 현재 범위에서 제외한 기능

아래 항목은 모두 `N/A`이며 백엔드 규범 E2E 완료로 계산하지 않는다.

| 제외 항목 | 처리 | 사유 |
|---|---|---|
| 소비처, 구매 상품, 소비 이유, 카테고리 기록 | N/A | 외부 거래 의미를 추정하지 않는 범위 |
| 실제 카드 결제 승인과 개별 거래 연동 | N/A | 잔액 snapshot만 사용하는 범위 |
| 저축·구매 위시 유형 | N/A | purpose만 자유 입력하는 단일 위시 모델 |
| 포인트·캐릭터 등 보상 체계 | N/A | 별도 제품 범위 |
| 자동 일일 저축액과 자동 위시 입금 | N/A | 사용자 명시 배분 원칙 |
| 다른 사용자의 위시 복사·가져오기 | N/A | 현재 범위 밖 |
| 전체 공개와 사용자 지정 그룹 | N/A | PRIVATE, FRIENDS, ACADEMY만 확정 |
| 다른 카드·학원 간 자금 이동 | N/A | 명시적 금지 규칙이며 기능으로 제공하지 않음 |
| 좋아요·응원·댓글·메시지 | N/A | 공유 카드 상호작용 제외 |
| 신고와 운영자 검토 | N/A | 운영 기능 제외 |
| 사용자 대상 소비 분석과 통계 | N/A | 초기 검증 범위 밖 |

## 18. 결정 보류 항목

아래 항목은 모두 `N/A`이며 결정 전 구현이나 테스트 완료로 계산하지 않는다.

| 보류 항목 | 처리 | 사유 |
|---|---|---|
| 화면 구조, 탭, 정렬, 피드 순서, 카드 시각 디자인 | N/A | UI 결정 보류. 진행/완료 카드 구분 원칙만 계약에 반영 |
| 진행·완료 사진 | N/A | 사진 기능 자체가 보류 |
| 잔액 불일치 외 알림 종류와 발송 규칙 | N/A | 알림 제품 결정 보류 |
| 위시 최대 생성 개수 | N/A | 한도 결정 보류 |
| 퍼센트의 구체적 반올림·표시 형식 | N/A | IN_PROGRESS 비100% 불변 조건만 확정 |
| 다른 날 두 번째 입금의 관찰 기간과 성공 임계값 | N/A | 제품 실험 설계 결정 보류 |
