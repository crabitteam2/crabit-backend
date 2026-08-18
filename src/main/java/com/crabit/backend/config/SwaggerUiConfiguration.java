package com.crabit.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.customizers.OpenApiCustomizer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
		name = "crabit.documentation.enabled",
		havingValue = "true",
		matchIfMissing = true)
public class SwaggerUiConfiguration {

	public static final String WISH_TAG = "Wishes";
	public static final String SEED_BEARER = "SeedBearer";

	private static final String DEPOSIT =
			"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/deposits";
	private static final String WITHDRAWAL =
			"/v1/card-balance-accounts/{cardBalanceAccountId}/wishes/{wishId}/withdrawals";
	private static final String TRANSFER =
			"/v1/card-balance-accounts/{cardBalanceAccountId}/transfers";

	@Bean
	OpenAPI wishOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("Crabit Wish API")
						.description("The generated implementation document for the seven Wish lifecycle "
								+ "operations. Amounts are integer Korean won, mutations use optimistic "
								+ "versions, unowned or tombstoned resources are hidden behind "
								+ "resource-specific 404 responses, and this generated projection is "
								+ "distinct from api/openapi.yaml."))
				.addTagsItem(new Tag()
						.name(WISH_TAG)
						.description("Create, query, edit, complete, abandon, and tombstone Wishes "
								+ "owned through a Card Balance Account."))
				.components(new Components().addSecuritySchemes(SEED_BEARER,
						new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("opaque-seed-token")
								.description("Opaque deterministic principal token. A known Seed token "
										+ "identifies either a student or an authenticated non-student staff "
										+ "principal. Token issuance and refresh are outside this contract.")));
	}

	@Bean
	OpenApiCustomizer canonicalWishMovementCustomizer() {
		return openApi -> {
			registerCanonicalMovementComponents(openApi.getComponents());
			removeGeneratedSiblingsFromCanonicalRefs(openApi.getComponents());
			canonicalizeMutation(openApi, DEPOSIT, true,
					"Deposit Card Balance Account funds into one Wish",
					"Performs PRE_DEPOSIT lookup internally. Provider failure leaves the Wish "
							+ "unchanged; a persisted mismatch observation locks and rejects only this "
							+ "deposit.",
					"DepositConflict", "InvalidAmountOrVersion", true);
			canonicalizeMutation(openApi, WITHDRAWAL, true,
					"Withdraw funds from one Wish", null,
					"WithdrawalConflict", "InvalidAmountOrVersion", false);
			canonicalizeTransfer(openApi);
		};
	}

	private static void registerCanonicalMovementComponents(Components components) {
		components.addSchemas("Uuid", new StringSchema().format("uuid"));
		components.addSchemas("KrwPositive", new IntegerSchema()
				.format("int64")
				.minimum(BigDecimal.ONE)
				.maximum(BigDecimal.valueOf(9_007_199_254_740_991L)));
		components.addSchemas("KrwNonNegative", new IntegerSchema()
				.format("int64")
				.minimum(BigDecimal.ZERO)
				.maximum(BigDecimal.valueOf(9_007_199_254_740_991L)));
		components.addSchemas("WishVersion", new IntegerSchema()
				.format("int64")
				.minimum(BigDecimal.ZERO));
		components.addSchemas("Purpose", new StringSchema().minLength(1).maxLength(200)
				.pattern("^(?!\\p{Zs})(?!.*\\p{Zs}$)(?!.*[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]).+$"));
		components.addSchemas("WishState", new StringSchema()._enum(List.of(
				"IN_PROGRESS", "AMOUNT_REACHED", "COMPLETED", "ABANDONED")));
		components.addSchemas("WishVisibility", new StringSchema()._enum(List.of(
				"PRIVATE", "FRIENDS", "ACADEMY")));
		components.addSchemas("UtcInstant", new StringSchema()
				.format("date-time")
				.pattern("Z$"));

		components.addParameters("CardBalanceAccountId", new Parameter()
				.name("cardBalanceAccountId")
				.in("path")
				.required(true)
				.schema(refSchema("Uuid")));
		components.addParameters("WishId", new Parameter()
				.name("wishId")
				.in("path")
				.required(true)
				.schema(refSchema("Uuid")));
		components.addParameters("IdempotencyKey", new Parameter()
				.name("Idempotency-Key")
				.in("header")
				.required(true)
				.description("Permanent per-student namespace; reuse is valid only for the same "
						+ "operation, target, and canonical request.")
				.schema(new StringSchema().minLength(1).maxLength(200)));
		components.addHeaders("IdempotencyReplayed", new Header()
				.description("True only when the original status and body are replayed for an "
						+ "identical request.")
				.schema(new BooleanSchema()));

		components.addResponses("WishMutationSuccess", new ApiResponse()
				.description("Wish mutation completed; identical replay returns the original status "
						+ "and body.")
				.addHeaderObject("Idempotency-Replayed",
						new Header().$ref("#/components/headers/IdempotencyReplayed"))
				.content(jsonContent("WishMutationResult")));
		components.addResponses("MalformedOrIdempotencyRequired", errorResponse(
				"MALFORMED_REQUEST, including a missing or non-integer required version, or "
						+ "IDEMPOTENCY_KEY_REQUIRED."));
		components.addResponses("AuthRequired", errorResponse(
				"AUTH_REQUIRED — missing or invalid bearer token.")
				.addHeaderObject("WWW-Authenticate", new Header()
						.required(true)
						.schema(new StringSchema()._const("Bearer"))));
		components.addResponses("Forbidden", errorResponse(
				"FORBIDDEN — the authenticated principal is not a student."));
		components.addResponses("WishOrAccountNotFound", errorResponse(
				"CARD_BALANCE_ACCOUNT_NOT_FOUND or WISH_NOT_FOUND — absence, non-ownership, "
						+ "deletion, or hidden state."));
		components.addResponses("DepositConflict", errorResponse(
				"VERSION_CONFLICT, INVALID_STATE_TRANSITION, BALANCE_MISMATCH_LOCKED, "
						+ "INSUFFICIENT_AVAILABLE_BALANCE, TARGET_AMOUNT_EXCEEDED, or "
						+ "IDEMPOTENCY_KEY_REUSED."));
		components.addResponses("WithdrawalConflict", errorResponse(
				"VERSION_CONFLICT, INVALID_STATE_TRANSITION, INSUFFICIENT_WISH_AMOUNT, or "
						+ "IDEMPOTENCY_KEY_REUSED."));
		components.addResponses("TransferConflict", errorResponse(
				"VERSION_CONFLICT, INVALID_STATE_TRANSITION, CROSS_ACCOUNT_TRANSFER_FORBIDDEN, "
						+ "INSUFFICIENT_WISH_AMOUNT, TARGET_AMOUNT_EXCEEDED, "
						+ "BALANCE_MISMATCH_LOCKED, or IDEMPOTENCY_KEY_REUSED."));
		components.addResponses("InvalidAmountOrVersion", errorResponse(
				"INVALID_AMOUNT or INVALID_VERSION — an independently decoded amount or "
						+ "expectedVersion violates its constraint."));
		components.addResponses("InvalidTransferAmountOrVersion", errorResponse(
				"INVALID_AMOUNT or INVALID_VERSION — an independently decoded amount or "
						+ "source/destination version violates its constraint."));
		components.addResponses("BalanceSyncFailed", errorResponse(
				"BALANCE_SYNC_FAILED — retryable external balance query failure; the failed "
						+ "observation is persisted without mutating the Wish."));
	}

	private static void removeGeneratedSiblingsFromCanonicalRefs(Components components) {
		for (String componentName : List.of(
				"Wish", "WishMutationResult", "WishAmountCommand",
				"WishTransferRequest", "WishTransferResult")) {
			Schema<?> component = components.getSchemas().get(componentName);
			if (component == null || component.getProperties() == null) continue;
			for (Schema<?> property : component.getProperties().values()) {
				if (property.get$ref() == null) continue;
				property.setType(null);
				property.setTypes(null);
				property.setFormat(null);
				property.setMinimum(null);
				property.setMaximum(null);
				property.setMinLength(null);
				property.setMaxLength(null);
				property.setPattern(null);
				property.setEnum(null);
			}
		}
	}

	private static void canonicalizeMutation(
			OpenAPI openApi,
			String path,
			boolean hasWishId,
			String summary,
			String description,
			String conflictResponse,
			String validationResponse,
			boolean balanceSyncFailure) {
		PathItem pathItem = openApi.getPaths().get(path);
		pathItem.setParameters(pathParameters(hasWishId));
		Operation operation = pathItem.getPost();
		operation.setSummary(summary);
		operation.setDescription(description);
		operation.setParameters(List.of(refParameter("IdempotencyKey")));
		operation.setRequestBody(jsonRequest("WishAmountCommand"));
		ApiResponses responses = commonMovementResponses()
				.addApiResponse("200", refResponse("WishMutationSuccess"))
				.addApiResponse("409", refResponse(conflictResponse))
				.addApiResponse("422", refResponse(validationResponse));
		if (balanceSyncFailure) {
			responses.addApiResponse("503", refResponse("BalanceSyncFailed"));
		}
		operation.setResponses(responses);
	}

	private static void canonicalizeTransfer(OpenAPI openApi) {
		PathItem pathItem = openApi.getPaths().get(TRANSFER);
		pathItem.setParameters(pathParameters(false));
		Operation operation = pathItem.getPost();
		operation.setSummary("Atomically transfer funds between two Wishes in one account");
		operation.setDescription(null);
		operation.setParameters(List.of(refParameter("IdempotencyKey")));
		operation.setRequestBody(jsonRequest("WishTransferRequest"));
		operation.setResponses(commonMovementResponses()
				.addApiResponse("200", new ApiResponse()
						.description("Atomic transfer completed; identical replay returns the original "
								+ "status and body.")
						.addHeaderObject("Idempotency-Replayed",
								new Header().$ref("#/components/headers/IdempotencyReplayed"))
						.content(jsonContent("WishTransferResult")))
				.addApiResponse("409", refResponse("TransferConflict"))
				.addApiResponse("422", refResponse("InvalidTransferAmountOrVersion")));
	}

	private static List<Parameter> pathParameters(boolean hasWishId) {
		if (hasWishId) {
			return List.of(refParameter("CardBalanceAccountId"), refParameter("WishId"));
		}
		return List.of(refParameter("CardBalanceAccountId"));
	}

	private static ApiResponses commonMovementResponses() {
		return new ApiResponses()
				.addApiResponse("400", refResponse("MalformedOrIdempotencyRequired"))
				.addApiResponse("401", refResponse("AuthRequired"))
				.addApiResponse("403", refResponse("Forbidden"))
				.addApiResponse("404", refResponse("WishOrAccountNotFound"));
	}

	private static Parameter refParameter(String name) {
		return new Parameter().$ref("#/components/parameters/" + name);
	}

	private static ApiResponse refResponse(String name) {
		return new ApiResponse().$ref("#/components/responses/" + name);
	}

	private static RequestBody jsonRequest(String schemaName) {
		return new RequestBody().required(true).content(jsonContent(schemaName));
	}

	private static ApiResponse errorResponse(String description) {
		return new ApiResponse().description(description).content(jsonContent("ErrorEnvelope"));
	}

	private static Content jsonContent(String schemaName) {
		return new Content().addMediaType("application/json",
				new io.swagger.v3.oas.models.media.MediaType().schema(refSchema(schemaName)));
	}

	private static Schema<?> refSchema(String name) {
		return new Schema<>().$ref("#/components/schemas/" + name);
	}
}
