package com.crabit.backend.wish;

import com.crabit.backend.relationship.RelationshipContextAuthorizationService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.crabit.backend.wishphoto.WishPhotoService;

@Service
public class SharedCardQueryService {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MAX_LIMIT = 100;

	private final RelationshipContextAuthorizationService relationships;
	private final SharedCardQueryRepository queries;
	private final WishPhotoService photos;
	private final SharedCardCursor cursors;
	private final com.crabit.backend.recommendation.FeedPageContextRepository feedPages;
	private final com.crabit.backend.recommendation.FeedRankingRequestAssembler assembler;
	private final com.crabit.backend.recommendation.FeedRankingClient ranking;
	private final Clock clock;
	private final org.springframework.transaction.support.TransactionTemplate transaction;

	@org.springframework.beans.factory.annotation.Autowired
	public SharedCardQueryService(
			RelationshipContextAuthorizationService relationships,
			SharedCardQueryRepository queries,
			java.util.Optional<WishPhotoService> photos, SharedCardCursor cursors,
			com.crabit.backend.recommendation.FeedPageContextRepository feedPages,
			java.util.Optional<com.crabit.backend.recommendation.FeedRankingRequestAssembler> assembler,
			java.util.Optional<com.crabit.backend.recommendation.FeedRankingClient> ranking, Clock clock,
			org.springframework.transaction.PlatformTransactionManager transactionManager) {
		this.relationships = relationships;
		this.queries = queries;
		this.photos = photos.orElse(null);
		this.cursors = cursors;
		this.feedPages = feedPages; this.assembler = assembler.orElse(null);
		this.ranking = ranking.orElse(null); this.clock = clock;
		this.transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
	}

	public SharedCardQueryService(RelationshipContextAuthorizationService relationships,
			SharedCardQueryRepository queries, java.util.Optional<WishPhotoService> photos,
			SharedCardCursor cursors) {
		this.relationships=relationships; this.queries=queries; this.photos=photos.orElse(null);
		this.cursors=cursors; this.feedPages=null; this.assembler=null; this.ranking=null;
		this.clock=Clock.systemUTC(); this.transaction=null;
	}

	public SharedCardPage list(
			UUID viewerId, UUID academyId, String cursor, Integer requestedLimit) {
		if (feedPages == null) {
			return list(viewerId, academyId, (UUID) null, cursor, requestedLimit);
		}
		FeedPage page = feed(viewerId, academyId, cursor, requestedLimit, ignored -> {});
		return new SharedCardPage(page.items(), page.nextCursor());
	}

	public SharedCardPage list(UUID viewerId, UUID academyId, UUID ownerId, String cursor, Integer requestedLimit) {
		if (ownerId == null && feedPages != null) return list(viewerId, academyId, cursor, requestedLimit);
		return inTransaction(() -> {
			requireAcademy(viewerId, academyId); int limit=validateLimit(requestedLimit);
			var boundary=cursors.decode(cursor,viewerId,academyId,ownerId);
			var candidates=queries.findVisiblePage(viewerId,academyId,ownerId,boundary,limit+1);
			boolean more=candidates.size()>limit; var rows=more?candidates.subList(0,limit):candidates;
			return new SharedCardPage(rows.stream().map(this::project).toList(),
					more?cursors.encode(rows.getLast(),viewerId,academyId,ownerId):null);
		});
	}

	public FeedPage feed(UUID viewer, UUID academy, String cursor, Integer requestedLimit,
			java.util.function.Consumer<List<SharedCardProjection>> beforeCommit) {
		requireAcademy(viewer, academy);
		int limit = validateLimit(requestedLimit);
		Instant now = clock.instant();
		com.crabit.backend.wish.SharedCardCursor.V2 decoded;
		if (cursor == null) {
			UUID requestId = UUID.randomUUID(), contextId = UUID.randomUUID();
			List<UUID> ranked = List.of(); String model = null; UUID successfulRequest = null;
			if (ranking != null && assembler != null) {
				var deadline = com.crabit.backend.recommendation.FeedRankingDeadline.start();
				try {
					var request = assembler.assemble(requestId, contextId, viewer, academy, now, deadline);
					var result = request.candidates().isEmpty() ? java.util.Optional
							.<com.crabit.backend.recommendation.FeedRankingModels.Result>empty()
							: ranking.rank(request, deadline);
					if (result.isPresent()) { ranked = result.get().orderedCardIds(); model="feed-rules-v1"; successfulRequest=requestId; }
				} catch (com.crabit.backend.recommendation.FeedRankingRequestAssembler.DeadlineExceeded ignored) {}
			}
			List<UUID> finalRanked=ranked; String finalModel=model; UUID finalRequest=successfulRequest;
			UUID finalContext=contextId;
			return transaction.execute(status -> {
				var initial=feedPages.create(finalContext,viewer,academy,now,finalRequest,finalModel,finalRanked);
				return transition(new SharedCardCursor.V2(initial.contextId(),initial.stateId(),initial.expiresAt()),
						viewer,academy,now,limit,beforeCommit);
			});
		} else {
			if (!cursors.isV2(cursor)) {
				return inTransaction(() -> {
					var boundary=cursors.decode(cursor,viewer,academy,null);
					var rows=queries.findVisiblePage(viewer,academy,boundary,limit+1);
					boolean more=rows.size()>limit; var selected=more?rows.subList(0,limit):rows;
					var items=selected.stream().map(this::project).toList(); beforeCommit.accept(items);
					return new FeedPage(items,more?cursors.encode(selected.getLast(),viewer,academy,null):null,
							"LATEST",null,null);
				});
			}
			decoded = cursors.decodeV2(cursor, viewer, academy);
			if (!now.isBefore(decoded.expiresAt())) throw new FeedCursorExpired();
		}
		SharedCardCursor.V2 continuation=decoded;
		return transaction.execute(status -> transition(continuation,viewer,academy,now,limit,beforeCommit));
	}

