package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.Test;

class LedgerAppendOnlyTest {

	@Test
	void exposesAppendAndReadOperationsWithoutInheritedMutationOrDeletionMethods() {
		Set<String> publicOperations = Arrays.stream(LedgerEventRepository.class.getMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.map(Method::getName)
				.filter(name -> !Set.of("equals", "hashCode", "toString").contains(name))
				.collect(Collectors.toSet());

		assertThat(publicOperations)
				.contains("append", "findById", "findAll", "existsByDepositBalanceObservationId")
				.doesNotContain(
						"save", "saveAll", "saveAndFlush", "flush",
						"delete", "deleteById", "deleteAll", "deleteAllInBatch");
	}

	@Test
	void keepsBothLedgerEntitiesMarkedImmutableWithLifecycleMutationGuards() {
		assertThat(LedgerEvent.class).hasAnnotation(Immutable.class);
		assertThat(LedgerWishEffect.class).hasAnnotation(Immutable.class);
		assertThat(Arrays.stream(LedgerEvent.class.getDeclaredMethods())
				.map(Method::getName)).contains("rejectMutation");
		assertThat(Arrays.stream(LedgerWishEffect.class.getDeclaredMethods())
				.map(Method::getName)).contains("rejectMutation");
	}
}
