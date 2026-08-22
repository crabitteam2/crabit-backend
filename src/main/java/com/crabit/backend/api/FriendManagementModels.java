package com.crabit.backend.api;

import com.crabit.backend.relationship.FriendRequestStatus;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FriendManagementModels {

	private FriendManagementModels() {
	}

	@Schema(name = "RelationshipState", enumAsRef = true,
			description = "Current relationship between the authenticated student and one same-academy search result, computed at response read time.")
	public enum RelationshipState {
		NONE,
		FRIEND,
		OUTGOING_PENDING,
		INCOMING_PENDING
	}

	@Schema(name = "StudentSummary",
			description = "Privacy-minimal student projection with no real name, card data, Wish data, authentication data, or academy-membership internals.")
	public record StudentSummary(
			@Schema(description = "Stable UUID of the projected counterpart student.", requiredMode = Schema.RequiredMode.REQUIRED)
			UUID studentId,
			@Schema(description = "Current nonblank student nickname, containing at most 80 Unicode code points.", minLength = 1, maxLength = 80, requiredMode = Schema.RequiredMode.REQUIRED)
			String nickname) {
	}

	@Schema(name = "StudentRelationship")
	public record StudentRelationship(
			@Schema(description = "Stable UUID of the same-academy search result.", requiredMode = Schema.RequiredMode.REQUIRED)
			UUID studentId,
			@Schema(description = "Current nonblank nickname used by the deterministic search ordering.", minLength = 1, maxLength = 80, requiredMode = Schema.RequiredMode.REQUIRED)
			String nickname,
			@Schema(description = "Exactly one current relationship state computed for the authenticated student.", requiredMode = Schema.RequiredMode.REQUIRED)
			RelationshipState relationshipState) {
	}

	@Schema(name = "StudentRelationshipPage")
	public record StudentRelationshipPage(
			@ArraySchema(arraySchema = @Schema(description = "Same-academy matches ordered by nickname ascending, studentId ascending after self, non-current membership, and bilateral-block exclusions.", requiredMode = Schema.RequiredMode.REQUIRED),
					schema = @Schema(implementation = StudentRelationship.class))
			List<StudentRelationship> items,
			@Schema(description = "Opaque cursor derived from the final returned (nickname, studentId) tuple and normalized nickname filter; null when no further item exists.", nullable = true, minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED)
			String nextCursor) {
	}

	@Schema(name = "Friend")
	public record Friend(
			@Schema(description = "Stable UUID of the current friend.", requiredMode = Schema.RequiredMode.REQUIRED)
			UUID studentId,
			@Schema(description = "Current nonblank nickname of the friend.", minLength = 1, maxLength = 80, requiredMode = Schema.RequiredMode.REQUIRED)
			String nickname,
			@Schema(description = "RFC 3339 UTC Z instant at which the current friendship was created or restarted.", requiredMode = Schema.RequiredMode.REQUIRED)
			Instant friendsSince) {
	}

	@Schema(name = "FriendPage")
	public record FriendPage(
			@ArraySchema(arraySchema = @Schema(description = "Current academy friends ordered by friendsSince descending, studentId descending.", requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(implementation = Friend.class))
			List<Friend> items,
			@Schema(description = "Opaque cursor derived from the final returned (friendsSince, studentId) tuple; null when no further item exists.", nullable = true, minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED)
			String nextCursor) {
	}

	@Schema(name = "CreateFriendRequestRequest",
			description = "Request payload naming only the receiver; the sender always comes from CurrentPrincipal.subjectId.")
	public record CreateFriendRequestRequest(
			@NotNull
			@Schema(description = "UUID of the intended current same-academy receiver.", requiredMode = Schema.RequiredMode.REQUIRED)
			UUID studentId) {
	}

	@Schema(name = "FriendRequest",
			description = "Privacy-minimal friend-request projection. Counterpart is the receiver for sent results and sender for received results.")
	public record FriendRequestView(
			@Schema(description = "Stable UUID of this friend request.", requiredMode = Schema.RequiredMode.REQUIRED)
			UUID friendRequestId,
			@Schema(description = "Receiver for sent results and sender for received results; ownership identifiers are not exposed separately.", requiredMode = Schema.RequiredMode.REQUIRED)
			StudentSummary counterpart,
			@Schema(description = "Current request lifecycle state.", requiredMode = Schema.RequiredMode.REQUIRED)
			FriendRequestStatus status,
			@Schema(description = "RFC 3339 UTC Z instant at which this request was created.", requiredMode = Schema.RequiredMode.REQUIRED)
			Instant createdAt,
			@Schema(description = "RFC 3339 UTC Z processing instant for ACCEPTED, REJECTED, or CANCELED; null only while PENDING.", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
			Instant processedAt) {
	}

	@Schema(name = "FriendRequestPage")
	public record FriendRequestPage(
			@ArraySchema(arraySchema = @Schema(description = "Actor-owned PENDING requests ordered by createdAt descending, friendRequestId descending.", requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(implementation = FriendRequestView.class))
			List<FriendRequestView> items,
			@Schema(description = "Opaque cursor derived from the final returned (createdAt, friendRequestId) tuple; null when no further item exists.", nullable = true, minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED)
			String nextCursor) {
	}

	@Schema(name = "CreateStudentBlockRequest",
			description = "Request payload naming only the blocked student; the blocker always comes from CurrentPrincipal.subjectId.")
	public record CreateStudentBlockRequest(
			@NotNull
			@Schema(description = "UUID of the student to block globally.", requiredMode = Schema.RequiredMode.REQUIRED)
			UUID studentId) {
	}

	@Schema(name = "StudentBlock")
	public record StudentBlockView(
			@Schema(description = "Stable UUID of the blocked student.", requiredMode = Schema.RequiredMode.REQUIRED)
			UUID studentId,
			@Schema(description = "Current nonblank nickname of the blocked student.", minLength = 1, maxLength = 80, requiredMode = Schema.RequiredMode.REQUIRED)
			String nickname,
			@Schema(description = "RFC 3339 UTC Z instant at which the current directional block was created or recreated.", requiredMode = Schema.RequiredMode.REQUIRED)
			Instant blockedAt) {
	}

	@Schema(name = "StudentBlockPage")
	public record StudentBlockPage(
			@ArraySchema(arraySchema = @Schema(description = "Active blocks created by the authenticated student, ordered by blockedAt descending, studentId descending.", requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(implementation = StudentBlockView.class))
			List<StudentBlockView> items,
			@Schema(description = "Opaque cursor derived from the final returned (blockedAt, studentId) tuple; null when no further item exists.", nullable = true, minLength = 1, requiredMode = Schema.RequiredMode.REQUIRED)
			String nextCursor) {
	}
}
