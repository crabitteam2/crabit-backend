package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;

import com.crabit.backend.api.FriendManagementModels.CreateFriendRequestRequest;
import com.crabit.backend.api.FriendManagementModels.CreateStudentBlockRequest;
import com.crabit.backend.api.FriendManagementModels.Friend;
import com.crabit.backend.api.FriendManagementModels.FriendPage;
import com.crabit.backend.api.FriendManagementModels.FriendRequestPage;
import com.crabit.backend.api.FriendManagementModels.FriendRequestView;
import com.crabit.backend.api.FriendManagementModels.StudentBlockPage;
import com.crabit.backend.api.FriendManagementModels.StudentBlockView;
import com.crabit.backend.api.FriendManagementModels.StudentRelationshipPage;
import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.relationship.FriendRequest;
import com.crabit.backend.relationship.Friendship;
import com.crabit.backend.relationship.RelationshipCommandService;
import com.crabit.backend.relationship.RelationshipException;
import com.crabit.backend.relationship.RelationshipQueryService;
import com.crabit.backend.relationship.StudentBlock;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Friend Management")
public class FriendManagementController {

	private final RelationshipCommandService commands;
	private final RelationshipQueryService queries;
	private final Clock clock;

	public FriendManagementController(
			RelationshipCommandService commands, RelationshipQueryService queries, Clock clock) {
		this.commands = commands;
		this.queries = queries;
		this.clock = clock;
	}

