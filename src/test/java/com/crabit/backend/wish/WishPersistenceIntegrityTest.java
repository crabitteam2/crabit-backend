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
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({
		RelationshipContextAuthorizationService.class,
		RelationshipCommandService.class,
		WishMoneyCommandService.class,
		WishEditCommandService.class,
		CardBalanceObservationService.class
})
class WishPersistenceIntegrityTest {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private RelationshipContextAuthorizationService relationshipAuthorization;

	@Autowired
	private RelationshipCommandService relationshipCommands;

	@Autowired
	private WishMoneyCommandService moneyCommands;

	@Autowired
	private WishEditCommandService wishEdits;

	@Autowired
	private CardBalanceObservationService observationService;

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
	void accountScopedServicePersistsEveryMoneyCommandFactAndSharedCardProjection() {
		Fixture fixture = persistFixture();
		observationService.recordSuccess(
				fixture.account().id(), BalanceLookupMethod.APP_LAUNCH,
				KrwAmount.nonNegative(1_000), NOW);
		Wish completed = publicWish(fixture, "완료", 100);
		Wish abandoned = publicWish(fixture, "포기", 100);
		Wish deleted = publicWish(fixture, "삭제", 100);
		Wish active = publicWish(fixture, "진행", 100);
		entityManager.persist(completed);
		entityManager.persist(abandoned);
		entityManager.persist(deleted);
		entityManager.persist(active);
		entityManager.flush();

		moneyCommands.deposit(fixture.account().id(), completed.id(),
				KrwAmount.positive(100), depositProof(fixture.account().id(), 1_000,
						NOW.plusMillis(500)), NOW.plusSeconds(1));
		moneyCommands.deposit(fixture.account().id(), abandoned.id(),
				KrwAmount.positive(60), depositProof(fixture.account().id(), 1_000,
						NOW.plusMillis(1_500)), NOW.plusSeconds(2));
		moneyCommands.deposit(fixture.account().id(), deleted.id(),
				KrwAmount.positive(50), depositProof(fixture.account().id(), 1_000,
						NOW.plusMillis(2_500)), NOW.plusSeconds(3));
		moneyCommands.deposit(fixture.account().id(), active.id(),
				KrwAmount.positive(40), depositProof(fixture.account().id(), 1_000,
						NOW.plusMillis(3_500)), NOW.plusSeconds(4));
		moneyCommands.withdraw(fixture.account().id(), active.id(),
				KrwAmount.positive(10), NOW.plusSeconds(5));
		moneyCommands.complete(
				fixture.account().id(), completed.id(), NOW.plusSeconds(6));
		moneyCommands.abandon(
				fixture.account().id(), abandoned.id(), NOW.plusSeconds(7));
		moneyCommands.tombstone(
				fixture.account().id(), deleted.id(), NOW.plusSeconds(8));
		entityManager.flush();
		entityManager.clear();

		List<LedgerEventType> eventTypes = entityManager.createQuery(
				"select event.type from LedgerEvent event where event.accountId = :accountId order by event.occurredAt",
				LedgerEventType.class)
				.setParameter("accountId", fixture.account().id())
				.getResultList();
		assertThat(eventTypes).containsExactly(
				LedgerEventType.CARD_BALANCE_CHANGE,
				LedgerEventType.WISH_DEPOSIT,
				LedgerEventType.WISH_DEPOSIT,
				LedgerEventType.WISH_DEPOSIT,
				LedgerEventType.WISH_DEPOSIT,
				LedgerEventType.WISH_WITHDRAWAL,
				LedgerEventType.WISH_COMPLETION_RETURN,
				LedgerEventType.WISH_ABANDONMENT_RETURN,
				LedgerEventType.WISH_DELETION_RETURN);
		assertThat(entityManager.find(Wish.class, completed.id()).state())
				.isEqualTo(WishState.COMPLETED);
		assertThat(entityManager.find(Wish.class, abandoned.id()).state())
				.isEqualTo(WishState.ABANDONED);
		assertThat(entityManager.find(Wish.class, deleted.id()).isDeleted()).isTrue();
		assertThat(entityManager.createQuery(
				"select card from SharedCard card where card.wishId = :wishId", SharedCard.class)
				.setParameter("wishId", completed.id())
				.getSingleResult().kind()).isEqualTo(SharedCardKind.COMPLETION);
		assertThat(entityManager.createQuery(
				"select count(card) from SharedCard card where card.wishId in :wishIds", Long.class)
				.setParameter("wishIds", List.of(abandoned.id(), deleted.id()))
				.getSingleResult()).isZero();
	}