	private <T> T inTransaction(java.util.function.Supplier<T> work) {
		return transaction==null ? work.get() : transaction.execute(status -> work.get());
	}

	private FeedPage transition(SharedCardCursor.V2 decoded, UUID viewer, UUID academy, Instant now,
			int limit, java.util.function.Consumer<List<SharedCardProjection>> beforeCommit) {
		requireAcademy(viewer, academy);
		final List<SharedCardProjection>[] projected = new List[]{List.of()};
		var transition = feedPages.transition(decoded.stateId(), decoded.contextId(), decoded.expiresAt(),
				viewer, academy, now, limit, state -> {
			PageBuild build = build(state, viewer, academy, limit);
			projected[0] = resolve(build.ids(), viewer, academy);
			beforeCommit.accept(projected[0]);
			return new com.crabit.backend.recommendation.FeedPageContextRepository.Page(build.ids(),
					build.rankedOffset(), build.latestAt(), build.latestId(), build.returned(),
					build.hasRanked(), build.hasMore());
		}, (state, replay) -> {
			projected[0]=resolve(replay.itemIds(),viewer,academy); beforeCommit.accept(projected[0]);
		});
		String next = transition.successorStateId()==null ? null
				: cursors.encodeV2(viewer,academy,decoded.contextId(),transition.successorStateId(),decoded.expiresAt());
		UUID request=stateRequest(decoded.stateId());
		boolean hasRanked=request!=null && projected[0].stream().map(SharedCardProjection::sharedCardId)
				.anyMatch(feedPages.rankedIds(decoded.stateId())::contains);
		return new FeedPage(projected[0], next, hasRanked?"RECOMMENDATION":"LATEST",
				hasRanked?request:null, hasRanked?"feed-rules-v1":null);
	}

	private UUID stateRequest(UUID state) {
		return feedPages.requestId(state);
	}

	private PageBuild build(com.crabit.backend.recommendation.FeedPageContextRepository.State state,
			UUID viewer, UUID academy, int limit) {
		List<UUID> ids=new ArrayList<>(); int offset=state.rankedOffset();
		Map<UUID,SharedCardQueryRepository.Row> visible=new LinkedHashMap<>();
		queries.findVisibleCardIds(viewer,academy,state.rankedIds().subList(offset,state.rankedIds().size()))
				.forEach(row -> visible.put(row.sharedCardId(),row));
		while(offset<state.rankedIds().size() && ids.size()<limit) {
			UUID id=state.rankedIds().get(offset++); if(visible.containsKey(id)) ids.add(id);
		}
		Instant boundaryAt=state.latestUpdatedAt(); UUID boundaryId=state.latestCardId(); boolean latestMore=false;
		Set<UUID> excluded=new java.util.HashSet<>(state.returnedIds()); excluded.addAll(ids);
		while(ids.size()<limit) {
			var rows=queries.findVisiblePage(viewer,academy,
					boundaryAt==null?null:new SharedCardQueryRepository.CursorBoundary(boundaryAt,boundaryId),101);
			if(rows.isEmpty()) break;
			int processed=0;
			for(var row:rows) { processed++; boundaryAt=row.contentUpdatedAt(); boundaryId=row.sharedCardId();
				if(excluded.add(row.sharedCardId())) ids.add(row.sharedCardId());
				if(ids.size()==limit) break; }
			if(ids.size()==limit) { latestMore=processed<rows.size() || rows.size()==101; break; }
			if(rows.size()<101) break;
		}
		List<UUID> returned=new ArrayList<>(state.returnedIds()); returned.addAll(ids);
		boolean more=offset<state.rankedIds().size() || latestMore;
		return new PageBuild(ids,offset,boundaryAt,boundaryId,returned,!ids.isEmpty() &&
				ids.stream().anyMatch(state.rankedIds()::contains),more);
	}