	@Operation(operationId = "searchAcademyStudents", summary = "Search current same-academy students by nickname",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@StandardQueryResponses(success = StudentRelationshipPage.class)
	@GetMapping("/v1/academies/{academyId}/students")
	public StudentRelationshipPage searchStudents(
			@PathVariable UUID academyId,
			@RequestParam String nickname,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit,
			HttpServletRequest request) {
		CurrentPrincipal principal = academyPrincipal(request, academyId);
		return queries.search(principal.subjectId(), academyId, nickname, cursor, limit);
	}

	@Operation(operationId = "listAcademyFriends", summary = "List current academy friends",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@StandardQueryResponses(success = FriendPage.class)
	@GetMapping("/v1/academies/{academyId}/friends")
	public FriendPage friends(
			@PathVariable UUID academyId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit,
			HttpServletRequest request) {
		CurrentPrincipal principal = academyPrincipal(request, academyId);
		return queries.friends(principal.subjectId(), academyId, cursor, limit);
	}

	@Operation(operationId = "unfriendAcademyStudent", summary = "End a current academy friendship",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "Friendship ended; the response has no body."),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404", description = "ACADEMY_NOT_FOUND or FRIENDSHIP_NOT_FOUND", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@DeleteMapping("/v1/academies/{academyId}/friends/{studentId}")
	public ResponseEntity<Void> unfriend(
			@PathVariable UUID academyId, @PathVariable UUID studentId, HttpServletRequest request) {
		CurrentPrincipal principal = academyPrincipal(request, academyId);
		commands.unfriend(principal.subjectId(), academyId, studentId, now());
		return ResponseEntity.noContent().build();
	}

	@Operation(operationId = "sendFriendRequest", summary = "Send a friend request",
			description = "Creates one PENDING request from CurrentPrincipal.subjectId. No Idempotency-Key is accepted.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "Friend request created.", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = FriendRequestView.class))),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404", description = "ACADEMY_NOT_FOUND or STUDENT_NOT_FOUND", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "409", description = "Relationship conflict", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@PostMapping("/v1/academies/{academyId}/friend-requests")
	@ResponseStatus(HttpStatus.CREATED)
	public FriendRequestView send(
			@PathVariable UUID academyId,
			@Valid @RequestBody CreateFriendRequestRequest body,
			HttpServletRequest request) {
		CurrentPrincipal principal = academyPrincipal(request, academyId);
		FriendRequest created = commands.sendFriendRequest(
				principal.subjectId(), academyId, body.studentId(), now());
		return queries.project(created, principal.subjectId());
	}

	@Operation(operationId = "listSentFriendRequests", summary = "List sent pending friend requests",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@StandardQueryResponses(success = FriendRequestPage.class)
	@GetMapping("/v1/academies/{academyId}/friend-requests/sent")
	public FriendRequestPage sentRequests(
			@PathVariable UUID academyId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit,
			HttpServletRequest request) {
		CurrentPrincipal principal = academyPrincipal(request, academyId);
		return queries.requests(principal.subjectId(), academyId, true, cursor, limit);
	}

	@Operation(operationId = "listReceivedFriendRequests", summary = "List received pending friend requests",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@StandardQueryResponses(success = FriendRequestPage.class)
	@GetMapping("/v1/academies/{academyId}/friend-requests/received")
	public FriendRequestPage receivedRequests(
			@PathVariable UUID academyId,
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit,
			HttpServletRequest request) {
		CurrentPrincipal principal = academyPrincipal(request, academyId);
		return queries.requests(principal.subjectId(), academyId, false, cursor, limit);
	}

	@Operation(operationId = "cancelFriendRequest", summary = "Cancel a sent pending friend request",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@RequestMutationResponses(success = FriendRequestView.class)
	@DeleteMapping("/v1/academies/{academyId}/friend-requests/{friendRequestId}")
	public FriendRequestView cancel(
			@PathVariable UUID academyId, @PathVariable UUID friendRequestId, HttpServletRequest request) {
		CurrentPrincipal principal = academyPrincipal(request, academyId);
		FriendRequest result = commands.cancelFriendRequest(
				principal.subjectId(), academyId, friendRequestId, now());
		return queries.project(result, principal.subjectId());
	}

	@Operation(operationId = "acceptFriendRequest", summary = "Accept a received pending friend request",
			description = "Rechecks the canonical student-pair lock, current academy memberships, exact request, current friendship, and bilateral blocks in one transaction; a concurrent loser receives a conflict.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@RequestMutationResponses(success = Friend.class)
	@PostMapping("/v1/academies/{academyId}/friend-requests/{friendRequestId}/acceptance")
	public Friend accept(
			@PathVariable UUID academyId, @PathVariable UUID friendRequestId, HttpServletRequest request) {
		CurrentPrincipal principal = academyPrincipal(request, academyId);
		Friendship friendship = commands.acceptFriendRequest(
				principal.subjectId(), academyId, friendRequestId, now());
		return queries.project(friendship, principal.subjectId());
	}

	@Operation(operationId = "rejectFriendRequest", summary = "Reject a received pending friend request",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@RequestMutationResponses(success = FriendRequestView.class)
	@PostMapping("/v1/academies/{academyId}/friend-requests/{friendRequestId}/rejection")
	public FriendRequestView reject(
			@PathVariable UUID academyId, @PathVariable UUID friendRequestId, HttpServletRequest request) {
		CurrentPrincipal principal = academyPrincipal(request, academyId);
		FriendRequest result = commands.rejectFriendRequest(
				principal.subjectId(), academyId, friendRequestId, now());
		return queries.project(result, principal.subjectId());
	}

	@Operation(operationId = "listMyStudentBlocks", summary = "List active blocks created by the authenticated student",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Active blocks.", content = @Content(schema = @Schema(implementation = StudentBlockPage.class))),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@GetMapping("/v1/me/student-blocks")
	public StudentBlockPage blocks(
			@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer limit,
			HttpServletRequest request) {
		CurrentPrincipal principal = principal(request);
		return queries.blocks(principal.subjectId(), cursor, limit);
	}

	@Operation(operationId = "blockStudent", summary = "Block a student globally",
			description = "Client input never controls the blocker. The canonical student-pair lock ends every friendship and cancels every pending request in one transaction; no Idempotency-Key is accepted.",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "201", description = "Block created.", content = @Content(schema = @Schema(implementation = StudentBlockView.class))),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404", description = "STUDENT_NOT_FOUND", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "409", description = "Relationship conflict", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@PostMapping("/v1/me/student-blocks")
	@ResponseStatus(HttpStatus.CREATED)
	public StudentBlockView block(
			@Valid @RequestBody CreateStudentBlockRequest body, HttpServletRequest request) {
		CurrentPrincipal principal = principal(request);
		StudentBlock result = commands.blockStudent(principal.subjectId(), body.studentId(), now());
		return queries.project(result);
	}

	@Operation(operationId = "unblockStudent", summary = "Release a directional student block",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	@ApiResponses({
		@ApiResponse(responseCode = "204", description = "Block released; the response has no body."),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404", description = "STUDENT_BLOCK_NOT_FOUND", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@DeleteMapping("/v1/me/student-blocks/{studentId}")
	public ResponseEntity<Void> unblock(@PathVariable UUID studentId, HttpServletRequest request) {
		CurrentPrincipal principal = principal(request);
		commands.unblockStudent(principal.subjectId(), studentId, now());
		return ResponseEntity.noContent().build();
	}

	private CurrentPrincipal academyPrincipal(HttpServletRequest request, UUID academyId) {
		CurrentPrincipal principal = principal(request);
		if (!principal.academyId().equals(academyId)) {
			throw new RelationshipException(RelationshipException.Code.ACADEMY_NOT_FOUND,
					"Academy not found.");
		}
		return principal;
	}

	private static CurrentPrincipal principal(HttpServletRequest request) {
		Object authenticated = request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE);
		if (!(authenticated instanceof CurrentPrincipal principal)) {
			throw new RelationshipException(RelationshipException.Code.AUTH_REQUIRED,
					"Authentication is required.");
		}
		if (principal.role() != CurrentPrincipal.Role.STUDENT) {
			throw new RelationshipException(RelationshipException.Code.FORBIDDEN,
					"A student principal is required.");
		}
		return principal;
	}

	private Instant now() {
		return clock.instant();
	}

	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Query result."),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404", description = "ACADEMY_NOT_FOUND", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	private @interface StandardQueryResponses {
		Class<?> success();
	}

	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "Mutation result."),
		@ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "401", description = "AUTH_REQUIRED", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "403", description = "FORBIDDEN", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "404", description = "Not found", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class))),
		@ApiResponse(responseCode = "409", description = "Relationship conflict", content = @Content(schema = @Schema(implementation = WishApiExceptionHandler.ErrorEnvelope.class)))
	})
	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	private @interface RequestMutationResponses {
		Class<?> success();
	}
}
