package com.crabit.backend.api;

import static com.crabit.backend.api.BehaviorRequestParser.*;

import com.crabit.backend.behavior.BehaviorException;
import com.crabit.backend.behavior.BehaviorModels.*;
import com.crabit.backend.behavior.BehaviorService;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/v1/academies/{academyId}")
public class BehaviorController {
    private final BehaviorService behavior;

    public BehaviorController(BehaviorService behavior) {
        this.behavior = behavior;
    }

    @PostMapping("/profile-visits")
    @Operation(operationId = "createProfileVisit")
    public ResponseEntity<Accepted> profile(
            @PathVariable String academyId,
            @RequestBody(required = false) byte[] bytes,
            HttpServletRequest request) {
        var actor = actor(request);
        var n = body(bytes, request);
        fields(n, Set.of("eventId", "targetStudentId", "occurredAt"), Set.of());
        return accepted(
                behavior.collect(
                        actor,
                        uuid(academyId),
                        new Event(
                                uuid(n, "eventId"),
                                "PROFILE_VISIT",
                                time(n),
                                uuid(n, "targetStudentId"),
                                null,
                                null,
                                null,
                                null,
                                null)));
    }

    @PostMapping("/feed-results")
    @Operation(operationId = "createFeedResult")
    public ResponseEntity<FeedResult> result(
            @PathVariable String academyId,
            @RequestBody(required = false) byte[] bytes,
            HttpServletRequest request) {
        var actor = actor(request);
        var n = body(bytes, request);
        fields(n, Set.of(), Set.of("cursor", "limit"));
        return ResponseEntity.status(201)
                .body(
                        behavior.createResult(
                                actor,
                                uuid(academyId),
                                n.has("cursor") ? string(n, "cursor") : null,
                                n.has("limit") ? integer(n, "limit", 1, 100) : null));
    }

    @PostMapping("/feed-events")
    @Operation(operationId = "createFeedEvent")
    public ResponseEntity<Accepted> feed(
            @PathVariable String academyId,
            @RequestBody(required = false) byte[] bytes,
            HttpServletRequest request) {
        var actor = actor(request);
        var n = body(bytes, request);
        String type = string(n, "eventType");
        var keys =
                new HashSet<>(
                        Set.of(
                                "eventId",
                                "eventType",
                                "occurredAt",
                                "resultContextId",
                                "cardId",
                                "position",
                                "impressionId"));
        if (type.equals("FEED_CLICK")) keys.add("clickKind");
        else if (!type.equals("FEED_EXPOSURE")) throw BehaviorException.malformed();
        fields(n, keys, Set.of());
        String click = type.equals("FEED_CLICK") ? string(n, "clickKind") : null;
        if (click != null && !click.equals("AUTHOR_PROFILE")) throw BehaviorException.malformed();
        return accepted(
                behavior.collect(
                        actor,
                        uuid(academyId),
                        new Event(
                                uuid(n, "eventId"),
                                type,
                                time(n),
                                null,
                                uuid(n, "resultContextId"),
                                uuid(n, "cardId"),
                                integer(n, "position", 0, 99),
                                uuid(n, "impressionId"),
                                click)));
    }

    private static ResponseEntity<Accepted> accepted(Outcome outcome) {
        var builder = ResponseEntity.status(outcome.replayed() ? 200 : 201);
        if (outcome.replayed()) builder.header("Idempotency-Replayed", "true");
        return builder.body(outcome.body());
    }
}
