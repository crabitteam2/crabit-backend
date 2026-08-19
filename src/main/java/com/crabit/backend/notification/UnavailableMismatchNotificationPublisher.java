package com.crabit.backend.notification;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e")
final class UnavailableMismatchNotificationPublisher
		implements MismatchNotificationPublisher {

	@Override
	public void publish(MismatchNotification notification) {
		throw new MismatchNotificationDeliveryException(
				"No external mismatch notification provider is configured");
	}
}
