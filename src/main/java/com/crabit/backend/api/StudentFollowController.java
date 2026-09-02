package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;

import com.crabit.backend.api.StudentFollowModels.CreateStudentBlockRequest;
import com.crabit.backend.api.StudentFollowModels.FollowPage;
import com.crabit.backend.api.StudentFollowModels.StudentBlockPage;
import com.crabit.backend.api.StudentFollowModels.StudentBlockView;
import com.crabit.backend.api.StudentFollowModels.StudentRelationshipPage;
import com.crabit.backend.auth.CurrentPrincipal;
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

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RestController
@Tag(name = "Student Relationships")
public class StudentFollowController {

    private final RelationshipCommandService commands;
    private final RelationshipQueryService queries;
    private final Clock clock;

    public StudentFollowController(
            RelationshipCommandService commands, RelationshipQueryService queries, Clock clock) {
        this.commands = commands;
        this.queries = queries;
        this.clock = clock;
    }

    @Operation(
            operationId = "searchAcademyStudents",
            summary = "Search current same-academy students by nickname",
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

    @GetMapping("/v1/academies/{academyId}/following")
    @Operation(operationId = "listAcademyFollowing")
    public FollowPage following(
            @PathVariable UUID academyId,
            @RequestParam(required = false) String nickname,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return queries.follows(
                academyPrincipal(request, academyId).subjectId(),
                academyId,
                true,
                nickname,
                cursor,
                limit);
    }

    @GetMapping("/v1/academies/{academyId}/followers")
    @Operation(operationId = "listAcademyFollowers")
    public FollowPage followers(
            @PathVariable UUID academyId,
            @RequestParam(required = false) String nickname,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return queries.follows(
                academyPrincipal(request, academyId).subjectId(),
                academyId,
                false,
                nickname,
                cursor,
                limit);
    }

    @PutMapping("/v1/academies/{academyId}/following/{studentId}")
    @Operation(operationId = "followAcademyStudent")
    public ResponseEntity<Void> follow(
            @PathVariable UUID academyId,
            @PathVariable UUID studentId,
            HttpServletRequest request) {
        commands.follow(academyPrincipal(request, academyId).subjectId(), academyId, studentId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/v1/academies/{academyId}/following/{studentId}")
    @Operation(operationId = "unfollowAcademyStudent")
    public ResponseEntity<Void> unfollow(
            @PathVariable UUID academyId,
            @PathVariable UUID studentId,
            HttpServletRequest request) {
        commands.unfollow(
                academyPrincipal(request, academyId).subjectId(), academyId, studentId, now());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "listMyStudentBlocks",
            summary = "List active blocks created by the authenticated student",
            security = @SecurityRequirement(name = SYNTHETIC_BEARER))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Active blocks.",
                content = @Content(schema = @Schema(implementation = StudentBlockPage.class))),
        @ApiResponse(
                responseCode = "400",
                description = "MALFORMED_REQUEST",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "401",
                description = "AUTH_REQUIRED",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "403",
                description = "FORBIDDEN",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class)))
    })
    @GetMapping("/v1/me/student-blocks")
    public StudentBlockPage blocks(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        CurrentPrincipal principal = principal(request);
        return queries.blocks(principal.subjectId(), cursor, limit);
    }

    @Operation(
            operationId = "blockStudent",
            summary = "Block a student globally",
            description =
                    "Client input never controls the blocker. The canonical student-pair lock ends"
                        + " every follow in both directions across all academies in one"
                        + " transaction; no Idempotency-Key is accepted.",
            security = @SecurityRequirement(name = SYNTHETIC_BEARER))
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Block created.",
                content = @Content(schema = @Schema(implementation = StudentBlockView.class))),
        @ApiResponse(
                responseCode = "400",
                description = "MALFORMED_REQUEST",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "401",
                description = "AUTH_REQUIRED",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "403",
                description = "FORBIDDEN",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "404",
                description = "STUDENT_NOT_FOUND",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "409",
                description = "Relationship conflict",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class)))
    })
    @PostMapping("/v1/me/student-blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public StudentBlockView block(
            @Valid @RequestBody CreateStudentBlockRequest body, HttpServletRequest request) {
        CurrentPrincipal principal = principal(request);
        StudentBlock result = commands.blockStudent(principal.subjectId(), body.studentId());
        return queries.project(result);
    }

    @Operation(
            operationId = "unblockStudent",
            summary = "Release a directional student block",
            security = @SecurityRequirement(name = SYNTHETIC_BEARER))
    @ApiResponses({
        @ApiResponse(
                responseCode = "204",
                description = "Block released; the response has no body."),
        @ApiResponse(
                responseCode = "400",
                description = "MALFORMED_REQUEST",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "401",
                description = "AUTH_REQUIRED",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "403",
                description = "FORBIDDEN",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "404",
                description = "STUDENT_BLOCK_NOT_FOUND",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class)))
    })
    @DeleteMapping("/v1/me/student-blocks/{studentId}")
    public ResponseEntity<Void> unblock(@PathVariable UUID studentId, HttpServletRequest request) {
        CurrentPrincipal principal = principal(request);
        commands.unblockStudent(principal.subjectId(), studentId);
        return ResponseEntity.noContent().build();
    }

    private CurrentPrincipal academyPrincipal(HttpServletRequest request, UUID academyId) {
        CurrentPrincipal principal = principal(request);
        if (!principal.academyId().equals(academyId)) {
            throw new RelationshipException(
                    RelationshipException.Code.ACADEMY_NOT_FOUND, "Academy not found.");
        }
        return principal;
    }

    private static CurrentPrincipal principal(HttpServletRequest request) {
        Object authenticated = request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE);
        if (!(authenticated instanceof CurrentPrincipal principal)) {
            throw new RelationshipException(
                    RelationshipException.Code.AUTH_REQUIRED, "Authentication is required.");
        }
        if (principal.role() != CurrentPrincipal.Role.STUDENT) {
            throw new RelationshipException(
                    RelationshipException.Code.FORBIDDEN, "A student principal is required.");
        }
        return principal;
    }

    private Instant now() {
        return clock.instant();
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Query result."),
        @ApiResponse(
                responseCode = "400",
                description = "MALFORMED_REQUEST",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "401",
                description = "AUTH_REQUIRED",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "403",
                description = "FORBIDDEN",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class))),
        @ApiResponse(
                responseCode = "404",
                description = "ACADEMY_NOT_FOUND",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        WishApiExceptionHandler.ErrorEnvelope
                                                                .class)))
    })
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    private @interface StandardQueryResponses {
        Class<?> success();
    }
}
