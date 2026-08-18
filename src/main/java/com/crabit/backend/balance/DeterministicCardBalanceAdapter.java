package com.crabit.backend.balance;

import com.crabit.backend.wish.KrwAmount;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public final class DeterministicCardBalanceAdapter
		implements CardBalanceProvider, CardBalanceScriptControl {

	private final Map<UUID, Queue<CardBalanceProviderResult>> scripts = new HashMap<>();

	@Override
	public synchronized void enqueueSuccess(UUID accountId, KrwAmount balance) {
		enqueue(accountId, new CardBalanceProviderResult.Success(balance));
	}

	@Override
	public synchronized void enqueueFailure(UUID accountId) {
		enqueue(accountId, CardBalanceProviderResult.failure());
	}

	@Override
	public synchronized void replace(
			UUID accountId, List<CardBalanceProviderResult> responses) {
		UUID key = Objects.requireNonNull(accountId, "accountId");
		List<CardBalanceProviderResult> replacement = List.copyOf(
				Objects.requireNonNull(responses, "responses"));
		if (replacement.isEmpty()) {
			throw new IllegalArgumentException("A balance script must contain at least one response");
		}
		scripts.put(key, new ArrayDeque<>(replacement));
	}

	@Override
	public synchronized List<CardBalanceProviderResult> remaining(UUID accountId) {
		Queue<CardBalanceProviderResult> responses = scripts.get(
				Objects.requireNonNull(accountId, "accountId"));
		return responses == null ? List.of() : List.copyOf(responses);
	}

	@Override
	public synchronized void clear(UUID accountId) {
		scripts.remove(Objects.requireNonNull(accountId, "accountId"));
	}

	@Override
	public synchronized void clear() {
		scripts.clear();
	}

	@Override
	public synchronized CardBalanceProviderResult lookup(UUID accountId) {
		UUID key = Objects.requireNonNull(accountId, "accountId");
		Queue<CardBalanceProviderResult> responses = scripts.get(key);
		if (responses == null) {
			return CardBalanceProviderResult.failure();
		}
		CardBalanceProviderResult response = responses.poll();
		if (responses.isEmpty()) {
			scripts.remove(key);
		}
		return response == null ? CardBalanceProviderResult.failure() : response;
	}

	private void enqueue(UUID accountId, CardBalanceProviderResult response) {
		scripts.computeIfAbsent(Objects.requireNonNull(accountId, "accountId"),
				ignored -> new ArrayDeque<>()).add(Objects.requireNonNull(response, "response"));
	}
}
