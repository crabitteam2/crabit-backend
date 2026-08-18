package com.crabit.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.crabit.backend.e2e.SeedFixtureCatalog;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

abstract class SharedCardApiIntegrationSupport extends WishApiIntegrationSupport {

	protected static final String SHARED_CARDS_PATH =
			"/v1/academies/" + SeedFixtureCatalog.PRIMARY_ACADEMY_ID + "/shared-cards";
	protected static final UUID HIDDEN_ACCOUNT_ID =
			UUID.fromString("00000000-0000-0000-0000-000000009301");
	protected static final UUID HIDDEN_WISH_ID =
			UUID.fromString("00000000-0000-0000-0000-000000009401");
	protected static final UUID HIDDEN_CARD_ID =
			UUID.fromString("00000000-0000-0000-0000-000000009801");
	protected static final UUID REVERSE_BLOCK_ID =
			UUID.fromString("00000000-0000-0000-0000-000000009701");

	@AfterEach
	void removeAdditionalSharedCardRows() {
		jdbc.update("DELETE FROM student_block WHERE id = ?", REVERSE_BLOCK_ID);
		jdbc.update("DELETE FROM shared_card WHERE id = ?", HIDDEN_CARD_ID);
		jdbc.update("DELETE FROM wish WHERE id = ?", HIDDEN_WISH_ID);
		jdbc.update("DELETE FROM card_balance_account WHERE id = ?", HIDDEN_ACCOUNT_ID);
	}

	protected String cardIdForWish(UUID wishId) {
		return jdbc.queryForObject(
				"SELECT id::text FROM shared_card WHERE wish_id = ?", String.class, wishId);
	}

	protected String cardPath(String cardId) {
		return SHARED_CARDS_PATH + "/" + cardId;
	}

	protected ResultActions listAs(String token) throws Exception {
		return asToken(token, get(SHARED_CARDS_PATH));
	}

	protected ResultActions getAs(String token, String cardId) throws Exception {
		return asToken(token, get(cardPath(cardId)));
	}

	protected ResultActions completeCamp(String key) throws Exception {
		return asOwner(post(WISHES_PATH + "/" + SeedFixtureCatalog.CAMP_WISH_ID + "/completion")
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0}"));
	}

	protected void insertHiddenFriendsCard(Instant updatedAt) {
		jdbc.update("""
				INSERT INTO card_balance_account
				    (id, student_id, academy_id, opened_at, closed_at, balance_lookup_version, version)
				VALUES (?, ?, ?, ?, NULL, 0, 0)
				""", HIDDEN_ACCOUNT_ID, SeedFixtureCatalog.NONFRIEND_ID,
				SeedFixtureCatalog.PRIMARY_ACADEMY_ID, Timestamp.from(SeedFixtureCatalog.FIXTURE_TIME));
		jdbc.update("""
				INSERT INTO wish
				    (id, account_id, academy_id, purpose, target_amount, wish_amount, state,
				     visibility, created_at, updated_at, target_date, completed_at, deleted_at,
				     deleted_purpose_snapshot, version)
				VALUES (?, ?, ?, '숨겨진 위시', 100000, 10000, 'IN_PROGRESS', 'FRIENDS',
				        ?, ?, NULL, NULL, NULL, NULL, 0)
				""", HIDDEN_WISH_ID, HIDDEN_ACCOUNT_ID, SeedFixtureCatalog.PRIMARY_ACADEMY_ID,
				Timestamp.from(SeedFixtureCatalog.FIXTURE_TIME), Timestamp.from(updatedAt));
		jdbc.update("""
				INSERT INTO shared_card (id, wish_id, kind, visibility, updated_at)
				VALUES (?, ?, 'PROGRESS', 'FRIENDS', ?)
				""", HIDDEN_CARD_ID, HIDDEN_WISH_ID, Timestamp.from(updatedAt));
	}
}
