package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OpenApiExamplesTest {

	private static final Set<String> REQUIRED_EXAMPLE_SUMMARIES = Set.of(
			"unknown-balance",
			"failed-refresh-known-balance",
			"empty-page",
			"wish-created-private-zero",
			"idempotent-replay",
			"balance-sync-failed",
			"balance-mismatch-locked",
			"deleted-wish-hidden",
			"card-balance-change",
			"card-balance-change-page",
			"account-card-balance-change",
			"account-card-balance-change-page",
			"account-fund-movement",
			"account-wish-transfer",
			"account-wish-transfer-page",
			"wish-fund-movement",
			"wish-transfer-source-page",
			"wish-transfer-destination-page",
			"deleted-wish-history-page",
			"deleted-wish-empty-history-page",
			"purpose-ascii-boundaries",
			"purpose-decomposed-nfc",
			"purpose-nbsp-boundaries",
			"purpose-empty-after-boundary-removal",
			"purpose-prohibited-unicode-categories",
			"invalid-expected-version",
			"invalid-source-expected-version",
			"invalid-destination-expected-version",
			"invalid-if-match-version",
			"shared-progress-adjustment-false",
			"shared-progress-adjustment-true",
			"shared-completion-without-adjustment-field",
			"friend-search-all-relationship-states",
			"friend-search-empty-page",
			"current-friend-page",
			"current-friend-empty-page",
			"friend-request-created-pending",
			"sent-pending-friend-request-page",
			"received-pending-friend-request-page",
			"pending-friend-request-empty-page",
			"friend-request-canceled",
			"friend-request-accepted-friendship",
			"friend-request-rejected",
			"student-block-created",
			"active-student-block-page",
			"active-student-block-empty-page",
			"friend-management-malformed-uuid",
			"friend-management-malformed-nickname",
			"friend-management-malformed-limit",
			"friend-management-malformed-cursor",
			"friend-management-auth-required",
			"friend-management-forbidden",
			"friend-management-academy-not-found",
			"friend-management-cross-academy-student-hidden",
			"friend-management-bilateral-block-student-hidden",
			"friend-management-friendship-not-found",
			"friend-management-unauthorized-request-hidden",
			"friend-management-student-block-not-found",
			"friend-management-self-relationship-conflict",
			"friend-management-already-friends-conflict",
			"friend-management-request-already-pending-conflict",
			"friend-management-incoming-request-pending-conflict",
			"friend-management-request-not-pending-conflict",
			"friend-management-request-not-actionable-conflict",
			"friend-management-student-block-active-conflict");

	private static final Set<String> FORBIDDEN_SHARED_CARD_FIELDS = Set.of(
			"wishId", "cardBalanceAccountId", "studentId", "physicalCardId", "physicalCardNumber",
			"actualCardBalance", "ledgerAvailableBalance", "displayAvailableBalance", "amount",
			"fundMovements", "cardBalanceChanges", "adjustmentStatus");

	private static Map<String, Object> document;
	private static Map<String, Object> examples;

	@BeforeAll
	static void parseContract() throws IOException {
		try (InputStream input = Files.newInputStream(Path.of("api", "openapi.yaml"))) {
			document = map(new Yaml().load(input));
		}
		examples = map(path("components", "examples"));
	}

	@Test
	void containsEveryApprovedNamedExampleAndValidatesEachAgainstItsSchema() {
		assertThat(examples.values()).extracting(example -> map(example).get("summary"))
				.containsAll(REQUIRED_EXAMPLE_SUMMARIES);

		examples.forEach((name, rawExample) -> {
			Map<String, Object> example = map(rawExample);
			String schemaRef = Objects.toString(example.get("x-schema-ref"), null);
			assertThat(schemaRef).as(name + " schema binding").startsWith("#/components/schemas/");
			assertThat(validate(example.get("value"), map(resolve(schemaRef)), "$"))
					.as(name + " schema validation").isEmpty();
		});
	}

	@Test
	void distinguishesUnknownBalanceFromAFailedRefreshOfKnownValues() {
		Map<String, Object> unknownPage = value("UnknownBalancePage");
		Map<String, Object> unknown = map(list(unknownPage.get("items")).getFirst());
		assertThat(unknown).containsEntry("balanceKnowledge", "UNKNOWN")
				.containsEntry("actualCardBalance", null)
				.containsEntry("ledgerAvailableBalance", null)
				.containsEntry("displayAvailableBalance", null)
				.containsEntry("unresolvedShortage", null);

		Map<String, Object> failedKnown = value("FailedRefreshKnownBalance");
		assertThat(failedKnown).containsEntry("balanceKnowledge", "KNOWN")
				.containsEntry("lastRefreshStatus", "FAILED");
		assertThat(failedKnown.get("actualCardBalance")).isNotNull();
		assertThat(failedKnown.get("ledgerAvailableBalance")).isNotNull();
		assertThat(failedKnown.get("displayAvailableBalance")).isNotNull();
	}

	@Test
	void makesOnlyBalanceSyncFailureRetryableAndShowsIdempotentReplayExplicitly() {
		Map<String, Object> syncError = map(value("BalanceSyncFailed").get("error"));
		Map<String, Object> mismatchError = map(value("BalanceMismatchLocked").get("error"));
		Map<String, Object> deletedError = map(value("DeletedWishHidden").get("error"));
		assertThat(syncError).containsEntry("code", "BALANCE_SYNC_FAILED").containsEntry("retryable", true);
		assertThat(mismatchError).containsEntry("code", "BALANCE_MISMATCH_LOCKED").containsEntry("retryable", false);
		assertThat(deletedError).containsEntry("code", "WISH_NOT_FOUND").containsEntry("retryable", false);
		assertThat(map(example("IdempotentReplay").get("x-response-headers")))
				.containsEntry("Idempotency-Replayed", true);
	}

	@Test
	void demonstratesAcceptedPurposeInputsAndTheirNormalizedOutputs() {
		Map<String, Object> ascii = value("PurposeAsciiBoundaries");
		Map<String, Object> decomposed = value("PurposeDecomposedNfc");
		Map<String, Object> nbsp = value("PurposeNbspBoundaries");

		assertThat(ascii.get("purpose").toString()).startsWith(" ").endsWith(" ");
		assertThat(example("PurposeAsciiBoundaries")).containsEntry("x-normalized-purpose", "새 노트북");

		String decomposedPurpose = decomposed.get("purpose").toString();
		assertThat(Normalizer.isNormalized(decomposedPurpose, Normalizer.Form.NFC)).isFalse();
		assertThat(example("PurposeDecomposedNfc")).containsEntry("x-normalized-purpose", "Café");

		String nbspPurpose = nbsp.get("purpose").toString();
		assertThat(nbspPurpose.codePointAt(0)).isEqualTo(0x00A0);
		assertThat(nbspPurpose.codePointBefore(nbspPurpose.length())).isEqualTo(0x00A0);
		assertThat(example("PurposeNbspBoundaries")).containsEntry("x-normalized-purpose", "비상금 계획");

		for (String name : List.of("PurposeAsciiBoundaries", "PurposeDecomposedNfc", "PurposeNbspBoundaries")) {
			Map<String, Object> request = value(name);
			assertThat(validate(request, schema("CreateWishRequest"), "$"))
					.as(name + " request input").isEmpty();
			assertThat(validate(example(name).get("x-normalized-purpose"), schema("Purpose"), "$"))
					.as(name + " normalized output").isEmpty();
		}
	}

	@Test
	void documentsEveryPurposeRejectionCategoryAndNegativeVersionField() {
		assertThat(example("InvalidPurposeEmptyAfterBoundaries"))
				.containsEntry("x-request-purpose", " \u00A0 ");
		assertThat(list(example("InvalidPurposeUnicodeCategories").get("x-rejected-unicode-categories")))
				.containsExactly("Cc", "Cf", "Zl", "Zp");
		assertInvalidError("InvalidPurposeEmptyAfterBoundaries", "INVALID_PURPOSE", "purpose");
		assertInvalidError("InvalidPurposeUnicodeCategories", "INVALID_PURPOSE", "purpose");
		assertInvalidError("InvalidExpectedVersion", "INVALID_VERSION", "expectedVersion");
		assertInvalidError("InvalidSourceExpectedVersion", "INVALID_VERSION", "sourceExpectedVersion");
		assertInvalidError("InvalidDestinationExpectedVersion", "INVALID_VERSION", "destinationExpectedVersion");
		assertInvalidError("InvalidIfMatchVersion", "INVALID_VERSION", "If-Match");
	}

	@Test
	void requiresTheReadTimeBooleanOnProgressAndRejectsItOnCompletion() {
		Map<String, Object> progressFalse = value("SharedProgressAdjustmentFalse");
		Map<String, Object> progressTrue = value("SharedProgressAdjustmentTrue");
		Map<String, Object> completion = value("SharedCompletion");
		assertThat(progressFalse).containsEntry("balanceAdjustmentInProgress", false);
		assertThat(progressTrue).containsEntry("balanceAdjustmentInProgress", true);
		assertThat(completion).doesNotContainKeys("balanceAdjustmentInProgress", "adjustmentStatus");

		Map<String, Object> missingBoolean = new LinkedHashMap<>(progressFalse);
		missingBoolean.remove("balanceAdjustmentInProgress");
		assertThat(validate(missingBoolean, schema("ProgressSharedCard"), "$"))
				.as("Progress must require balanceAdjustmentInProgress").isNotEmpty();

		Map<String, Object> staleEnum = new LinkedHashMap<>(progressTrue);
		staleEnum.put("adjustmentStatus", "OPEN");
		assertThat(validate(staleEnum, schema("ProgressSharedCard"), "$"))
				.as("the replaced adjustmentStatus contract must be rejected").isNotEmpty();

		Map<String, Object> completionWithBoolean = new LinkedHashMap<>(completion);
		completionWithBoolean.put("balanceAdjustmentInProgress", true);
		assertThat(validate(completionWithBoolean, schema("CompletionSharedCard"), "$"))
				.as("Completion must reject the Progress-only boolean").isNotEmpty();
	}

	@Test
	void keepsSharedCardsPubliclyUsefulWithoutLeakingOwnerState() {
		for (String name : List.of("SharedProgressAdjustmentFalse", "SharedProgressAdjustmentTrue", "SharedCompletion")) {
			Map<String, Object> card = value(name);
			assertThat(card).as(name).containsKey("targetAmount");
			Set<String> observedFields = new HashSet<>();
			collectFieldNames(card, observedFields);
			assertThat(observedFields).as(name + " privacy").doesNotContainAnyElementsOf(FORBIDDEN_SHARED_CARD_FIELDS);
		}
	}

	@Test
	void preservesOneCardChangeIdentityAndAdjustmentLinkAcrossBothHistoryViews() {
		Map<String, Object> cardChange = value("CardBalanceChangeExample");
		Map<String, Object> accountChange = value("AccountCardBalanceChangeExample");

		assertThat(cardChange.get("eventId")).isEqualTo(accountChange.get("eventId"));
		assertThat(cardChange.get("observationId")).isEqualTo(accountChange.get("observationId"));
		assertThat(cardChange.get("actualCardBalanceDelta"))
				.isEqualTo(accountChange.get("actualCardBalanceDelta"));
		assertThat(cardChange.get("balanceAdjustment"))
				.isEqualTo(accountChange.get("balanceAdjustment"));
		assertThat(accountChange).containsEntry("accountAvailableBalanceAfter", -25000);

		Map<String, Object> zeroDelta = new LinkedHashMap<>(cardChange);
		zeroDelta.put("actualCardBalanceDelta", 0);
		assertThat(validate(zeroDelta, schema("CardBalanceChange"), "$"))
				.as("zero-delta successful observations are not money-history items")
				.isNotEmpty();
	}

	@Test
	void representsOneTransferAsOneAccountItemAndTwoOppositeWishEffects() {
		Map<String, Object> accountTransfer = value("AccountWishTransferExample");
		Map<String, Object> sourcePage = value("WishTransferSourcePageExample");
		Map<String, Object> destinationPage = value("WishTransferDestinationPageExample");
		Map<String, Object> source = map(list(sourcePage.get("items")).getFirst());
		Map<String, Object> destination = map(list(destinationPage.get("items")).getFirst());

		assertThat(source.get("eventId")).isEqualTo(accountTransfer.get("eventId"));
		assertThat(destination.get("eventId")).isEqualTo(accountTransfer.get("eventId"));
		assertThat(accountTransfer).containsEntry("accountAvailableBalanceDelta", 0)
				.containsEntry("amount", 30000);
		assertThat(source).containsEntry("direction", "SOURCE")
				.containsEntry("wishAmountDelta", -30000);
		assertThat(destination).containsEntry("direction", "DESTINATION")
				.containsEntry("wishAmountDelta", 30000);
		assertThat(map(source.get("counterpartyWish")).get("wishId"))
				.isEqualTo(map(destinationPage.get("wish")).get("wishId"));
		assertThat(map(destination.get("counterpartyWish")).get("wishId"))
				.isEqualTo(map(sourcePage.get("wish")).get("wishId"));
	}

	@Test
	void keepsOwnedTombstoneHistoryReadableWithoutFabricatingADetailLink() {
		Map<String, Object> deletedPage = value("DeletedWishHistoryPageExample");
		Map<String, Object> deletedSubject = map(deletedPage.get("wish"));
		Map<String, Object> deletionEvent = map(list(deletedPage.get("items")).getFirst());
		Map<String, Object> adjustment = map(deletionEvent.get("balanceAdjustment"));

		assertThat(deletedSubject).containsEntry("displayPurpose", "새 노트북")
				.containsEntry("deletedWish", true)
				.containsEntry("detailAvailable", false);
		assertThat(deletionEvent).containsEntry("eventType", "WISH_DELETION_RETURN")
				.containsEntry("wishPurposeSnapshot", "새 노트북")
				.containsEntry("wishAmountAfter", 0);
		assertThat(adjustment).containsEntry("eventRole", "RESOLUTION")
				.containsEntry("sequenceNumber", 2);

		Set<String> observedFields = new HashSet<>();
		collectFieldNames(deletedPage, observedFields);
		assertThat(observedFields).doesNotContain("href", "url", "detailPath");

		Map<String, Object> emptyDeletedPage = value("EmptyDeletedWishHistoryPageExample");
		assertThat(map(emptyDeletedPage.get("wish"))).containsEntry("deletedWish", true)
				.containsEntry("detailAvailable", false);
		assertThat(list(emptyDeletedPage.get("items"))).isEmpty();
		assertThat(emptyDeletedPage).containsEntry("nextCursor", null);
	}

	private static Map<String, Object> example(String name) {
		return map(examples.get(name));
	}

	private static Map<String, Object> value(String name) {
		return map(example(name).get("value"));
	}

	private static void assertInvalidError(String name, String code, String field) {
		Map<String, Object> error = map(value(name).get("error"));
		assertThat(error).containsEntry("code", code).containsEntry("retryable", false);
		if ("INVALID_VERSION".equals(code)) {
			assertThat(example(name)).containsEntry("x-request-value", -1);
		}
		assertThat(list(error.get("fieldErrors"))).singleElement()
				.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
				.containsEntry("field", field);
	}

	private static Map<String, Object> schema(String name) {
		return map(path("components", "schemas", name));
	}

	private static List<String> validate(Object value, Map<String, Object> schema, String location) {
		List<String> errors = new ArrayList<>();
		if (schema.containsKey("$ref")) {
			Object resolved = resolve(schema.get("$ref").toString());
			if (resolved == null) {
				return List.of(location + " unresolved ref " + schema.get("$ref"));
			}
			return validate(value, map(resolved), schema.get("$ref").toString());
		}

		if (schema.containsKey("oneOf")) {
			long matches = list(schema.get("oneOf")).stream()
					.map(OpenApiExamplesTest::map)
					.filter(candidate -> validate(value, candidate, location).isEmpty())
					.count();
			if (matches != 1) {
				errors.add(location + " matched " + matches + " oneOf branches");
			}
		}
		if (schema.containsKey("anyOf")) {
			boolean matches = list(schema.get("anyOf")).stream()
					.map(OpenApiExamplesTest::map)
					.anyMatch(candidate -> validate(value, candidate, location).isEmpty());
			if (!matches) {
				errors.add(location + " matched no anyOf branch");
			}
		}
		if (schema.containsKey("allOf")) {
			list(schema.get("allOf")).stream()
					.map(OpenApiExamplesTest::map)
					.forEach(candidate -> errors.addAll(validate(value, candidate, location)));
		}
		if (schema.containsKey("not") && validate(value, map(schema.get("not")), location).isEmpty()) {
			errors.add(location + " matched a forbidden schema");
		}

		Object rawType = schema.get("type");
		if (rawType != null && !matchesType(value, rawType)) {
			errors.add(location + " expected type " + rawType + " but was "
					+ (value == null ? "null" : value.getClass().getSimpleName()));
			return errors;
		}
		if (schema.containsKey("enum") && !list(schema.get("enum")).contains(value)) {
			errors.add(location + " not in enum " + schema.get("enum"));
		}
		if (schema.containsKey("const") && !Objects.equals(schema.get("const"), value)) {
			errors.add(location + " expected const " + schema.get("const"));
		}

		if (value instanceof Map<?, ?> rawObject) {
			Map<String, Object> object = map(rawObject);
			for (Object required : list(schema.get("required"))) {
				if (!object.containsKey(required.toString())) {
					errors.add(location + " missing " + required);
				}
			}
			Map<String, Object> properties = map(schema.get("properties"));
			object.forEach((name, child) -> {
				if (properties.containsKey(name)) {
					errors.addAll(validate(child, map(properties.get(name)), location + "." + name));
				} else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
					errors.add(location + " rejects additional property " + name);
				}
			});
		}

		if (value instanceof List<?> array) {
			if (schema.containsKey("items")) {
				for (int index = 0; index < array.size(); index++) {
					errors.addAll(validate(array.get(index), map(schema.get("items")), location + "[" + index + "]"));
				}
			}
			if (Boolean.TRUE.equals(schema.get("uniqueItems")) && new HashSet<>(array).size() != array.size()) {
				errors.add(location + " contains duplicate items");
			}
		}

		if (value instanceof String string) {
			if (schema.get("minLength") instanceof Number minimum && string.codePointCount(0, string.length()) < minimum.intValue()) {
				errors.add(location + " is shorter than " + minimum);
			}
			if (schema.get("maxLength") instanceof Number maximum && string.codePointCount(0, string.length()) > maximum.intValue()) {
				errors.add(location + " is longer than " + maximum);
			}
			if (schema.get("pattern") instanceof String pattern && !Pattern.compile(pattern).matcher(string).find()) {
				errors.add(location + " does not match " + pattern);
			}
			validateFormat(string, schema.get("format"), location, errors);
		}

		if (isInteger(value)) {
			BigDecimal number = new BigDecimal(value.toString());
			if (schema.get("minimum") instanceof Number minimum
					&& number.compareTo(new BigDecimal(minimum.toString())) < 0) {
				errors.add(location + " is below minimum " + minimum);
			}
			if (schema.get("maximum") instanceof Number maximum
					&& number.compareTo(new BigDecimal(maximum.toString())) > 0) {
				errors.add(location + " is above maximum " + maximum);
			}
		}
		return errors;
	}

	private static boolean matchesType(Object value, Object rawType) {
		if (rawType instanceof List<?> allowed) {
			return allowed.stream().anyMatch(type -> matchesType(value, type));
		}
		return switch (rawType.toString()) {
			case "null" -> value == null;
			case "object" -> value instanceof Map<?, ?>;
			case "array" -> value instanceof List<?>;
			case "string" -> value instanceof String;
			case "integer" -> isInteger(value);
			case "number" -> value instanceof Number;
			case "boolean" -> value instanceof Boolean;
			default -> false;
		};
	}

	private static boolean isInteger(Object value) {
		return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
				|| value instanceof java.math.BigInteger;
	}

	private static void validateFormat(String value, Object rawFormat, String location, List<String> errors) {
		if (rawFormat == null) {
			return;
		}
		try {
			switch (rawFormat.toString()) {
				case "uuid" -> UUID.fromString(value);
				case "date" -> LocalDate.parse(value);
				case "date-time" -> OffsetDateTime.parse(value);
				default -> { }
			}
		} catch (RuntimeException invalid) {
			errors.add(location + " is not a valid " + rawFormat);
		}
	}

	private static void collectFieldNames(Object value, Set<String> names) {
		if (value instanceof Map<?, ?> object) {
			object.forEach((key, child) -> {
				names.add(key.toString());
				collectFieldNames(child, names);
			});
		} else if (value instanceof List<?> array) {
			array.forEach(child -> collectFieldNames(child, names));
		}
	}

	private static Object path(String... segments) {
		Object current = document;
		for (String segment : segments) {
			current = map(current).get(segment);
		}
		return current;
	}

	private static Object resolve(String ref) {
		Object current = document;
		for (String encoded : ref.substring(2).split("/")) {
			String segment = encoded.replace("~1", "/").replace("~0", "~");
			if (!(current instanceof Map<?, ?> currentMap) || !currentMap.containsKey(segment)) {
				return null;
			}
			current = currentMap.get(segment);
		}
		return current;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value == null ? Map.of() : (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return value == null ? List.of() : (List<Object>) value;
	}
}
