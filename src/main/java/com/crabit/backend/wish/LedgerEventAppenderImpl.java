package com.crabit.backend.wish;

import jakarta.persistence.EntityManager;
import java.util.Objects;

public class LedgerEventAppenderImpl implements LedgerEventAppender {

	private final EntityManager entityManager;

	public LedgerEventAppenderImpl(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	public LedgerEvent append(LedgerEvent event) {
		LedgerEvent immutableEvent = Objects.requireNonNull(event, "event");
		entityManager.persist(immutableEvent);
		return immutableEvent;
	}
}
