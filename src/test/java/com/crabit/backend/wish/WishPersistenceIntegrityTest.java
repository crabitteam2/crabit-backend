package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
	void persistsDeletionAcrossLifecycleStatesAndKeepsLedgerReferencesAndActiveQueriesIntact() {
		Fixture fixture = persistFixture();
		Wish inProgress = Wish.create(fixture.account().id(), fixture.academy().id(),
				"여름 캠프", KrwAmount.positive(200), NOW);
		Wish destination = Wish.create(fixture.account().id(), fixture.academy().id(),
				"노트북", KrwAmount.positive(300), NOW);
		Wish completed = Wish.create(fixture.account().id(), fixture.academy().id(),
				"자전거", KrwAmount.positive(100), NOW);
		Wish abandoned = Wish.create(fixture.account().id(), fixture.academy().id(),
				"여행", KrwAmount.positive(150), NOW);
		inProgress.allocate(KrwAmount.positive(80));
		completed.allocate(KrwAmount.positive(100));
		completed.complete(NOW.plusSeconds(30));
		abandoned.allocate(KrwAmount.positive(40));
		abandoned.abandon();
		entityManager.persist(inProgress);
		entityManager.persist(destination);
		entityManager.persist(completed);
		entityManager.persist(abandoned);
		entityManager.persist(LedgerEvent.transfer(
				fixture.account(),
				inProgress,
				destination,
				KrwAmount.positive(30), NOW));
		entityManager.flush();

		inProgress.tombstone(NOW.plusSeconds(60));
		completed.tombstone(NOW.plusSeconds(60));
		abandoned.tombstone(NOW.plusSeconds(60));
		entityManager.flush();
		entityManager.clear();

		Wish retainedInProgress = entityManager.find(Wish.class, inProgress.id());
		Wish retainedCompleted = entityManager.find(Wish.class, completed.id());
		Wish retainedAbandoned = entityManager.find(Wish.class, abandoned.id());
		long activeCount = entityManager.createQuery(
				"select count(w) from Wish w where w.accountId = :accountId and w.deletedAt is null and w.state in :activeStates",
				Long.class)
				.setParameter("accountId", fixture.account().id())
				.setParameter("activeStates", List.of(WishState.IN_PROGRESS, WishState.AMOUNT_REACHED))
				.getSingleResult();
		long retainedEffectCount = entityManager.createQuery(
				"select count(e) from LedgerWishEffect e where e.wishId = :wishId", Long.class)
				.setParameter("wishId", inProgress.id())
				.getSingleResult();

		assertThat(retainedInProgress.state()).isEqualTo(WishState.IN_PROGRESS);
		assertThat(retainedInProgress.amount()).isEqualTo(KrwAmount.zero());
		assertThat(retainedInProgress.isDeleted()).isTrue();
		assertThat(retainedInProgress.purposeSnapshot()).isEqualTo("여름 캠프");
		assertThat(retainedInProgress.displayPurpose()).isEqualTo("삭제된 위시");
		assertThat(retainedCompleted.state()).isEqualTo(WishState.COMPLETED);
		assertThat(retainedCompleted.isDeleted()).isTrue();
		assertThat(retainedAbandoned.state()).isEqualTo(WishState.ABANDONED);
		assertThat(retainedAbandoned.isDeleted()).isTrue();
		assertThat(activeCount).isOne();
		assertThat(retainedEffectCount).isOne();
	}

	@Test
	void persistsOptionalTargetDateAndSystemRecordedCompletionTimeForDuration() {
		Fixture fixture = persistFixture();
		LocalDate targetDate = LocalDate.of(2026, 12, 31);
		Instant completedAt = NOW.plus(Duration.ofDays(4));
		Wish wish = Wish.create(
				fixture.account().id(), fixture.academy().id(), "노트북",
				KrwAmount.positive(100), targetDate, NOW);
		wish.allocate(KrwAmount.positive(100));
		wish.complete(completedAt);
		entityManager.persist(wish);
		entityManager.flush();
		entityManager.clear();

		Wish retained = entityManager.find(Wish.class, wish.id());

		assertThat(retained.targetDate()).isEqualTo(targetDate);
		assertThat(retained.completedAt()).isEqualTo(completedAt);
		assertThat(retained.actualDuration()).contains(Duration.ofDays(4));
	}

	@Test
	void rejectsACompletedWishWithoutACompletionTimestamp() {
		Fixture fixture = persistFixture();

		assertThatThrownBy(() -> entityManager.createNativeQuery("""
				insert into wish (
				  id, account_id, academy_id, purpose, target_amount, wish_amount,
				  state, visibility, target_date, created_at, completed_at,
				  deleted_at, deleted_purpose_snapshot, version
				) values (
				  :id, :accountId, :academyId, 'invalid', 100, 0,
				  'COMPLETED', 'PRIVATE', null, :createdAt, null,
				  null, null, 0
				)
				""")
				.setParameter("id", UUID.randomUUID())
				.setParameter("accountId", fixture.account().id())
				.setParameter("academyId", fixture.academy().id())
				.setParameter("createdAt", NOW)
				.executeUpdate())
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("ck_wish_completion_time"));
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
		source.allocate(KrwAmount.positive(30));
		entityManager.persist(source);
		entityManager.persist(destination);
		LedgerEvent transfer = LedgerEvent.transfer(
				fixture.account(),
				source,
				destination,
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

	@Test
	void persistsAccountScopedTransferAsOneEventTwoEffectsAndBothWishUpdates() {
		Fixture fixture = persistFixture();
		Wish source = Wish.create(fixture.account().id(), fixture.academy().id(),
				"노트북", KrwAmount.positive(100), NOW);
		Wish destination = Wish.create(fixture.account().id(), fixture.academy().id(),
				"여행", KrwAmount.positive(100), NOW);
		source.allocate(KrwAmount.positive(70));
		destination.allocate(KrwAmount.positive(80));
		entityManager.persist(source);
		entityManager.persist(destination);

		LedgerEvent transfer = LedgerEvent.transfer(
				fixture.account(), source, destination, KrwAmount.positive(20), NOW);
		entityManager.persist(transfer);
		entityManager.flush();
		entityManager.clear();

		Wish retainedSource = entityManager.find(Wish.class, source.id());
		Wish retainedDestination = entityManager.find(Wish.class, destination.id());
		LedgerEvent retainedEvent = entityManager.find(LedgerEvent.class, transfer.id());
		assertThat(retainedSource.amount()).isEqualTo(KrwAmount.of(50));
		assertThat(retainedDestination.amount()).isEqualTo(KrwAmount.of(100));
		assertThat(retainedDestination.state()).isEqualTo(WishState.AMOUNT_REACHED);
		assertThat(retainedEvent.wishEffects()).hasSize(2);
		assertThat(retainedEvent.wishEffects()).extracting(effect -> effect.delta().won())
				.containsExactlyInAnyOrder(-20L, 20L);
	}

	@Test
	void rejectedTransferLeavesBothPersistedWishRowsUnchanged() {
		Fixture fixture = persistFixture();
		Wish source = Wish.create(fixture.account().id(), fixture.academy().id(),
				"노트북", KrwAmount.positive(100), NOW);
		Wish destination = Wish.create(fixture.account().id(), fixture.academy().id(),
				"여행", KrwAmount.positive(100), NOW);
		source.allocate(KrwAmount.positive(20));
		destination.allocate(KrwAmount.positive(90));
		entityManager.persist(source);
		entityManager.persist(destination);
		entityManager.flush();

		assertThatThrownBy(() -> LedgerEvent.transfer(
				fixture.account(), source, destination, KrwAmount.positive(30), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("source");
		assertThatThrownBy(() -> LedgerEvent.transfer(
				fixture.account(), source, destination, KrwAmount.positive(20), NOW))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("destination");

		entityManager.flush();
		entityManager.clear();
		assertThat(entityManager.find(Wish.class, source.id()).amount()).isEqualTo(KrwAmount.of(20));
		assertThat(entityManager.find(Wish.class, destination.id()).amount()).isEqualTo(KrwAmount.of(90));
		long transferCount = entityManager.createQuery(
				"select count(event) from LedgerEvent event where event.accountId = :accountId",
				Long.class)
				.setParameter("accountId", fixture.account().id())
				.getSingleResult();
		assertThat(transferCount).isZero();
	}

	@Test
	void friendshipPairIsUniqueWithinAcademyButTheSamePairCanExistInAnotherAcademy() {
		Student firstStudent = new Student(UUID.randomUUID(), "첫째");
		Student secondStudent = new Student(UUID.randomUUID(), "둘째");
		Academy firstAcademy = new Academy(UUID.randomUUID(), "A 학원");
		Academy secondAcademy = new Academy(UUID.randomUUID(), "B 학원");
		entityManager.persist(firstStudent);
		entityManager.persist(secondStudent);
		entityManager.persist(firstAcademy);
		entityManager.persist(secondAcademy);
		AcademyMembership firstAtA = new AcademyMembership(firstStudent.id(), firstAcademy.id(), NOW);
		AcademyMembership secondAtA = new AcademyMembership(secondStudent.id(), firstAcademy.id(), NOW);
		AcademyMembership firstAtB = new AcademyMembership(firstStudent.id(), secondAcademy.id(), NOW);
		AcademyMembership secondAtB = new AcademyMembership(secondStudent.id(), secondAcademy.id(), NOW);
		entityManager.persist(firstAtA);
		entityManager.persist(secondAtA);
		entityManager.persist(firstAtB);
		entityManager.persist(secondAtB);
		entityManager.persist(new Friendship(firstAtA, secondAtA, NOW));
		entityManager.persist(new Friendship(firstAtB, secondAtB, NOW));
		entityManager.flush();

		long friendshipCount = entityManager.createQuery(
				"select count(f) from Friendship f", Long.class).getSingleResult();
		assertThat(friendshipCount).isEqualTo(2);
	}

	@Test
	void rejectsDuplicateFriendshipForTheSameAcademyPair() {
		Fixture fixture = persistFixture();
		Student friend = new Student(UUID.randomUUID(), "친구");
		entityManager.persist(friend);
		AcademyMembership ownerMembership = new AcademyMembership(
				fixture.student().id(), fixture.academy().id(), NOW);
		AcademyMembership friendMembership = new AcademyMembership(
				friend.id(), fixture.academy().id(), NOW);
		entityManager.persist(ownerMembership);
		entityManager.persist(friendMembership);
		entityManager.persist(new Friendship(ownerMembership, friendMembership, NOW));
		entityManager.persist(new Friendship(friendMembership, ownerMembership, NOW.plusSeconds(1)));

		assertThatThrownBy(entityManager::flush)
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("uk_friendship_academy_pair"));
	}

	@Test
	void persistsEveryLedgerEventLinkedToOneMismatchEpisode() {
		Fixture fixture = persistFixture();
		LedgerEvent opening = persistTransfer(fixture);
		LedgerEvent middle = persistTransfer(fixture);
		LedgerEvent resolution = persistTransfer(fixture);
		BalanceAdjustmentCase adjustmentCase = BalanceAdjustmentCase.open(
				opening, KrwAmount.positive(30), NOW);
		adjustmentCase.record(middle);
		adjustmentCase.resolve(resolution, NOW.plusSeconds(60));
		entityManager.persist(adjustmentCase);
		entityManager.flush();
		entityManager.clear();

		BalanceAdjustmentCase retained = entityManager.find(
				BalanceAdjustmentCase.class, adjustmentCase.id());
		assertThat(retained.ledgerEvents()).extracting(LedgerEvent::id)
				.containsExactly(opening.id(), middle.id(), resolution.id());
		long linkCount = entityManager.createQuery(
				"select count(link) from BalanceAdjustmentCaseEvent link where link.accountId = :accountId",
				Long.class)
				.setParameter("accountId", fixture.account().id())
				.getSingleResult();
		assertThat(linkCount).isEqualTo(3);
	}

	@Test
	void rejectsMismatchEpisodeLinkToAnEventFromAnotherAccount() {
		Fixture caseFixture = persistFixture();
		Fixture foreignFixture = persistFixture();
		LedgerEvent opening = persistTransfer(caseFixture);
		LedgerEvent foreign = persistTransfer(foreignFixture);
		BalanceAdjustmentCase adjustmentCase = BalanceAdjustmentCase.open(
				opening, KrwAmount.positive(10), NOW);
		entityManager.persist(adjustmentCase);
		entityManager.flush();

		assertThatThrownBy(() -> entityManager.createNativeQuery("""
				insert into balance_adjustment_case_event (
				  id, adjustment_case_id, event_id, account_id
				) values (:id, :caseId, :eventId, :accountId)
				""")
				.setParameter("id", UUID.randomUUID())
				.setParameter("caseId", adjustmentCase.id())
				.setParameter("eventId", foreign.id())
				.setParameter("accountId", caseFixture.account().id())
				.executeUpdate())
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_adjustment_case_event_ledger_account"));
	}

	@Test
	void persistsSuccessFailureZeroChangeAndFirstConnectionObservationProvenance() {
		Fixture fixture = persistFixture();
		BalanceObservation first = BalanceObservation.firstSucceeded(
				fixture.account().id(), BalanceLookupMethod.APP_LAUNCH,
				KrwAmount.nonNegative(100), NOW);
		LedgerEvent change = LedgerEvent.cardBalanceChange(
				fixture.account(), KrwAmount.of(-30), NOW.plusSeconds(1));
		BalanceObservation changed = BalanceObservation.succeeded(
				first, BalanceLookupMethod.MANUAL_REFRESH, KrwAmount.nonNegative(70),
				change, NOW.plusSeconds(1));
		BalanceObservation unchanged = BalanceObservation.succeeded(
				changed, BalanceLookupMethod.AUTO_DAILY, KrwAmount.nonNegative(70),
				null, NOW.plusSeconds(2));
		BalanceObservation failed = BalanceObservation.failed(
				fixture.account().id(), BalanceLookupMethod.PRE_DEPOSIT,
				"CARD_TIMEOUT", NOW.plusSeconds(3));
		entityManager.persist(first);
		entityManager.persist(change);
		entityManager.persist(changed);
		entityManager.persist(unchanged);
		entityManager.persist(failed);
		entityManager.flush();
		entityManager.clear();

		BalanceObservation retainedFirst = entityManager.find(BalanceObservation.class, first.id());
		BalanceObservation retainedChanged = entityManager.find(BalanceObservation.class, changed.id());
		BalanceObservation retainedUnchanged = entityManager.find(BalanceObservation.class, unchanged.id());
		BalanceObservation retainedFailed = entityManager.find(BalanceObservation.class, failed.id());
		assertThat(retainedFirst.isFirstConnection()).isTrue();
		assertThat(retainedFirst.lookupMethod()).isEqualTo(BalanceLookupMethod.APP_LAUNCH);
		assertThat(retainedChanged.previousSuccessfulObservationId()).isEqualTo(first.id());
		assertThat(retainedChanged.balanceChangeEventId()).isEqualTo(change.id());
		assertThat(retainedUnchanged.previousSuccessfulObservationId()).isEqualTo(changed.id());
		assertThat(retainedUnchanged.balanceChangeEventId()).isNull();
		assertThat(retainedFailed.status()).isEqualTo(BalanceObservationStatus.FAILED);
		assertThat(retainedFailed.lookupMethod()).isEqualTo(BalanceLookupMethod.PRE_DEPOSIT);
		assertThat(retainedFailed.failureCode()).isEqualTo("CARD_TIMEOUT");
	}

	@Test
	void rejectsALedgerWishEffectOwnedByAnotherAccountAndAcademy() {
		Fixture eventFixture = persistFixture();
		Fixture foreignWishFixture = persistFixture();
		Wish source = Wish.create(eventFixture.account().id(), eventFixture.academy().id(),
				"노트북", KrwAmount.positive(100), NOW);
		Wish destination = Wish.create(eventFixture.account().id(), eventFixture.academy().id(),
				"여행", KrwAmount.positive(100), NOW);
		Wish foreignWish = Wish.create(foreignWishFixture.account().id(), foreignWishFixture.academy().id(),
				"자전거", KrwAmount.positive(100), NOW);
		source.allocate(KrwAmount.positive(30));
		entityManager.persist(source);
		entityManager.persist(destination);
		entityManager.persist(foreignWish);
		LedgerEvent event = LedgerEvent.transfer(
				eventFixture.account(), source, destination, KrwAmount.positive(30), NOW);
		entityManager.persist(event);
		entityManager.flush();

		LedgerWishEffect foreignEffect = new LedgerWishEffect(
				UUID.randomUUID(), event, foreignWish.id(), foreignWish.purpose(), KrwAmount.positive(1));
		entityManager.persist(foreignEffect);

		assertThatThrownBy(entityManager::flush)
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_ledger_effect_wish_account"));
	}

	@Test
	void rejectsAnAdjustmentOpeningEventOwnedByAnotherAccount() {
		Fixture caseFixture = persistFixture();
		Fixture eventFixture = persistFixture();
		LedgerEvent foreignEvent = persistTransfer(eventFixture);

		assertThatThrownBy(() -> insertAdjustmentCase(
				caseFixture.account().id(), foreignEvent.id(), null, "OPEN"))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_adjustment_opening_event_account"));
	}

	@Test
	void rejectsAnAdjustmentResolutionEventOwnedByAnotherAccount() {
		Fixture caseFixture = persistFixture();
		Fixture foreignFixture = persistFixture();
		LedgerEvent openingEvent = persistTransfer(caseFixture);
		LedgerEvent foreignResolution = persistTransfer(foreignFixture);

		assertThatThrownBy(() -> insertAdjustmentCase(
				caseFixture.account().id(), openingEvent.id(), foreignResolution.id(), "RESOLVED"))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_adjustment_resolution_event_account"));
	}

	@Test
	void rejectsACorrectionOfAnEventOwnedByAnotherAccount() {
		Fixture correctionFixture = persistFixture();
		Fixture originalFixture = persistFixture();
		LedgerEvent originalEvent = persistTransfer(originalFixture);

		assertThatThrownBy(() -> entityManager.createNativeQuery("""
				insert into ledger_event (
				  id, account_id, event_type, account_delta, occurred_at, correction_of_event_id
				) values (
				  :id, :accountId, 'CORRECTION', 0, :occurredAt, :originalEventId
				)
				""")
				.setParameter("id", UUID.randomUUID())
				.setParameter("accountId", correctionFixture.account().id())
				.setParameter("occurredAt", NOW.plusSeconds(1))
				.setParameter("originalEventId", originalEvent.id())
				.executeUpdate())
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_ledger_event_correction_account"));
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

	private LedgerEvent persistTransfer(Fixture fixture) {
		Wish source = Wish.create(fixture.account().id(), fixture.academy().id(),
				"출발", KrwAmount.positive(100), NOW);
		Wish destination = Wish.create(fixture.account().id(), fixture.academy().id(),
				"도착", KrwAmount.positive(100), NOW);
		source.allocate(KrwAmount.positive(1));
		entityManager.persist(source);
		entityManager.persist(destination);
		LedgerEvent event = LedgerEvent.transfer(
				fixture.account(), source, destination, KrwAmount.positive(1), NOW);
		entityManager.persist(event);
		entityManager.flush();
		return event;
	}

	private void insertAdjustmentCase(
			UUID accountId, UUID openingEventId, UUID resolutionEventId, String status) {
		entityManager.createNativeQuery("""
				insert into balance_adjustment_case (
				  id, account_id, opening_event_id, status, opened_shortage,
				  opened_at, resolved_at, resolution_event_id
				) values (
				  :id, :accountId, :openingEventId, :status, 1,
				  :openedAt, :resolvedAt, :resolutionEventId
				)
				""")
				.setParameter("id", UUID.randomUUID())
				.setParameter("accountId", accountId)
				.setParameter("openingEventId", openingEventId)
				.setParameter("status", status)
				.setParameter("openedAt", NOW)
				.setParameter("resolvedAt", resolutionEventId == null ? null : NOW.plusSeconds(1))
				.setParameter("resolutionEventId", resolutionEventId)
				.executeUpdate();
	}

	private record Fixture(Academy academy, Student student, CardBalanceAccount account) {
	}
}
