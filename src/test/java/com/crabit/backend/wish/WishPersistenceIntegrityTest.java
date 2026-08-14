package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class WishPersistenceIntegrityTest {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Autowired
	private EntityManager entityManager;

	@Test
	void persistsAValidGraphAndKeepsATombstonedWishOutOfActiveQueries() {
		Fixture fixture = persistFixture();
		Wish wish = Wish.create(fixture.account().id(), fixture.academy().id(),
				"여름 캠프", KrwAmount.positive(200), NOW);
		Wish destination = Wish.create(fixture.account().id(), fixture.academy().id(),
				"노트북", KrwAmount.positive(300), NOW);
		wish.allocate(KrwAmount.positive(80));
		entityManager.persist(wish);
		entityManager.persist(destination);
		entityManager.persist(LedgerEvent.transfer(
				fixture.account().id(),
				wish.id(), wish.purpose(),
				destination.id(), destination.purpose(),
				KrwAmount.positive(30), NOW));
		entityManager.flush();

		wish.tombstone(NOW.plusSeconds(60));
		entityManager.flush();
		entityManager.clear();

		Wish retained = entityManager.find(Wish.class, wish.id());
		long activeCount = entityManager.createQuery(
				"select count(w) from Wish w where w.accountId = :accountId and w.deletedAt is null and w.state in :activeStates",
				Long.class)
				.setParameter("accountId", fixture.account().id())
				.setParameter("activeStates", List.of(WishState.IN_PROGRESS, WishState.AMOUNT_REACHED))
				.getSingleResult();
		long retainedEffectCount = entityManager.createQuery(
				"select count(e) from LedgerWishEffect e where e.wishId = :wishId", Long.class)
				.setParameter("wishId", wish.id())
				.getSingleResult();

		assertThat(retained).isNotNull();
		assertThat(retained.isDeleted()).isTrue();
		assertThat(retained.displayPurpose()).isEqualTo("삭제된 위시");
		assertThat(activeCount).isOne();
		assertThat(retainedEffectCount).isOne();
	}

	@Test
	void rejectsAWishWhoseAcademyDoesNotMatchItsAccount() {
		Fixture fixture = persistFixture();
		Academy otherAcademy = new Academy(UUID.randomUUID(), "다른 학원");
		entityManager.persist(otherAcademy);
		Wish mismatched = Wish.create(fixture.account().id(), otherAcademy.id(),
				"노트북", KrwAmount.positive(100), NOW);

		entityManager.persist(mismatched);

		assertThatThrownBy(entityManager::flush)
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_wish_account_academy"));
	}

	@Test
	void rejectsAnOrphanMembershipReference() {
		Fixture fixture = persistFixture();
		AcademyMembership orphan = new AcademyMembership(
				UUID.randomUUID(), fixture.academy().id(), NOW);
		entityManager.persist(orphan);

		assertThatThrownBy(entityManager::flush)
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_membership_student"));
	}

	@Test
	void rejectsADuplicateStudentAcademyMembership() {
		Fixture fixture = persistFixture();
		entityManager.persist(new AcademyMembership(
				fixture.student().id(), fixture.academy().id(), NOW));
		entityManager.persist(new AcademyMembership(
				fixture.student().id(), fixture.academy().id(), NOW.plusSeconds(1)));

		assertThatThrownBy(entityManager::flush)
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("uk_membership_student_academy"));
	}

	@Test
	void rejectsStateAndAmountCombinationsThatBypassTheDomainConstructor() {
		Fixture fixture = persistFixture();

		assertThatThrownBy(() -> entityManager.createNativeQuery("""
				insert into wish (
				  id, account_id, academy_id, purpose, target_amount, wish_amount,
				  state, visibility, created_at, deleted_at, deleted_purpose_snapshot, version
				) values (
				  :id, :accountId, :academyId, 'invalid', 100, 100,
				  'IN_PROGRESS', 'PRIVATE', :createdAt, null, null, 0
				)
				""")
				.setParameter("id", UUID.randomUUID())
				.setParameter("accountId", fixture.account().id())
				.setParameter("academyId", fixture.academy().id())
				.setParameter("createdAt", NOW)
				.executeUpdate())
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("ck_wish_state_amount"));
	}

	@Test
	void ignoresUpdatesAndRejectsDeletionOfAnAppendOnlyLedgerEvent() throws ReflectiveOperationException {
		Fixture fixture = persistFixture();
		Wish source = Wish.create(fixture.account().id(), fixture.academy().id(),
				"노트북", KrwAmount.positive(100), NOW);
		Wish destination = Wish.create(fixture.account().id(), fixture.academy().id(),
				"여행", KrwAmount.positive(100), NOW);
		entityManager.persist(source);
		entityManager.persist(destination);
		LedgerEvent transfer = LedgerEvent.transfer(
				fixture.account().id(),
				source.id(), source.purpose(),
				destination.id(), destination.purpose(),
				KrwAmount.positive(30), NOW);
		entityManager.persist(transfer);
		entityManager.flush();

		Field accountDelta = LedgerEvent.class.getDeclaredField("accountDelta");
		accountDelta.setAccessible(true);
		accountDelta.set(transfer, KrwAmount.positive(999));
		entityManager.flush();
		entityManager.clear();
		LedgerEvent retained = entityManager.find(LedgerEvent.class, transfer.id());

		assertThat(retained.accountDelta()).isEqualTo(KrwAmount.zero());

		assertThatThrownBy(() -> {
			entityManager.remove(retained);
			entityManager.flush();
		}).isInstanceOfAny(UnsupportedOperationException.class, PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("append-only"));
	}

	private static String causeMessages(Throwable error) {
		StringBuilder messages = new StringBuilder();
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				messages.append(current.getMessage()).append('\n');
			}
		}
		return messages.toString();
	}

	private Fixture persistFixture() {
		Academy academy = new Academy(UUID.randomUUID(), "크래빗 학원");
		Student student = new Student(UUID.randomUUID(), "토끼");
		CardBalanceAccount account = CardBalanceAccount.open(student.id(), academy.id(), NOW);
		entityManager.persist(academy);
		entityManager.persist(student);
		entityManager.persist(account);
		entityManager.flush();
		return new Fixture(academy, student, account);
	}

	private record Fixture(Academy academy, Student student, CardBalanceAccount account) {
	}
}
