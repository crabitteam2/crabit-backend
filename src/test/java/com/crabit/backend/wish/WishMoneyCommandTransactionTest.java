package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crabit.backend.account.Academy;
import com.crabit.backend.account.AcademyMembership;
import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.Student;
import com.crabit.backend.relationship.Friendship;
import com.crabit.backend.relationship.RelationshipCommandService;
import com.crabit.backend.relationship.StudentBlock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Import({
		WishMoneyCommandService.class,
		RepresentativeWishService.class,
		WishEditCommandService.class,
		BalanceAdjustmentPolicy.class,
		RelationshipCommandService.class,
		CardBalanceObservationService.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WishMoneyCommandTransactionTest {

	private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private WishMoneyCommandService moneyCommands;

	@Autowired
	private WishEditCommandService wishEdits;

	@Autowired
	private RelationshipCommandService relationshipCommands;

	@Autowired
	private CardBalanceObservationService observationService;

	@Autowired
	private WishRepository wishRepository;

	@Autowired
	private LedgerEventRepository eventRepository;

	@Autowired
	private BalanceObservationRepository observationRepository;

	@Autowired
	private BalanceAdjustmentCaseRepository adjustmentRepository;

	@Autowired
	private MismatchNotificationOutboxRepository outboxRepository;

	@Autowired
	private SharedCardRepository sharedCardRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void outerFailureRollsBackWishLedgerEffectsAndSharedCardAsOneUnit() {
		Scenario scenario = createScenario(100, List.of(new WishSpec("노트북", 100, true)));
		UUID wishId = scenario.wishIds().getFirst();
		DepositBalanceProof proof = depositProof(scenario.accountId(), 100, NOW.plusMillis(500));

		assertThatThrownBy(() -> requiredTransaction().executeWithoutResult(status -> {
			moneyCommands.deposit(
					scenario.accountId(), wishId, KrwAmount.positive(60), proof,
					NOW.plusSeconds(1));
			throw new ForcedRollback();
		})).isInstanceOf(ForcedRollback.class);

		Wish retained = wishRepository.findById(wishId).orElseThrow();
		assertThat(retained.amount()).isEqualTo(KrwAmount.zero());
		long eventCount = countByAccount("LedgerEvent", scenario.accountId());
		assertThat(eventCount).isOne();
		assertThat(sharedCardRepository.findByWishId(wishId)).isEmpty();
		long effectCount = requiredTransaction().execute(status -> entityManager.createQuery(
				"select count(effect) from LedgerWishEffect effect where effect.accountId = :accountId",
				Long.class)
				.setParameter("accountId", scenario.accountId())
				.getSingleResult());
		assertThat(effectCount).isZero();

		moneyCommands.deposit(
				scenario.accountId(), wishId, KrwAmount.positive(60), proof,
				NOW.plusSeconds(2));

		assertThat(wishRepository.findById(wishId).orElseThrow().amount())
				.isEqualTo(KrwAmount.of(60));
		assertThat(countDeposits(scenario.accountId())).isOne();
	}

	@Test
	void acceptedPreDepositProofCannotBeReplayed() {
		Scenario scenario = createScenario(100, List.of(new WishSpec("노트북", 100, false)));
		UUID wishId = scenario.wishIds().getFirst();
		DepositBalanceProof proof = depositProof(scenario.accountId(), 100, NOW.plusMillis(500));

		moneyCommands.deposit(
				scenario.accountId(), wishId, KrwAmount.positive(10), proof,
				NOW.plusSeconds(1));

		assertThatThrownBy(() -> moneyCommands.deposit(
				scenario.accountId(), wishId, KrwAmount.positive(10), proof,
				NOW.plusSeconds(2)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already authorized");

		assertThat(wishRepository.findById(wishId).orElseThrow().amount())
				.isEqualTo(KrwAmount.of(10));
		assertThat(countDeposits(scenario.accountId())).isOne();
	}

	@Test
	void concurrentDepositsCannotConsumeTheSamePreDepositProofTwice() throws Exception {
		Scenario scenario = createScenario(100, List.of(new WishSpec("노트북", 100, false)));
		UUID wishId = scenario.wishIds().getFirst();
		DepositBalanceProof proof = depositProof(scenario.accountId(), 100, NOW.plusMillis(500));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<Boolean> first = executor.submit(() -> attemptDeposit(
					start, scenario.accountId(), wishId, proof, NOW.plusSeconds(1)));
			Future<Boolean> second = executor.submit(() -> attemptDeposit(
					start, scenario.accountId(), wishId, proof, NOW.plusSeconds(2)));
			start.countDown();

			assertThat(List.of(
					first.get(10, TimeUnit.SECONDS),
					second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}

		assertThat(wishRepository.findById(wishId).orElseThrow().amount())
				.isEqualTo(KrwAmount.of(10));
		assertThat(countDeposits(scenario.accountId())).isOne();
	}

	@Test
	void observationFailureRollsBackLedgerCaseEpisodeOutboxAndObservationTogether() {
		Scenario scenario = createScenario(100, List.of(new WishSpec("조정 대상", 100, true)));
		UUID wishId = scenario.wishIds().getFirst();
		moneyCommands.deposit(
				scenario.accountId(), wishId, KrwAmount.positive(80),
				depositProof(scenario.accountId(), 100, NOW.plusMillis(500)), NOW.plusSeconds(1));

		assertThatThrownBy(() -> requiredTransaction().executeWithoutResult(status -> {
			observationService.recordSuccess(
					scenario.accountId(), BalanceLookupMethod.USER_REQUESTED,
					KrwAmount.nonNegative(50), NOW.plusSeconds(2));
			throw new ForcedRollback();
		})).isInstanceOf(ForcedRollback.class);

		assertThat(wishRepository.findById(wishId).orElseThrow().amount())
				.isEqualTo(KrwAmount.of(80));
		assertThat(countByAccount("BalanceObservation", scenario.accountId())).isEqualTo(2);
		assertThat(countByAccount("LedgerEvent", scenario.accountId())).isEqualTo(2);
		assertThat(countByAccount("BalanceAdjustmentCase", scenario.accountId())).isZero();
		long outboxCount = requiredTransaction().execute(status -> entityManager.createQuery(
				"select count(outbox) from MismatchNotificationOutbox outbox where outbox.adjustmentCase.accountId = :accountId",
				Long.class)
				.setParameter("accountId", scenario.accountId())
				.getSingleResult());
		assertThat(outboxCount).isZero();
		assertThat(sharedCardRepository.findByWishId(wishId)).isPresent();
	}

	@Test
	void depositRejectsAppLaunchAndAnOldPreDepositSuccessAfterTheLatestAttemptFails() {
		Scenario scenario = createScenario(100, List.of(new WishSpec("조정 대상", 100, false)));
		UUID wishId = scenario.wishIds().getFirst();
		DepositBalanceProof userRequestedProof = DepositBalanceProof.from(
				observationService.recordSuccess(
						scenario.accountId(), BalanceLookupMethod.USER_REQUESTED,
						KrwAmount.nonNegative(100), NOW.plusMillis(100)));

		assertThatThrownBy(() -> moneyCommands.deposit(
				scenario.accountId(), wishId, KrwAmount.positive(10), userRequestedProof,
				NOW.plusMillis(200)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("PRE_DEPOSIT");

		DepositBalanceProof oldSuccess = depositProof(
				scenario.accountId(), 100, NOW.plusMillis(300));
		BalanceObservation failedAttempt = observationService.recordFailure(
				scenario.accountId(), BalanceLookupMethod.PRE_DEPOSIT,
				"TIMEOUT", NOW.plusMillis(400));

		assertThat(failedAttempt.accountLookupVersion())
				.isGreaterThan(oldSuccess.accountLookupVersion());
		assertThatThrownBy(() -> moneyCommands.deposit(
				scenario.accountId(), wishId, KrwAmount.positive(10), oldSuccess,
				NOW.plusMillis(500)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("stale");

		assertThat(wishRepository.findById(wishId).orElseThrow().amount())
				.isEqualTo(KrwAmount.zero());
		long deposits = requiredTransaction().execute(status -> entityManager.createQuery(
				"select count(event) from LedgerEvent event where event.accountId = :accountId and event.type = :type",
				Long.class)
				.setParameter("accountId", scenario.accountId())
				.setParameter("type", LedgerEventType.WISH_DEPOSIT)
				.getSingleResult());
		assertThat(deposits).isZero();
	}

	@Test
	void outerFailureRollsBackWishEditsAndSharedCardProjectionTogether() {
		Scenario scenario = createScenario(100, List.of(new WishSpec("노트북", 100, false)));
		UUID wishId = scenario.wishIds().getFirst();
		wishEdits.changeVisibility(
				scenario.accountId(), wishId, WishVisibility.FRIENDS, NOW.plusSeconds(1));

		assertThatThrownBy(() -> requiredTransaction().executeWithoutResult(status -> {
			wishEdits.changePurpose(
					scenario.accountId(), wishId, "여름 캠프", NOW.plusSeconds(2));
			wishEdits.changeTarget(
					scenario.accountId(), wishId, KrwAmount.positive(150), NOW.plusSeconds(3));
			wishEdits.changeTargetDate(
					scenario.accountId(), wishId, java.time.LocalDate.of(2026, 12, 31),
					NOW.plusSeconds(4));
			wishEdits.changeVisibility(
					scenario.accountId(), wishId, WishVisibility.ACADEMY, NOW.plusSeconds(5));
			throw new ForcedRollback();
		})).isInstanceOf(ForcedRollback.class);

		Wish retained = wishRepository.findById(wishId).orElseThrow();
		SharedCard retainedCard = sharedCardRepository.findByWishId(wishId).orElseThrow();
		assertThat(retained.purpose()).isEqualTo("노트북");
		assertThat(retained.targetAmount()).isEqualTo(KrwAmount.of(100));
		assertThat(retained.targetDate()).isNull();
		assertThat(retained.visibility()).isEqualTo(WishVisibility.FRIENDS);
		assertThat(retainedCard.visibility()).isEqualTo(WishVisibility.FRIENDS);
		assertThat(retainedCard.updatedAt()).isEqualTo(NOW.plusSeconds(1));
	}

	@Test
	void openMismatchRejectsContentAndEveryVisibilityChange() {
		Scenario scenario = createScenario(100, List.of(new WishSpec("노트북", 100, true)));
		UUID wishId = scenario.wishIds().getFirst();
		moneyCommands.deposit(
				scenario.accountId(), wishId, KrwAmount.positive(80),
				depositProof(scenario.accountId(), 100, NOW.plusMillis(500)), NOW.plusSeconds(1));
		observationService.recordSuccess(
				scenario.accountId(), BalanceLookupMethod.USER_REQUESTED,
				KrwAmount.nonNegative(50), NOW.plusSeconds(2));

		assertThatThrownBy(() -> wishEdits.changePurpose(
				scenario.accountId(), wishId, "여름 캠프", NOW.plusSeconds(3)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("balance adjustment");
		assertThatThrownBy(() -> wishEdits.changeTarget(
				scenario.accountId(), wishId, KrwAmount.positive(150), NOW.plusSeconds(3)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("balance adjustment");
		assertThatThrownBy(() -> wishEdits.changeTargetDate(
				scenario.accountId(), wishId, java.time.LocalDate.of(2026, 12, 31),
				NOW.plusSeconds(3)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("balance adjustment");
		assertThatThrownBy(() -> wishEdits.changeVisibility(
				scenario.accountId(), wishId, WishVisibility.ACADEMY, NOW.plusSeconds(3)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("balance adjustment");
		assertThatThrownBy(() -> wishEdits.changeVisibility(
				scenario.accountId(), wishId, WishVisibility.PRIVATE, NOW.plusSeconds(4)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("balance adjustment");

		Wish retained = wishRepository.findById(wishId).orElseThrow();
		assertThat(retained.purpose()).isEqualTo("노트북");
		assertThat(retained.targetAmount()).isEqualTo(KrwAmount.of(100));
		assertThat(retained.targetDate()).isNull();
		assertThat(retained.visibility()).isEqualTo(WishVisibility.FRIENDS);
		assertThat(sharedCardRepository.findByWishId(wishId)).isPresent();
	}

	@Test
	void outerFailureRollsBackBlockAndFriendshipEndTogether() {
		RelationshipScenario relationship = createRelationshipScenario();

		assertThatThrownBy(() -> requiredTransaction().executeWithoutResult(status -> {
			relationshipCommands.block(
					relationship.accountId(), relationship.viewerId(), NOW.plusSeconds(1));
			throw new ForcedRollback();
		})).isInstanceOf(ForcedRollback.class);

		Boolean friendshipCurrent = requiredTransaction().execute(status -> entityManager.createQuery(
				"select count(friendship) from Friendship friendship where friendship.academyId = :academyId and friendship.endedAt is null",
				Long.class)
				.setParameter("academyId", relationship.academyId())
				.getSingleResult() == 1L);
		Long currentBlocks = requiredTransaction().execute(status -> entityManager.createQuery(
				"select count(block) from StudentBlock block where block.releasedAt is null", Long.class)
				.getSingleResult());
		assertThat(friendshipCurrent).isTrue();
		assertThat(currentBlocks).isZero();
	}

	@Test
	void racingBlockAndBefriendCannotResurrectFriendshipAfterRelease() throws Exception {
		RelationshipScenario relationship = createRelationshipScenario();
		requiredTransaction().executeWithoutResult(status -> {
			Friendship friendship = entityManager.createQuery(
					"select friendship from Friendship friendship where friendship.academyId = :academyId",
					Friendship.class)
					.setParameter("academyId", relationship.academyId())
					.getSingleResult();
			friendship.end(NOW.plusMillis(100));
		});

		ExecutorService executor = Executors.newFixedThreadPool(3);
		CountDownLatch friendshipLocked = new CountDownLatch(1);
		CountDownLatch releaseFriendshipLock = new CountDownLatch(1);
		try {
			Future<?> lockHolder = executor.submit(() -> requiredTransaction().executeWithoutResult(status -> {
				entityManager.createQuery(
						"select friendship from Friendship friendship where friendship.academyId = :academyId",
						Friendship.class)
						.setParameter("academyId", relationship.academyId())
						.setLockMode(LockModeType.PESSIMISTIC_WRITE)
						.getSingleResult();
				friendshipLocked.countDown();
				await(releaseFriendshipLock);
			}));
			assertThat(friendshipLocked.await(5, TimeUnit.SECONDS)).isTrue();

			Future<Boolean> befriend = executor.submit(() -> attemptBefriend(relationship));
			assertThatThrownBy(() -> befriend.get(200, TimeUnit.MILLISECONDS))
					.isInstanceOf(TimeoutException.class);
			Future<StudentBlock> block = executor.submit(() -> relationshipCommands.block(
					relationship.accountId(), relationship.viewerId(), NOW.plusSeconds(2)));
			Thread.sleep(200);
			releaseFriendshipLock.countDown();

			lockHolder.get(10, TimeUnit.SECONDS);
			block.get(10, TimeUnit.SECONDS);
			befriend.get(10, TimeUnit.SECONDS);
		} finally {
			releaseFriendshipLock.countDown();
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}

		relationshipCommands.releaseBlock(
				relationship.accountId(), relationship.viewerId(), NOW.plusSeconds(3));

		Long currentFriendships = requiredTransaction().execute(status -> entityManager.createQuery(
				"select count(friendship) from Friendship friendship where friendship.academyId = :academyId and friendship.endedAt is null",
				Long.class)
				.setParameter("academyId", relationship.academyId())
				.getSingleResult());
		assertThat(currentFriendships).isZero();
	}

	@Test
	void concurrentTransfersSerializeOnTheAccountAndOnlyOneCanSpendTheSameFunds()
			throws Exception {
		Scenario scenario = createScenario(100, List.of(
				new WishSpec("출발", 100, false),
				new WishSpec("도착 A", 100, false),
				new WishSpec("도착 B", 100, false)));
		UUID sourceId = scenario.wishIds().get(0);
		UUID destinationA = scenario.wishIds().get(1);
		UUID destinationB = scenario.wishIds().get(2);
		moneyCommands.deposit(
				scenario.accountId(), sourceId, KrwAmount.positive(50),
				depositProof(scenario.accountId(), 100, NOW.plusMillis(500)), NOW.plusSeconds(1));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Boolean>> attempts = new ArrayList<>();
			attempts.add(executor.submit(() -> attemptTransfer(
					start, scenario.accountId(), sourceId, destinationA, NOW.plusSeconds(2))));
			attempts.add(executor.submit(() -> attemptTransfer(
					start, scenario.accountId(), sourceId, destinationB, NOW.plusSeconds(3))));
			start.countDown();
			List<Boolean> outcomes = List.of(
					attempts.get(0).get(10, TimeUnit.SECONDS),
					attempts.get(1).get(10, TimeUnit.SECONDS));

			assertThat(outcomes).containsExactlyInAnyOrder(true, false);
		} finally {
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}

		assertThat(wishRepository.findById(sourceId).orElseThrow().amount())
				.isEqualTo(KrwAmount.of(10));
		List<KrwAmount> destinationAmounts = List.of(
				wishRepository.findById(destinationA).orElseThrow().amount(),
				wishRepository.findById(destinationB).orElseThrow().amount());
		assertThat(destinationAmounts).containsExactlyInAnyOrder(
				KrwAmount.of(40), KrwAmount.zero());
		long transferCount = requiredTransaction().execute(status -> entityManager.createQuery(
				"select count(event) from LedgerEvent event where event.type = :type and event.accountId = :accountId",
				Long.class)
				.setParameter("type", LedgerEventType.WISH_TRANSFER)
				.setParameter("accountId", scenario.accountId())
				.getSingleResult());
		assertThat(transferCount).isOne();
	}

	private boolean attemptTransfer(
			CountDownLatch start,
			UUID accountId,
			UUID sourceId,
			UUID destinationId,
			Instant occurredAt) throws InterruptedException {
		start.await();
		try {
			moneyCommands.transfer(
					accountId, sourceId, destinationId, KrwAmount.positive(40), occurredAt);
			return true;
		} catch (IllegalArgumentException expectedOverspend) {
			return false;
		}
	}

	private boolean attemptDeposit(
			CountDownLatch start,
			UUID accountId,
			UUID wishId,
			DepositBalanceProof proof,
			Instant occurredAt) throws InterruptedException {
		start.await();
		try {
			moneyCommands.deposit(accountId, wishId, KrwAmount.positive(10), proof, occurredAt);
			return true;
		} catch (IllegalStateException expectedReplay) {
			return false;
		}
	}

	private boolean attemptBefriend(RelationshipScenario relationship) {
		try {
			relationshipCommands.befriend(
					relationship.viewerAccountId(), relationship.ownerId(), NOW.plusSeconds(1));
			return true;
		} catch (IllegalStateException expectedBlock) {
			return false;
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for relationship race");
			}
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Relationship race was interrupted", interrupted);
		}
	}

	private Scenario createScenario(long actualBalance, List<WishSpec> wishSpecs) {
		return requiredTransaction().execute(status -> {
			Academy academy = new Academy(UUID.randomUUID(), "트랜잭션 학원");
			Student student = new Student(UUID.randomUUID(), "학생", 15);
			CardBalanceAccount account = CardBalanceAccount.open(student.id(), academy.id(), NOW);
			entityManager.persist(academy);
			entityManager.persist(student);
			entityManager.persist(account);
			entityManager.flush();
			observationService.recordSuccess(
					account.id(), BalanceLookupMethod.USER_REQUESTED,
					KrwAmount.nonNegative(actualBalance), NOW);
			List<UUID> wishIds = new ArrayList<>();
			for (WishSpec spec : wishSpecs) {
				Wish wish = Wish.create(account.id(), academy.id(), spec.purpose(),
						KrwAmount.positive(spec.target()), NOW);
				if (spec.shared()) {
					wish.changeVisibility(WishVisibility.FRIENDS);
				}
				entityManager.persist(wish);
				wishIds.add(wish.id());
			}
			entityManager.flush();
			return new Scenario(account.id(), wishIds);
		});
	}

	private RelationshipScenario createRelationshipScenario() {
		return requiredTransaction().execute(status -> {
			Academy academy = new Academy(UUID.randomUUID(), "관계 학원");
			Student owner = new Student(UUID.randomUUID(), "소유자", 15);
			Student viewer = new Student(UUID.randomUUID(), "열람자", 16);
			AcademyMembership ownerMembership = new AcademyMembership(owner.id(), academy.id(), NOW);
			AcademyMembership viewerMembership = new AcademyMembership(viewer.id(), academy.id(), NOW);
			CardBalanceAccount account = CardBalanceAccount.open(owner.id(), academy.id(), NOW);
			CardBalanceAccount viewerAccount = CardBalanceAccount.open(viewer.id(), academy.id(), NOW);
			entityManager.persist(academy);
			entityManager.persist(owner);
			entityManager.persist(viewer);
			entityManager.persist(ownerMembership);
			entityManager.persist(viewerMembership);
			entityManager.persist(account);
			entityManager.persist(viewerAccount);
			entityManager.persist(new Friendship(ownerMembership, viewerMembership, NOW));
			entityManager.flush();
			return new RelationshipScenario(
					account.id(), viewerAccount.id(), academy.id(), owner.id(), viewer.id());
		});
	}

	private TransactionTemplate requiredTransaction() {
		return new TransactionTemplate(transactionManager);
	}

	private DepositBalanceProof depositProof(UUID accountId, long balance, Instant observedAt) {
		BalanceObservation observation = observationService.recordSuccess(
				accountId, BalanceLookupMethod.PRE_DEPOSIT,
				KrwAmount.nonNegative(balance), observedAt);
		return DepositBalanceProof.from(observation);
	}

	private long countByAccount(String entityName, UUID accountId) {
		return requiredTransaction().execute(status -> entityManager.createQuery(
				"select count(entity) from " + entityName
						+ " entity where entity.accountId = :accountId", Long.class)
				.setParameter("accountId", accountId)
				.getSingleResult());
	}

	private long countDeposits(UUID accountId) {
		return requiredTransaction().execute(status -> entityManager.createQuery(
				"select count(event) from LedgerEvent event where event.accountId = :accountId and event.type = :type",
				Long.class)
				.setParameter("accountId", accountId)
				.setParameter("type", LedgerEventType.WISH_DEPOSIT)
				.getSingleResult());
	}

	private record WishSpec(String purpose, long target, boolean shared) {
	}

	private record Scenario(UUID accountId, List<UUID> wishIds) {
	}

	private record RelationshipScenario(
			UUID accountId,
			UUID viewerAccountId,
			UUID academyId,
			UUID ownerId,
			UUID viewerId) {
	}

	private static final class ForcedRollback extends RuntimeException {
	}
}