	private List<SharedCardProjection> resolve(List<UUID> ids, UUID viewer, UUID academy) {
		queries.lockFeedProjection(viewer, academy, ids);
		Map<UUID,SharedCardQueryRepository.Row> rows=new LinkedHashMap<>();
		queries.findVisibleCardIds(viewer,academy,ids).forEach(row->rows.put(row.sharedCardId(),row));
		return ids.stream().filter(rows::containsKey).map(id->project(rows.get(id))).toList();
	}

	private record PageBuild(List<UUID> ids,int rankedOffset,Instant latestAt,UUID latestId,
			List<UUID> returned,boolean hasRanked,boolean hasMore) {}
	public record FeedPage(List<SharedCardProjection> items,String nextCursor,String sortSource,
			UUID recommendationResultId,String modelVersion) {}
	public static final class FeedCursorExpired extends RuntimeException {}

	@Transactional(readOnly = true)
	public SharedCardProjection get(UUID viewerId, UUID academyId, UUID cardId) {
		requireAcademy(viewerId, academyId);
		return queries.findVisibleDetail(viewerId, academyId,
				Objects.requireNonNull(cardId, "cardId"))
				.map(this::project)
				.orElseThrow(() -> new WishLifecycleException(
						WishLifecycleException.Code.SHARED_CARD_NOT_FOUND,
						"Shared Card not found."));
	}

	private void requireAcademy(UUID viewerId, UUID academyId) {
		if (!relationships.canAccessAcademy(
				Objects.requireNonNull(viewerId, "viewerId"),
				Objects.requireNonNull(academyId, "academyId"))) {
			throw new WishLifecycleException(
					WishLifecycleException.Code.ACADEMY_NOT_FOUND,
					"Academy not found.");
		}
	}

	private SharedCardProjection project(SharedCardQueryRepository.Row row) {
		var photo = photos == null ? null : photos.attachedView(row.wishId());
		if (row.kind() == SharedCardKind.COMPLETION) {
			return SharedCardCompletionMapper.project(row, photo);
		}
		if (row.kind() == SharedCardKind.ABANDONMENT) {
			if (row.state() != WishState.ABANDONED || row.abandonmentAmount() == null) {
				throw new IllegalStateException("Abandonment Shared Card requires immutable abandonment data");
			}
			return new SharedCardProjection.Abandonment(
					row.sharedCardId(), "ABANDONMENT", "ABANDONED", row.ownerId(), row.ownerNickname(),
					row.purpose(), row.targetAmount(), abandonmentProgressPercent(row), photo,
					row.startDate(), row.targetDate(), row.contentUpdatedAt());
		}
		return new SharedCardProjection.Progress(
				row.sharedCardId(), "PROGRESS", row.ownerNickname(), row.ownerId(), row.startDate(), row.purpose(),
				row.targetAmount(), progressPercent(row), row.balanceAdjustmentInProgress(), row.targetDate(),
				photo, row.contentUpdatedAt());
	}

	private static int progressPercent(SharedCardQueryRepository.Row row) {
		if (row.state() == WishState.AMOUNT_REACHED) {
			return 100;
		}
		BigInteger percent = BigInteger.valueOf(row.wishAmount())
				.multiply(BigInteger.valueOf(100))
				.divide(BigInteger.valueOf(row.targetAmount()));
		return Math.min(99, percent.intValueExact());
	}

	private static int abandonmentProgressPercent(SharedCardQueryRepository.Row row) {
		BigInteger percent = BigInteger.valueOf(row.abandonmentAmount())
				.multiply(BigInteger.valueOf(100))
				.divide(BigInteger.valueOf(row.targetAmount()));
		return percent.intValueExact();
	}

	private static int validateLimit(Integer requestedLimit) {
		int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
		if (limit < 1 || limit > MAX_LIMIT) {
			throw malformed("limit must be between 1 and 100.", "limit");
		}
		return limit;
	}

	private static WishLifecycleException malformed(String message, String field) {
		return new WishLifecycleException(
				WishLifecycleException.Code.MALFORMED_REQUEST, message, field);
	}

	@Schema(name = "SharedCardPage", description = "Visible cards and opaque continuation cursor.",
			additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
	public record SharedCardPage(
			@ArraySchema(schema = @Schema(implementation = SharedCardProjection.class))
			List<SharedCardProjection> items,
			@Schema(nullable = true, minLength = 1,
					description = "Opaque next-page cursor, or null.")
			String nextCursor) {
	}
}
