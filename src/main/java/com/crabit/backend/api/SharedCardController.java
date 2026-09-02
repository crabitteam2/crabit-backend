package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;
import static com.crabit.backend.config.SwaggerUiConfiguration.SHARED_CARD_TAG;

import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.wish.SharedCardProjection;
import com.crabit.backend.wish.SharedCardQueryService;
import com.crabit.backend.wish.WishLifecycleException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/academies/{academyId}/shared-cards")
@Tag(name = SHARED_CARD_TAG)
public class SharedCardController {

	private static final String AUTH_REQUIRED = """
			{"error":{"code":"AUTH_REQUIRED","message":"A known Bearer token is required.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[],"details":{}}}
			""";
	private static final String FORBIDDEN = """
			{"error":{"code":"FORBIDDEN","message":"The authenticated principal is not a student.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[],"details":{}}}
			""";
	private static final String MALFORMED = """
			{"error":{"code":"MALFORMED_REQUEST","message":"cursor is malformed.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[{"field":"cursor","message":"cursor is malformed."}],"details":{}}}
			""";
	private static final String ACADEMY_NOT_FOUND = """
			{"error":{"code":"ACADEMY_NOT_FOUND","message":"Academy not found.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[],"details":{}}}
			""";
	private static final String SHARED_CARD_NOT_FOUND = """
			{"error":{"code":"SHARED_CARD_NOT_FOUND","message":"Shared Card not found.",
			"retryable":false,"traceId":"8f870810-a9d8-4b84-bf13-f83a2b74a136",
			"fieldErrors":[],"details":{}}}
			""";
	private static final String PROGRESS_PAGE = """
			{"items":[{"sharedCardId":"5d0a53d2-7b2d-4a6a-aefa-3c3bbca881b6",
			"kind":"PROGRESS","ownerNickname":"rabbit","purpose":"새 노트북",
			"targetAmount":1500000,"progressPercent":40,
			"balanceAdjustmentInProgress":false,
			"contentUpdatedAt":"2026-08-16T04:00:00Z"}],"nextCursor":null}
			""";
	private static final String PROGRESS = """
			{"sharedCardId":"5d0a53d2-7b2d-4a6a-aefa-3c3bbca881b6",
			"kind":"PROGRESS","ownerNickname":"rabbit","purpose":"새 노트북",
			"targetAmount":1500000,"progressPercent":40,
			"balanceAdjustmentInProgress":false,
			"contentUpdatedAt":"2026-08-16T04:00:00Z"}
			""";

	private final SharedCardQueryService sharedCards;

	public SharedCardController(SharedCardQueryService sharedCards) {
		this.sharedCards = sharedCards;
	}

	@Operation(
			operationId = "listAcademySharedCards",
			summary = "List currently visible Shared Cards in an academy",
			description = "Re-evaluates current academy membership, directional follow, and "
					+ "bilateral blocking on every read. The viewer's own cards are excluded. "
					+ "Visibility is filtered before opaque keyset pagination ordered by "
					+ "contentUpdatedAt descending and sharedCardId descending; relationship and "
					+ "balance reads never reorder a card.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Currently visible Progress and Completion cards.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = SharedCardQueryService.SharedCardPage.class),
						examples = @ExampleObject(name = "progressAdjustmentFalsePage", value = PROGRESS_PAGE))),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST: malformed UUID, cursor, or limit.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "malformedRequest", value = MALFORMED))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED: missing or invalid bearer token.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE, example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "authRequired", value = AUTH_REQUIRED))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN: authenticated principal is not a student.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "forbidden", value = FORBIDDEN))),
		@ApiResponse(responseCode = "404", description = "ACADEMY_NOT_FOUND: absent or currently invisible academy.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "academyNotFound", value = ACADEMY_NOT_FOUND)))
	})
	@GetMapping
	public SharedCardQueryService.SharedCardPage list(
			@Parameter(description = "Academy UUID whose current membership is required.", required = true)
			@PathVariable UUID academyId,
			@Parameter(description = "Opaque cursor for the fixed card order.")
			@RequestParam(required = false) String cursor,
			@Parameter(description = "Maximum page size from 1 through 100.",
					schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
			@RequestParam(required = false) Integer limit,
			HttpServletRequest request) {
		return sharedCards.list(principal(request).subjectId(), academyId, cursor, limit);
	}

	@Operation(
			operationId = "getAcademySharedCard",
			summary = "Get one currently visible Shared Card",
			description = "The owner may read their own currently public card while currently "
					+ "enrolled. Every non-owner absence, membership failure, follow visibility failure, "
					+ "or bilateral block is hidden as SHARED_CARD_NOT_FOUND.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "One currently visible Shared Card.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = SharedCardProjection.class),
						examples = @ExampleObject(name = "progressAdjustmentFalse", value = PROGRESS))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED: missing or invalid bearer token.",
				headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE, example = "Bearer"),
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "authRequired", value = AUTH_REQUIRED))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN: authenticated principal is not a student.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = @ExampleObject(name = "forbidden", value = FORBIDDEN))),
		@ApiResponse(responseCode = "404",
				description = "ACADEMY_NOT_FOUND or SHARED_CARD_NOT_FOUND: absence and visibility failures are hidden.",
				content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
						schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class),
						examples = {
						@ExampleObject(name = "academyNotFound", value = ACADEMY_NOT_FOUND),
						@ExampleObject(name = "sharedCardNotFound", value = SHARED_CARD_NOT_FOUND)
					}))
	})
	@GetMapping("/{cardId}")
	public SharedCardProjection get(
			@Parameter(description = "Academy UUID whose current membership is required.", required = true)
			@PathVariable UUID academyId,
			@Parameter(description = "Opaque stable Shared Card UUID.", required = true)
			@PathVariable UUID cardId,
			HttpServletRequest request) {
		return sharedCards.get(principal(request).subjectId(), academyId, cardId);
	}

	private static CurrentPrincipal principal(HttpServletRequest request) {
		Object value = request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE);
		if (value instanceof CurrentPrincipal principal
				&& principal.role() == CurrentPrincipal.Role.STUDENT) {
			return principal;
		}
		throw new WishLifecycleException(
				WishLifecycleException.Code.AUTH_REQUIRED,
				"A known Bearer token is required.");
	}
}
