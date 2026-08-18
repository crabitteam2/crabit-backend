package com.crabit.backend.notification;

public final class MismatchNotificationDeliveryException extends RuntimeException {

	public MismatchNotificationDeliveryException(String message) {
		super(message);
	}
}
