package com.crabit.backend.notification;

import java.util.Objects;
import java.util.UUID;

public record MismatchNotification(
		UUID idempotencyKey,
		UUID cardBalanceAccountId,
		String title,
		String body) {

	public static final String GENERIC_TITLE = "카드 잔액 조정이 필요해요";
	public static final String GENERIC_BODY =
			"위시 배분액과 카드 잔액이 일치하지 않습니다. 위시에서 직접 자금을 해제해 조정해 주세요.";

	public MismatchNotification {
		Objects.requireNonNull(idempotencyKey, "idempotencyKey");
		Objects.requireNonNull(cardBalanceAccountId, "cardBalanceAccountId");
		if (!GENERIC_TITLE.equals(title) || !GENERIC_BODY.equals(body)) {
			throw new IllegalArgumentException("Mismatch notification content must remain generic");
		}
	}

	public static MismatchNotification forAdjustmentCase(
			UUID adjustmentCaseId, UUID accountId) {
		return new MismatchNotification(
				adjustmentCaseId, accountId, GENERIC_TITLE, GENERIC_BODY);
	}
}
