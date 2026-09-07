package com.crabit.backend.recap;

import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.wish.SharedCardQueryRepository;
import com.crabit.backend.wish.SharedCardCompletionMapper;
import com.crabit.backend.relationship.RelationshipContextAuthorizationService;
import com.crabit.backend.wishphoto.WishPhotoService;
import com.crabit.backend.wish.WishLifecycleException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class RecapQueryService {
	private final CardBalanceAccountRepository accounts;
	private final RecapGenerationRepository generations;
	private final SharedCardQueryRepository cards;
	private final ObjectMapper json;
	private final Clock clock;
	private final RelationshipContextAuthorizationService relationships;
	private final WishPhotoService photos;

	@Autowired
	public RecapQueryService(CardBalanceAccountRepository accounts,
			RecapGenerationRepository generations, SharedCardQueryRepository cards, ObjectMapper json,
			RelationshipContextAuthorizationService relationships, WishPhotoService photos) {
		this(accounts, generations, cards, json, Clock.systemUTC(), relationships, photos);
	}
	RecapQueryService(CardBalanceAccountRepository accounts, RecapGenerationRepository generations,
			SharedCardQueryRepository cards, ObjectMapper json, Clock clock,
			RelationshipContextAuthorizationService relationships, WishPhotoService photos) {
		this.accounts = accounts; this.generations = generations; this.cards = cards; this.json = json; this.clock = clock;
		this.relationships = relationships; this.photos = photos;
	}

	@Transactional(readOnly = true)
	public Response weekly(UUID studentId, UUID academyId, UUID accountId, String weekStart) {
		return get(studentId, academyId, accountId, RecapKind.WEEKLY, RecapPeriods.weekly(weekStart, clock));
	}
	@Transactional(readOnly = true)
	public Response monthly(UUID studentId, UUID academyId, UUID accountId, String month) {
		return get(studentId, academyId, accountId, RecapKind.MONTHLY, RecapPeriods.monthly(month, clock));
	}

	private Response get(UUID studentId, UUID academyId, UUID accountId, RecapKind kind, RecapPeriods.Period period) {
		try {
			accounts.findById(accountId).filter(a -> a.isActive() && a.studentId().equals(studentId)
					&& a.academyId().equals(academyId)).orElseThrow(() -> new WishLifecycleException(
					WishLifecycleException.Code.CARD_BALANCE_ACCOUNT_NOT_FOUND, "Card Balance Account not found."));
			var current = generations.findFirstByAccountIdAndKindAndPeriodStartAndPeriodEndExclusiveAndCurrentVersionTrueOrderByGenerationVersionDesc(
					accountId, kind, period.start(), period.endExclusive());
			if (current.isPresent()) return project(current.get(), studentId, academyId, period);
			return generations.findFirstByAccountIdAndKindAndPeriodStartAndPeriodEndExclusiveOrderByGenerationVersionDesc(
					accountId, kind, period.start(), period.endExclusive())
					.map(g -> project(g, studentId, academyId, period))
					.orElseGet(() -> Response.notGenerated(kind, period));
		} catch (WishLifecycleException | RecapException e) { throw e; }
		catch (DataAccessException e) { throw new RecapException(RecapException.Code.RECAP_QUERY_UNAVAILABLE,
				"Recaps are temporarily unavailable."); }
	}

	private Response project(RecapGeneration generation, UUID viewer, UUID academy, RecapPeriods.Period period) {
		String publicState = switch (generation.state()) {
			case PENDING, RUNNING -> "GENERATING";
			case NOT_ELIGIBLE -> "NOT_ELIGIBLE";
			case SUCCEEDED -> "SUCCEEDED";
			case FAILED, SUPERSEDED -> "FAILED";
		};
		Object result = null;
		if (generation.state() == RecapGenerationState.SUCCEEDED) {
			result = parseAndCamelize(generation.viewJson());
			if (generation.kind() == RecapKind.WEEKLY && result instanceof Map<?, ?> map) {
				result = authorizeStories(cast(map), viewer, academy);
			}
		}
		return new Response(generation.kind().name(), publicState, PeriodView.of(period),
				generation.generationVersion(), 1, generation.algorithmVersion(), generation.generatedAt(), result);
	}

	private Map<String, Object> authorizeStories(Map<String, Object> view, UUID viewer, UUID academy) {
		Object pageValue = view.get("page3AcademySuccessStories");
		if (!(pageValue instanceof Map<?, ?> pageRaw)) return view;
		Map<String, Object> page = cast(pageRaw);
		Object storiesValue = page.get("stories");
		if (!(storiesValue instanceof List<?> stored)) return view;
		List<UUID> ids = new ArrayList<>();
		for (Object item : stored) if (item instanceof Map<?, ?> raw) {
			UUID id = storyWishId(raw); if (id != null) ids.add(id);
		}
		Map<UUID, SharedCardQueryRepository.Row> visible = new LinkedHashMap<>();
		if (!ids.isEmpty() && relationships.canAccessAcademy(viewer, academy)) {
			for (var row : cards.findVisibleRecapCompletionWishIds(viewer, academy, ids)) visible.put(row.wishId(), row);
		}
		List<Map<String, Object>> allowed = new ArrayList<>();
		for (Object item : stored) if (item instanceof Map<?, ?> raw) {
			if (allowed.size() == 5) break;
			UUID wishId = storyWishId(raw);
			var row = visible.get(wishId); if (row == null) continue;
			var completion = SharedCardCompletionMapper.project(row, photos.attachedView(wishId));
			Map<String, Object> story = new LinkedHashMap<>(); story.put("wishId", wishId);
			story.put("typeTitle", raw.get("typeTitle")); story.put("ownerStudentId", completion.ownerId());
			story.put("sharedCardId", completion.sharedCardId()); story.put("kind", completion.kind());
			story.put("ownerNickname", completion.ownerNickname()); story.put("purpose", completion.purpose());
			story.put("targetAmount", completion.targetAmount()); story.put("progressPercent", completion.progressPercent());
			story.put("startDate", completion.startDate()); story.put("targetDate", completion.targetDate());
			story.put("createdAt", completion.createdAt()); story.put("completedAt", completion.completedAt());
			story.put("actualDurationSeconds", completion.actualDurationSeconds()); story.put("photo", completion.photo());
			story.put("contentUpdatedAt", completion.contentUpdatedAt()); allowed.add(story);
		}
		Map<String, Object> pageCopy = new LinkedHashMap<>(page); pageCopy.put("stories", allowed);
		if (allowed.size() != stored.size()) pageCopy.put("messageSummary", allowed.isEmpty() ? "현재 볼 수 있는 성공 story가 없어요."
				: "현재 볼 수 있는 학원 친구 " + allowed.size() + "명이 목표를 이뤘어요!");
		Map<String, Object> result = new LinkedHashMap<>(view); result.put("page3AcademySuccessStories", pageCopy); return result;
	}

	private static UUID storyWishId(Map<?, ?> story) {
		Object value = story.get("wishId");
		if (!(value instanceof String id)) return null;
		try { return UUID.fromString(id); }
		catch (IllegalArgumentException ignored) { return null; }
	}

	private Object parseAndCamelize(String value) {
		try { return camelize(json.readValue(value, Object.class)); }
		catch (JacksonException e) { throw new RecapException(RecapException.Code.RECAP_QUERY_UNAVAILABLE,
				"The stored recap result is unreadable."); }
	}
	private Object camelize(Object value) {
		if (value instanceof Map<?, ?> source) { Map<String,Object> out = new LinkedHashMap<>(); source.forEach((k,v) -> out.put(camel(String.valueOf(k)), camelize(v))); return out; }
		if (value instanceof List<?> list) return list.stream().map(this::camelize).toList();
		return value;
	}
	private static String camel(String key) { StringBuilder out = new StringBuilder(); boolean up=false; for(char c:key.toCharArray()) { if(c=='_'){up=true;} else {out.append(up?Character.toUpperCase(c):c);up=false;} } return out.toString(); }
	@SuppressWarnings("unchecked") private static Map<String,Object> cast(Map<?,?> value) { return (Map<String,Object>) value; }

	public record Response(String kind, String status, PeriodView period, Long generationVersion,
			int schemaVersion, String algorithmVersion, Instant generatedAt, Object result) {
		static Response notGenerated(RecapKind kind, RecapPeriods.Period period) {
			return new Response(kind.name(), "NOT_GENERATED", PeriodView.of(period), null, 1, null, null, null);
		}
	}
	public record PeriodView(LocalDate startDate, LocalDate endDateExclusive, String timezone) {
		static PeriodView of(RecapPeriods.Period period) { return new PeriodView(period.start(), period.endExclusive(), "Asia/Seoul"); }
	}
}