	@Test
	void openMismatchBlocksTransferAndAtomicallyRetainsOpeningResolutionAndOutbox() {
		Fixture fixture = persistFixture();
		observationService.recordSuccess(
				fixture.account().id(), BalanceLookupMethod.APP_LAUNCH,
				KrwAmount.nonNegative(100), NOW);
		Wish source = publicWish(fixture, "조정 대상", 100);
		Wish destination = publicWish(fixture, "이동 대상", 100);
		entityManager.persist(source);
		entityManager.persist(destination);
		entityManager.flush();
		moneyCommands.deposit(fixture.account().id(), source.id(),
				KrwAmount.positive(80), depositProof(fixture.account().id(), 100,
						NOW.plusMillis(500)), NOW.plusSeconds(1));

		BalanceObservation mismatchObservation = observationService.recordSuccess(
				fixture.account().id(), BalanceLookupMethod.MANUAL_REFRESH,
				KrwAmount.nonNegative(50), NOW.plusSeconds(2));

		assertThatThrownBy(() -> moneyCommands.transfer(
				fixture.account().id(), source.id(), destination.id(),
				KrwAmount.positive(10), NOW.plusSeconds(3)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("adjustment");

		moneyCommands.withdraw(fixture.account().id(), source.id(),
				KrwAmount.positive(30), NOW.plusSeconds(4));
		entityManager.flush();
		entityManager.clear();

		BalanceAdjustmentCase adjustment = entityManager.createQuery(
				"select adjustment from BalanceAdjustmentCase adjustment where adjustment.accountId = :accountId",
				BalanceAdjustmentCase.class)
				.setParameter("accountId", fixture.account().id())
				.getSingleResult();
		assertThat(adjustment.isOpen()).isFalse();
		assertThat(adjustment.eventLinks()).extracting(BalanceAdjustmentCaseEvent::role)
				.containsExactly(
						BalanceAdjustmentEventRole.OPENING,
						BalanceAdjustmentEventRole.RESOLUTION);
		assertThat(adjustment.openingEventId())
				.isEqualTo(mismatchObservation.balanceChangeEventId());
		assertThat(adjustment.resolutionEventId()).isNotNull();
		assertThat(entityManager.createQuery(
				"select count(outbox) from MismatchNotificationOutbox outbox", Long.class)
				.getSingleResult()).isOne();
		assertThat(entityManager.createQuery(
				"select count(event) from LedgerEvent event where event.type = :type", Long.class)
				.setParameter("type", LedgerEventType.WISH_TRANSFER)
				.getSingleResult()).isZero();
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
	void relationshipContextRequiresBothCurrentMembershipsAndCurrentAcademyFriendship() {
		RelationshipFixture relationship = persistRelationshipFixture();

		assertThat(relationshipAuthorization.canViewFriendsCard(
				relationship.owner().id(), relationship.viewer().id(), relationship.academy().id()))
				.isTrue();

		relationship.viewerMembership().leave(NOW.plusSeconds(1));
		entityManager.flush();

		assertThat(relationshipAuthorization.canViewFriendsCard(
				relationship.owner().id(), relationship.viewer().id(), relationship.academy().id()))
				.isFalse();
	}

	@Test
	void relationshipContextRevokesAccessAfterUnfriendAndNeverLeaksAcrossAcademies() {
		RelationshipFixture relationship = persistRelationshipFixture();

		assertThat(relationshipAuthorization.canViewFriendsCard(
				relationship.owner().id(), relationship.viewer().id(), UUID.randomUUID()))
				.isFalse();

		relationship.friendship().end(NOW.plusSeconds(1));
		entityManager.flush();

		assertThat(relationshipAuthorization.canViewFriendsCard(
				relationship.owner().id(), relationship.viewer().id(), relationship.academy().id()))
				.isFalse();
	}

	@Test
	void relationshipContextRejectsACurrentBlockInEitherDirection() {
		RelationshipFixture relationship = persistRelationshipFixture();
		StudentBlock ownerBlocksViewer = new StudentBlock(
				relationship.owner().id(), relationship.viewer().id(), NOW.plusSeconds(1));
		entityManager.persist(ownerBlocksViewer);
		entityManager.flush();

		assertThat(relationshipAuthorization.canViewFriendsCard(
				relationship.owner().id(), relationship.viewer().id(), relationship.academy().id()))
				.isFalse();

		ownerBlocksViewer.release(NOW.plusSeconds(2));
		entityManager.persist(new StudentBlock(
				relationship.viewer().id(), relationship.owner().id(), NOW.plusSeconds(3)));
		entityManager.flush();

		assertThat(relationshipAuthorization.canViewFriendsCard(
				relationship.owner().id(), relationship.viewer().id(), relationship.academy().id()))
				.isFalse();
	}

	@Test
	void accountScopedBlockEndsTheAcademyFriendshipAndReleaseDoesNotRestoreAccess() {
		RelationshipFixture relationship = persistRelationshipFixture();

		relationshipCommands.block(
				relationship.account().id(), relationship.viewer().id(), NOW.plusSeconds(1));
		entityManager.flush();
		entityManager.clear();

		Friendship endedFriendship = entityManager.createQuery(
				"select friendship from Friendship friendship where friendship.academyId = :academyId",
				Friendship.class)
				.setParameter("academyId", relationship.academy().id())
				.getSingleResult();
		assertThat(endedFriendship.endedAt()).isEqualTo(NOW.plusSeconds(1));
		assertThat(relationshipAuthorization.canViewFriendsCard(
				relationship.owner().id(), relationship.viewer().id(), relationship.academy().id()))
				.isFalse();

		relationshipCommands.releaseBlock(
				relationship.account().id(), relationship.viewer().id(), NOW.plusSeconds(2));
		entityManager.flush();
		entityManager.clear();

		assertThat(relationshipAuthorization.canViewFriendsCard(
				relationship.owner().id(), relationship.viewer().id(), relationship.academy().id()))
				.isFalse();
		assertThat(entityManager.createQuery(
				"select count(block) from StudentBlock block where block.releasedAt is null", Long.class)
				.getSingleResult()).isZero();
		assertThat(entityManager.createQuery(
				"select count(friendship) from Friendship friendship where friendship.endedAt is null",
				Long.class).getSingleResult()).isZero();
	}

	@Test
	void globalBlockEndsEveryAcademyFriendshipAndExplicitRefriendRestartsEachPair() {
		Academy academyA = new Academy(UUID.randomUUID(), "A 관계 학원");
		Academy academyB = new Academy(UUID.randomUUID(), "B 관계 학원");
		Student owner = new Student(UUID.randomUUID(), "소유자");
		Student viewer = new Student(UUID.randomUUID(), "열람자");
		AcademyMembership ownerA = new AcademyMembership(owner.id(), academyA.id(), NOW);
		AcademyMembership viewerA = new AcademyMembership(viewer.id(), academyA.id(), NOW);
		AcademyMembership ownerB = new AcademyMembership(owner.id(), academyB.id(), NOW);
		AcademyMembership viewerB = new AcademyMembership(viewer.id(), academyB.id(), NOW);
		CardBalanceAccount accountA = CardBalanceAccount.open(owner.id(), academyA.id(), NOW);
		CardBalanceAccount accountB = CardBalanceAccount.open(owner.id(), academyB.id(), NOW);
		entityManager.persist(academyA);
		entityManager.persist(academyB);
		entityManager.persist(owner);
		entityManager.persist(viewer);
		entityManager.persist(ownerA);
		entityManager.persist(viewerA);
		entityManager.persist(ownerB);
		entityManager.persist(viewerB);
		entityManager.persist(accountA);
		entityManager.persist(accountB);
		entityManager.persist(new Friendship(ownerA, viewerA, NOW));
		entityManager.persist(new Friendship(ownerB, viewerB, NOW));
		entityManager.flush();

		relationshipCommands.block(accountA.id(), viewer.id(), NOW.plusSeconds(1));
		entityManager.flush();
		entityManager.clear();

		assertThat(entityManager.createQuery(
				"select count(friendship) from Friendship friendship where friendship.endedAt is null",
				Long.class).getSingleResult()).isZero();
		assertThat(relationshipAuthorization.canViewFriendsCard(
				owner.id(), viewer.id(), academyA.id())).isFalse();
		assertThat(relationshipAuthorization.canViewFriendsCard(
				owner.id(), viewer.id(), academyB.id())).isFalse();

		relationshipCommands.releaseBlock(accountA.id(), viewer.id(), NOW.plusSeconds(2));
		entityManager.flush();
		entityManager.clear();

		assertThat(relationshipAuthorization.canViewFriendsCard(
				owner.id(), viewer.id(), academyA.id())).isFalse();
		assertThat(relationshipAuthorization.canViewFriendsCard(
				owner.id(), viewer.id(), academyB.id())).isFalse();

		relationshipCommands.befriend(accountA.id(), viewer.id(), NOW.plusSeconds(3));
		entityManager.flush();
		entityManager.clear();
		assertThat(relationshipAuthorization.canViewFriendsCard(
				owner.id(), viewer.id(), academyA.id())).isTrue();
		assertThat(relationshipAuthorization.canViewFriendsCard(
				owner.id(), viewer.id(), academyB.id())).isFalse();

		relationshipCommands.befriend(accountB.id(), viewer.id(), NOW.plusSeconds(4));
		entityManager.flush();
		entityManager.clear();

		assertThat(relationshipAuthorization.canViewFriendsCard(
				owner.id(), viewer.id(), academyB.id())).isTrue();
		List<Friendship> friendships = entityManager.createQuery(
				"select friendship from Friendship friendship order by friendship.startedAt",
				Friendship.class).getResultList();
		assertThat(friendships).hasSize(2);
		assertThat(friendships).extracting(Friendship::startedAt)
				.containsExactly(NOW.plusSeconds(3), NOW.plusSeconds(4));
		assertThat(friendships).extracting(Friendship::endedAt).containsOnlyNulls();
	}

	@Test
	void accountLockedWishEditsPersistAndSynchronizeOrRemoveTheCurrentSharedCard() {
		Fixture fixture = persistFixture();
		Wish wish = Wish.create(fixture.account().id(), fixture.academy().id(),
				"노트북", KrwAmount.positive(100), NOW);
		entityManager.persist(wish);
		entityManager.flush();

		wishEdits.changeVisibility(fixture.account().id(), wish.id(),
				WishVisibility.FRIENDS, NOW.plusSeconds(1));
		wishEdits.changePurpose(fixture.account().id(), wish.id(),
				"여름 캠프", NOW.plusSeconds(2));
		wishEdits.changeTarget(fixture.account().id(), wish.id(),
				KrwAmount.positive(150), NOW.plusSeconds(3));
		wishEdits.changeTargetDate(fixture.account().id(), wish.id(),
				LocalDate.of(2026, 12, 31), NOW.plusSeconds(4));
		entityManager.flush();
		entityManager.clear();

		Wish retained = entityManager.find(Wish.class, wish.id());
		SharedCard retainedCard = entityManager.createQuery(
				"select card from SharedCard card where card.wishId = :wishId", SharedCard.class)
				.setParameter("wishId", wish.id())
				.getSingleResult();
		assertThat(retained.purpose()).isEqualTo("여름 캠프");
		assertThat(retained.targetAmount()).isEqualTo(KrwAmount.of(150));
		assertThat(retained.targetDate()).isEqualTo(LocalDate.of(2026, 12, 31));
		assertThat(retained.visibility()).isEqualTo(WishVisibility.FRIENDS);
		assertThat(retainedCard.visibility()).isEqualTo(WishVisibility.FRIENDS);
		assertThat(retainedCard.updatedAt()).isEqualTo(NOW.plusSeconds(4));

		wishEdits.changeVisibility(fixture.account().id(), wish.id(),
				WishVisibility.PRIVATE, NOW.plusSeconds(5));
		entityManager.flush();
		entityManager.clear();

		assertThat(entityManager.createQuery(
				"select count(card) from SharedCard card where card.wishId = :wishId", Long.class)
				.setParameter("wishId", wish.id())
				.getSingleResult()).isZero();
	}

	@Test
	void persistsEveryLedgerEventLinkedToOneMismatchEpisode() {
		Fixture fixture = persistFixture();
		LedgerEvent opening = persistCardBalanceChange(fixture, -30, NOW);
		LedgerEvent middle = persistCardBalanceChange(fixture, -10, NOW.plusSeconds(20));
		LedgerEvent resolution = persistCardBalanceChange(fixture, 40, NOW.plusSeconds(40));
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
	@SuppressWarnings("unchecked")
	void persistenceLifecycleRejectsAnEventAppendedAfterAdjustmentResolution()
			throws ReflectiveOperationException {
		Fixture fixture = persistFixture();
		LedgerEvent opening = persistCardBalanceChange(fixture, -30, NOW);
		LedgerEvent resolution = persistCardBalanceChange(fixture, 30, NOW.plusSeconds(10));
		BalanceAdjustmentCase adjustment = BalanceAdjustmentCase.open(
				opening, KrwAmount.positive(30), NOW);
		adjustment.resolve(resolution, NOW.plusSeconds(10));
		entityManager.persist(adjustment);
		entityManager.flush();

		LedgerEvent late = persistCardBalanceChange(fixture, -1, NOW.plusSeconds(11));
		BalanceAdjustmentCaseEvent bypass = new BalanceAdjustmentCaseEvent(
				UUID.randomUUID(), adjustment, late, 2, BalanceAdjustmentEventRole.INTERMEDIATE);
		Field eventLinksField = BalanceAdjustmentCase.class.getDeclaredField("eventLinks");
		eventLinksField.setAccessible(true);
		((List<BalanceAdjustmentCaseEvent>) eventLinksField.get(adjustment)).add(bypass);

		assertThatThrownBy(() -> {
			entityManager.persist(bypass);
			entityManager.flush();
		}).isInstanceOfAny(IllegalStateException.class, PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("resolution event last"));
	}

	@Test
	void rejectsMismatchEpisodeLinkToAnEventFromAnotherAccount() {
		Fixture caseFixture = persistFixture();
		Fixture foreignFixture = persistFixture();
		LedgerEvent opening = persistCardBalanceChange(caseFixture, -10, NOW);
		LedgerEvent foreign = persistCardBalanceChange(foreignFixture, 10, NOW.plusSeconds(10));
		BalanceAdjustmentCase adjustmentCase = BalanceAdjustmentCase.open(
				opening, KrwAmount.positive(10), NOW);
		entityManager.persist(adjustmentCase);
		entityManager.flush();

		assertThatThrownBy(() -> entityManager.createNativeQuery("""
				insert into balance_adjustment_case_event (
				  id, adjustment_case_id, event_id, account_id, sequence_number, event_role
				) values (:id, :caseId, :eventId, :accountId, 1, 'INTERMEDIATE')
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
		LedgerEvent firstDeposit = LedgerEvent.cardBalanceChange(
				fixture.account(), KrwAmount.positive(100), NOW);
		BalanceObservation first = BalanceObservation.firstSucceeded(
				fixture.account().id(), BalanceLookupMethod.APP_LAUNCH,
				KrwAmount.nonNegative(100), firstDeposit, NOW);
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
		entityManager.persist(firstDeposit);
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
		assertThat(retainedFirst.balanceChangeEventId()).isEqualTo(firstDeposit.id());
		assertThat(retainedFirst.balanceChangeEventType())
				.isEqualTo(LedgerEventType.CARD_BALANCE_CHANGE);
		assertThat(retainedFirst.balanceChangeEventDelta()).isEqualTo(KrwAmount.of(100));
		assertThat(retainedChanged.previousSuccessfulObservationId()).isEqualTo(first.id());
		assertThat(retainedChanged.balanceChangeEventId()).isEqualTo(change.id());
		assertThat(retainedUnchanged.previousSuccessfulObservationId()).isEqualTo(changed.id());
		assertThat(retainedUnchanged.balanceChangeEventId()).isNull();
		assertThat(retainedFailed.status()).isEqualTo(BalanceObservationStatus.FAILED);
		assertThat(retainedFailed.lookupMethod()).isEqualTo(BalanceLookupMethod.PRE_DEPOSIT);
		assertThat(retainedFailed.failureCode()).isEqualTo("CARD_TIMEOUT");
	}

	@Test
	void rejectsObservationWhosePersistedChangeTypeOrDeltaDoesNotMatch() {
		Fixture fixture = persistFixture();
		UUID wrongTypeEvent = insertRawLedgerEvent(
				fixture.account().id(), "WISH_DEPOSIT", 100, NOW);

		assertThatThrownBy(() -> insertObservation(
				fixture.account().id(), "SUCCEEDED", 100L, null, true,
				null, 0L, wrongTypeEvent, "WISH_DEPOSIT", 100L, NOW))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("ck_observation_change_provenance"));
	}

	@Test
	void rejectsObservationWhosePersistedChangeDeltaIsNotExact() {
		Fixture fixture = persistFixture();
		LedgerEvent wrongDelta = persistCardBalanceChange(fixture, 90, NOW);

		assertThatThrownBy(() -> insertObservation(
				fixture.account().id(), "SUCCEEDED", 100L, null, true,
				null, 0L, wrongDelta.id(), "CARD_BALANCE_CHANGE", 90L, NOW))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("ck_observation_change_provenance"));
	}

	@Test
	void rejectsObservationWhosePersistedChangeEventOccurredAtDoesNotMatch() {
		Fixture fixture = persistFixture();
		LedgerEvent eventAtAnotherTime = persistCardBalanceChange(fixture, 100, NOW);

		assertThatThrownBy(() -> insertObservation(
				fixture.account().id(), "SUCCEEDED", 100L, null, true,
				null, 0L, eventAtAnotherTime.id(), "CARD_BALANCE_CHANGE", 100L,
				NOW.plusSeconds(1)))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_observation_change_event_proof"));
	}

	@Test
	void rejectsReuseOfOneBalanceChangeEventForTwoSuccessfulObservations() {
		Fixture fixture = persistFixture();
		LedgerEvent deposit = persistCardBalanceChange(fixture, 100, NOW);
		BalanceObservation first = BalanceObservation.firstSucceeded(
				fixture.account().id(), BalanceLookupMethod.APP_LAUNCH,
				KrwAmount.positive(100), deposit, NOW);
		entityManager.persist(first);
		entityManager.flush();

		assertThatThrownBy(() -> insertObservation(
				fixture.account().id(), "SUCCEEDED", 200L, null, null,
				first.id(), 100L, deposit.id(), "CARD_BALANCE_CHANGE", 100L,
				NOW.plusSeconds(1)))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("uk_observation_change_event"));
	}

	@Test
	void rejectsASecondDisconnectedSuccessfulObservationRoot() {
		Fixture fixture = persistFixture();
		entityManager.persist(BalanceObservation.firstSucceeded(
				fixture.account().id(), BalanceLookupMethod.APP_LAUNCH, KrwAmount.zero(), NOW));
		entityManager.flush();

		assertThatThrownBy(() -> insertObservation(
				fixture.account().id(), "SUCCEEDED", 0L, null, true,
				null, 0L, null, null, null, NOW.plusSeconds(1)))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("uk_observation_first_success"));
	}

	@Test
	void rejectsABrokenPreviousBalanceProofAndFailedObservationWithMoneyFact() {
		Fixture fixture = persistFixture();
		LedgerEvent deposit = persistCardBalanceChange(fixture, 100, NOW);
		BalanceObservation first = BalanceObservation.firstSucceeded(
				fixture.account().id(), BalanceLookupMethod.APP_LAUNCH,
				KrwAmount.positive(100), deposit, NOW);
		entityManager.persist(first);
		LedgerEvent withdrawal = persistCardBalanceChange(fixture, -10, NOW.plusSeconds(1));

		assertThatThrownBy(() -> insertObservation(
				fixture.account().id(), "SUCCEEDED", 89L, null, null,
				first.id(), 99L, withdrawal.id(), "CARD_BALANCE_CHANGE", -10L,
				NOW.plusSeconds(1)))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_observation_previous_success_proof"));
	}

	@Test
	void rejectsFailedObservationThatBypassesTheNoMoneyFactRule() {
		Fixture fixture = persistFixture();
		LedgerEvent event = persistCardBalanceChange(fixture, 1, NOW);

		assertThatThrownBy(() -> insertObservation(
				fixture.account().id(), "FAILED", null, "TIMEOUT", null,
				null, null, event.id(), "CARD_BALANCE_CHANGE", 1L, NOW))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("ck_observation_change_provenance"));
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
		LedgerEvent foreignEvent = persistCardBalanceChange(eventFixture, -1, NOW);

		assertThatThrownBy(() -> insertAdjustmentCase(
				caseFixture.account().id(), foreignEvent.id(), "CARD_BALANCE_CHANGE", -1,
				NOW, null, "OPEN"))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_adjustment_opening_event_proof"));
	}

	@Test
	void rejectsAdjustmentOpeningFromWrongTypeEvenWhenTheEventIdentityMatches() {
		Fixture fixture = persistFixture();
		LedgerEvent transfer = persistTransfer(fixture);

		assertThatThrownBy(() -> insertAdjustmentCase(
				fixture.account().id(), transfer.id(), "WISH_TRANSFER", 0,
				NOW, null, "OPEN"))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("ck_adjustment_opening_provenance"));
	}

	@Test
	void rejectsAdjustmentOpeningFromANonnegativeCardBalanceChange() {
		Fixture fixture = persistFixture();
		LedgerEvent increase = persistCardBalanceChange(fixture, 1, NOW);

		assertThatThrownBy(() -> insertAdjustmentCase(
				fixture.account().id(), increase.id(), "CARD_BALANCE_CHANGE", 1,
				NOW, null, "OPEN"))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("ck_adjustment_opening_provenance"));
	}

	@Test
	void rejectsAdjustmentOpeningAtATimeDifferentFromItsExactEvent() {
		Fixture fixture = persistFixture();
		LedgerEvent decrease = persistCardBalanceChange(fixture, -1, NOW.plusSeconds(1));

		assertThatThrownBy(() -> insertAdjustmentCase(
				fixture.account().id(), decrease.id(), "CARD_BALANCE_CHANGE", -1,
				NOW, null, "OPEN"))
				.isInstanceOf(PersistenceException.class)
				.satisfies(error -> assertThat(causeMessages(error))
						.containsIgnoringCase("fk_adjustment_opening_event_proof"));
	}

	@Test
	void rejectsAnAdjustmentResolutionEventOwnedByAnotherAccount() {
		Fixture caseFixture = persistFixture();
		Fixture foreignFixture = persistFixture();
		LedgerEvent openingEvent = persistCardBalanceChange(caseFixture, -1, NOW);
		LedgerEvent foreignResolution = persistCardBalanceChange(foreignFixture, 1, NOW.plusSeconds(1));

		assertThatThrownBy(() -> insertAdjustmentCase(
				caseFixture.account().id(), openingEvent.id(), "CARD_BALANCE_CHANGE", -1,
				NOW, foreignResolution.id(), "RESOLVED"))
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

	private Wish publicWish(Fixture fixture, String purpose, long target) {
		Wish wish = Wish.create(fixture.account().id(), fixture.academy().id(),
				purpose, KrwAmount.positive(target), NOW);
		wish.changeVisibility(WishVisibility.FRIENDS);
		return wish;
	}

	private RelationshipFixture persistRelationshipFixture() {
		Academy academy = new Academy(UUID.randomUUID(), "관계 학원");
		Student owner = new Student(UUID.randomUUID(), "소유자");
		Student viewer = new Student(UUID.randomUUID(), "열람자");
		AcademyMembership ownerMembership = new AcademyMembership(owner.id(), academy.id(), NOW);
		AcademyMembership viewerMembership = new AcademyMembership(viewer.id(), academy.id(), NOW);
		Friendship friendship = new Friendship(ownerMembership, viewerMembership, NOW);
		CardBalanceAccount account = CardBalanceAccount.open(owner.id(), academy.id(), NOW);
		entityManager.persist(academy);
		entityManager.persist(owner);
		entityManager.persist(viewer);
		entityManager.persist(ownerMembership);
		entityManager.persist(viewerMembership);
		entityManager.persist(friendship);
		entityManager.persist(account);
		entityManager.flush();
		return new RelationshipFixture(
				academy, owner, viewer, ownerMembership, viewerMembership, friendship, account);
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

	private DepositBalanceProof depositProof(UUID accountId, long balance, Instant observedAt) {
		return DepositBalanceProof.from(observationService.recordSuccess(
				accountId, BalanceLookupMethod.PRE_DEPOSIT,
				KrwAmount.nonNegative(balance), observedAt));
	}

	private LedgerEvent persistCardBalanceChange(Fixture fixture, long delta, Instant occurredAt) {
		LedgerEvent event = LedgerEvent.cardBalanceChange(
				fixture.account(), KrwAmount.of(delta), occurredAt);
		entityManager.persist(event);
		entityManager.flush();
		return event;
	}

	private UUID insertRawLedgerEvent(
			UUID accountId, String eventType, long accountDelta, Instant occurredAt) {
		UUID id = UUID.randomUUID();
		entityManager.createNativeQuery("""
				insert into ledger_event (
				  id, account_id, event_type, account_delta, occurred_at, correction_of_event_id
				) values (:id, :accountId, :eventType, :accountDelta, :occurredAt, null)
				""")
				.setParameter("id", id)
				.setParameter("accountId", accountId)
				.setParameter("eventType", eventType)
				.setParameter("accountDelta", accountDelta)
				.setParameter("occurredAt", occurredAt)
				.executeUpdate();
		return id;
	}

	private void insertObservation(
			UUID accountId,
			String status,
			Long actualBalance,
			String failureCode,
			Boolean firstSuccessful,
			UUID previousObservationId,
			Long previousBalance,
			UUID eventId,
			String eventType,
			Long eventDelta,
			Instant observedAt) {
		entityManager.createNativeQuery("""
				insert into balance_observation (
				  id, account_id, status, lookup_method, actual_card_balance, failure_code,
				  first_successful, previous_successful_observation_id,
				  previous_successful_balance, balance_change_event_id,
				  balance_change_event_type, balance_change_event_delta, observed_at
				) values (
				  :id, :accountId, :status, 'MANUAL_REFRESH', :actualBalance, :failureCode,
				  :firstSuccessful, :previousObservationId,
				  :previousBalance, :eventId, :eventType, :eventDelta, :observedAt
				)
				""")
				.setParameter("id", UUID.randomUUID())
				.setParameter("accountId", accountId)
				.setParameter("status", status)
				.setParameter("actualBalance", actualBalance)
				.setParameter("failureCode", failureCode)
				.setParameter("firstSuccessful", firstSuccessful)
				.setParameter("previousObservationId", previousObservationId)
				.setParameter("previousBalance", previousBalance)
				.setParameter("eventId", eventId)
				.setParameter("eventType", eventType)
				.setParameter("eventDelta", eventDelta)
				.setParameter("observedAt", observedAt)
				.executeUpdate();
	}

	private void insertAdjustmentCase(
			UUID accountId,
			UUID openingEventId,
			String openingEventType,
			long openingEventDelta,
			Instant openedAt,
			UUID resolutionEventId,
			String status) {
		entityManager.createNativeQuery("""
				insert into balance_adjustment_case (
				  id, account_id, opening_event_id, opening_event_type,
				  opening_event_delta, status, opened_shortage,
				  opened_at, resolved_at, resolution_event_id
				) values (
				  :id, :accountId, :openingEventId, :openingEventType,
				  :openingEventDelta, :status, 1,
				  :openedAt, :resolvedAt, :resolutionEventId
				)
				""")
				.setParameter("id", UUID.randomUUID())
				.setParameter("accountId", accountId)
				.setParameter("openingEventId", openingEventId)
				.setParameter("openingEventType", openingEventType)
				.setParameter("openingEventDelta", openingEventDelta)
				.setParameter("status", status)
				.setParameter("openedAt", openedAt)
				.setParameter("resolvedAt", resolutionEventId == null
						? null : openedAt.plusSeconds(1))
				.setParameter("resolutionEventId", resolutionEventId)
				.executeUpdate();
	}

	private record Fixture(Academy academy, Student student, CardBalanceAccount account) {
	}

	private record RelationshipFixture(
			Academy academy,
			Student owner,
			Student viewer,
			AcademyMembership ownerMembership,
			AcademyMembership viewerMembership,
			Friendship friendship,
			CardBalanceAccount account) {
	}
}
