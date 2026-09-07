package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.crabit.backend.account.*;
import com.crabit.backend.relationship.RelationshipContextAuthorizationService;
import com.crabit.backend.wish.*;
import com.crabit.backend.wishphoto.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecapQueryServicePrivacyTest {
	final UUID account=UUID.randomUUID(), viewer=UUID.randomUUID(), academy=UUID.randomUUID();
	final CardBalanceAccountRepository accounts=mock(CardBalanceAccountRepository.class);
	final RecapGenerationRepository generations=mock(RecapGenerationRepository.class);
	final SharedCardQueryRepository cards=mock(SharedCardQueryRepository.class);
	final RelationshipContextAuthorizationService relationships=mock(RelationshipContextAuthorizationService.class);
	final WishPhotoService photos=mock(WishPhotoService.class);
	final ObjectMapper json=new ObjectMapper();
	final RecapQueryService service=new RecapQueryService(accounts,generations,cards,json,
			Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"),ZoneOffset.UTC),relationships,photos);

	@Test void enrichesInStoredOrderPreservingHistoricalValuesAndStoredBytes() {
		UUID first=UUID.randomUUID(), second=UUID.randomUUID();
		var generation=stored(List.of(story(first,null),story(second,"historical")));
		String before=generation.viewJson();
		when(cards.findVisibleRecapCompletionWishIds(viewer,academy,List.of(first,second))).thenReturn(List.of(row(second),row(first)));
		var photo=new WishPhotoView(UUID.randomUUID(),new WishPhotoView.Variants("small","medium","large"),Instant.EPOCH);
		when(photos.attachedView(second)).thenReturn(photo);
		var stories=stories();
		assertThat(stories).hasSize(2);
		assertThat(stories.getFirst()).containsEntry("wishId",first).containsEntry("typeTitle",null)
				.containsEntry("startDate",null).containsEntry("targetDate",null).containsEntry("photo",null)
				.containsEntry("kind","COMPLETION").containsEntry("progressPercent",100)
				.containsEntry("actualDurationSeconds",86400L).containsEntry("ownerNickname","current name")
				.containsEntry("purpose","current purpose").hasSize(16);
		assertThat(stories.get(1)).containsEntry("wishId",second).containsEntry("typeTitle","historical").containsEntry("photo",photo);
		assertThat(generation.viewJson()).isEqualTo(before);
		verify(generations,never()).save(any());
	}
	@Test void omitsMalformedAndInvisibleStoriesWithoutPhotoAccess() {
		stored(List.of(story(UUID.randomUUID(),"private"),Map.of("wish_id","invalid")));
		assertThat(stories()).isEmpty();
		assertThat(page().get("messageSummary")).isEqualTo("현재 볼 수 있는 성공 story가 없어요.");
		verifyNoInteractions(photos);
	}
	@Test void membershipLossRedactsBeforeDetailLookupOrPhotoSigning() {
		stored(List.of(story(UUID.randomUUID(),"private")));
		when(relationships.canAccessAcademy(viewer,academy)).thenReturn(false);
		assertThat(stories()).isEmpty(); verifyNoInteractions(cards,photos);
	}
	@Test void emptyOriginalPagePreservesSummary() {
		stored(List.of()); assertThat(page().get("messageSummary")).isEqualTo("original summary");
		verifyNoInteractions(cards,photos);
	}
	@Test void photoFailurePropagatesWithoutReturningPartialSuccess() {
		UUID wish=UUID.randomUUID(); stored(List.of(story(wish,null)));
		when(cards.findVisibleRecapCompletionWishIds(viewer,academy,List.of(wish))).thenReturn(List.of(row(wish)));
		var failure=new WishPhotoException(WishPhotoException.Code.PHOTO_DELIVERY_UNAVAILABLE,"Unavailable");
		when(photos.attachedView(wish)).thenThrow(failure);
		assertThatThrownBy(this::stories).isSameAs(failure);
	}
	@Test void atMostFiveStoriesAndOnlyStoredCandidatesAreProjected() {
		var ids=java.util.stream.IntStream.range(0,6).mapToObj(i->UUID.randomUUID()).toList();
		stored(ids.stream().map(id->story(id,"stored")).toList());
		when(cards.findVisibleRecapCompletionWishIds(viewer,academy,ids)).thenReturn(ids.stream().map(this::row).toList());
		assertThat(stories()).hasSize(5); verify(photos,never()).attachedView(ids.getLast());
	}
	private Map<String,Object> story(UUID id,String title) {
		Map<String,Object> out=new LinkedHashMap<>();out.put("wish_id",id.toString());out.put("type_title",title);return out;
	}
	private RecapGeneration stored(List<? extends Map<String,?>> stories) {
		when(accounts.findById(account)).thenReturn(Optional.of(CardBalanceAccount.reconstitute(account,viewer,academy,Instant.EPOCH,null)));
		when(relationships.canAccessAcademy(viewer,academy)).thenReturn(true);
		var generation=new RecapGeneration(UUID.randomUUID(),account,viewer,academy,RecapKind.WEEKLY,LocalDate.parse("2026-08-24"),LocalDate.parse("2026-08-31"),1,"sha256:x","{}",Instant.EPOCH);
		generation.succeed(json.writeValueAsString(Map.of("page3_academy_success_stories",Map.of("message_summary","original summary","stories",stories))),"{\"secret\":true}",Instant.parse("2026-08-31T00:00:00Z"));generation.makeCurrent();
		when(generations.findFirstByAccountIdAndKindAndPeriodStartAndPeriodEndExclusiveAndCurrentVersionTrueOrderByGenerationVersionDesc(account,RecapKind.WEEKLY,LocalDate.parse("2026-08-24"),LocalDate.parse("2026-08-31"))).thenReturn(Optional.of(generation));
		return generation;
	}
	@SuppressWarnings("unchecked") private Map<String,Object> page() {
		var response=service.weekly(viewer,academy,account,"2026-08-24");
		assertThat(response.status()).isEqualTo("SUCCEEDED"); assertThat(response.result().toString()).doesNotContain("secret");
		return (Map<String,Object>)((Map<?,?>)response.result()).get("page3AcademySuccessStories");
	}
	@SuppressWarnings("unchecked") private List<Map<String,Object>> stories(){return (List<Map<String,Object>>)page().get("stories");}
	private SharedCardQueryRepository.Row row(UUID wish) {
		return new SharedCardQueryRepository.Row(UUID.randomUUID(),SharedCardKind.COMPLETION,UUID.randomUUID(),"current name",12,UUID.randomUUID(),academy,Instant.EPOCH,null,wish,"current purpose",10000,0,WishState.COMPLETED,null,null,Instant.EPOCH,Instant.EPOCH.plusSeconds(86400),null,Instant.EPOCH.plusSeconds(86401),false);
	}
}
