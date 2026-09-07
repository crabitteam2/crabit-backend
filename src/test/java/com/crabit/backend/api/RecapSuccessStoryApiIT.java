package com.crabit.backend.api;

import static com.crabit.backend.e2e.SeedFixtureCatalog.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.crabit.backend.recap.*;
import com.crabit.backend.wishphoto.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties="crabit.wish-photo.enabled=true")
class RecapSuccessStoryApiIT extends WishApiIntegrationSupport {
	@Autowired RecapGenerationRepository generations;
	@Autowired ObjectMapper mapper;
	@MockitoBean WishPhotoStorage storage;
	@MockitoBean WishPhotoSafetyScanner safety;
	UUID owner,account,wish,card,generationId;
	static final Instant CREATED=Instant.parse("2026-08-01T00:00:00Z"), COMPLETED=Instant.parse("2026-08-25T00:00:00Z");
	static final String PATH="/v1/card-balance-accounts/"+OWNER_ACCOUNT_ID+"/recaps/weekly?weekStart=2026-08-24";

	@BeforeEach void setupStory() {
		owner=UUID.randomUUID();account=UUID.randomUUID();wish=UUID.randomUUID();card=UUID.randomUUID();
		jdbc.update("insert into student(id,nickname,age) values (?,'current nickname',12)",owner);
		jdbc.update("insert into academy_membership(id,student_id,academy_id,joined_at) values (?,?,?,?)",UUID.randomUUID(),owner,PRIMARY_ACADEMY_ID,Timestamp.from(CREATED));
		jdbc.update("insert into card_balance_account(id,student_id,academy_id,opened_at) values (?,?,?,?)",account,owner,PRIMARY_ACADEMY_ID,Timestamp.from(CREATED));
		jdbc.update("insert into wish(id,account_id,academy_id,purpose,target_amount,wish_amount,state,visibility,created_at,completed_at) values (?,?,?,'current purpose',10000,0,'COMPLETED','ACADEMY',?,?)",wish,account,PRIMARY_ACADEMY_ID,Timestamp.from(CREATED),Timestamp.from(COMPLETED));
		jdbc.update("insert into shared_card(id,wish_id,kind,visibility,updated_at) values (?,?,'COMPLETION','ACADEMY',?)",card,wish,Timestamp.from(COMPLETED));
		generationId=UUID.randomUUID();
		var generation=new RecapGeneration(generationId,OWNER_ACCOUNT_ID,OWNER_ID,PRIMARY_ACADEMY_ID,RecapKind.WEEKLY,LocalDate.parse("2026-08-24"),LocalDate.parse("2026-08-31"),1,"sha256:test","{}",COMPLETED);
		generation.succeed("{\"page3_academy_success_stories\":{\"message_summary\":\"stored summary\",\"stories\":[{\"wish_id\":\""+wish+"\",\"type_title\":null}]}}","{\"private\":true}",Instant.parse("2026-08-31T00:00:00Z"));generation.makeCurrent();generations.saveAndFlush(generation);
		clock.set(Instant.parse("2026-09-07T00:00:00.123Z"));
		when(storage.signedUrls(anyString(),any(WishPhotoStorage.SigningWindow.class))).thenAnswer(invocation->{
			var window=invocation.getArgument(1,WishPhotoStorage.SigningWindow.class);
			String base="https://private.example.invalid/"+window.issuedAt().getEpochSecond();
			return new WishPhotoView.Variants(base+"/small",base+"/medium",base+"/large");
		});
	}

	@Test void fullCardMatchesPublicDetailAndDoesNotRewriteGeneration() throws Exception {
		var before=jdbc.queryForMap("select * from recap_generation where id=?",generationId);
		Map<String,Object> story=story();
		assertThat(OpenApiExamplesTest.validateWireResponse("WeeklyRecapStory",story)).isEmpty();
		assertThat(story).containsEntry("typeTitle",null).containsEntry("startDate",null).containsEntry("targetDate",null).containsEntry("photo",null)
				.containsEntry("actualDurationSeconds",24*86400).containsEntry("ownerNickname","current nickname");
		String detail=asOwner(get("/v1/academies/"+PRIMARY_ACADEMY_ID+"/shared-cards/"+card)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		Map<String,Object> completion=mapper.readValue(detail,Map.class);
		for(String field:List.of("kind","ownerNickname","purpose","targetAmount","progressPercent","startDate","targetDate","createdAt","completedAt","actualDurationSeconds","photo","contentUpdatedAt"))
			assertThat(story.get(field)).as(field).isEqualTo(completion.get(field));
		jdbc.update("update student set nickname='renamed owner' where id=?",owner);
		jdbc.update("update wish set start_date='2026-08-03',target_date='2026-08-30' where id=?",wish);
		assertThat(story()).containsEntry("ownerNickname","renamed owner")
				.containsEntry("startDate","2026-08-03").containsEntry("targetDate","2026-08-30")
				.containsEntry("actualDurationSeconds",24*86400).containsEntry("typeTitle",null);
		assertThat(jdbc.queryForMap("select * from recap_generation where id=?",generationId)).isEqualTo(before);
		verifyNoInteractions(storage);
	}

	@Test void attachedPhotoGetsFreshWholeSecondUrlsAndSigningFailureIs503() throws Exception {
		attachPhoto();
		var before=jdbc.queryForMap("select * from recap_generation where id=?",generationId);
		var first=story();
		assertThat(OpenApiExamplesTest.validateWireResponse("WeeklyRecapStory",first)).isEmpty();
		assertThat(((Map<?,?>)first.get("photo")).get("expiresAt")).isEqualTo("2026-09-07T00:05:00Z");
		clock.set(Instant.parse("2026-09-07T00:00:01.999Z"));
		assertThat(story().get("photo")).isNotEqualTo(first.get("photo"));
		when(storage.signedUrls(anyString(),any(WishPhotoStorage.SigningWindow.class))).thenThrow(new IllegalStateException("secret signing failure"));
		asOwner(get(PATH)).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"))
				.andExpect(jsonPath("$.error.code").value("PHOTO_DELIVERY_UNAVAILABLE")).andExpect(jsonPath("$.error.retryable").value(true))
				.andExpect(jsonPath("$.result").doesNotExist());
		assertThat(jdbc.queryForMap("select * from recap_generation where id=?",generationId)).isEqualTo(before);
	}

	@ParameterizedTest @ValueSource(strings={"private","deleted","owner-left","account-closed","viewer-left","block-forward","block-reverse","progress","abandoned","card-missing","other-academy","self"})
	void currentPrivacyAndCompletionChangesRedactBeforeSigning(String change) throws Exception {
		attachPhoto();
		switch(change) {
			case "private" -> {jdbc.update("update wish set visibility='PRIVATE' where id=?",wish);jdbc.update("delete from shared_card where id=?",card);}
			case "deleted" -> jdbc.update("update wish set deleted_at=?,deleted_purpose_snapshot=purpose where id=?",Timestamp.from(COMPLETED.plusSeconds(1)),wish);
			case "owner-left" -> jdbc.update("update academy_membership set left_at=? where student_id=?",Timestamp.from(COMPLETED.plusSeconds(1)),owner);
			case "viewer-left" -> jdbc.update("update academy_membership set left_at=? where student_id=?",Timestamp.from(COMPLETED.plusSeconds(1)),OWNER_ID);
			case "account-closed" -> jdbc.update("update card_balance_account set closed_at=? where id=?",Timestamp.from(COMPLETED.plusSeconds(1)),account);
			case "block-forward","block-reverse" -> jdbc.update("insert into student_block(id,blocker_id,blocked_id,blocked_at) values (?,?,?,?)",UUID.randomUUID(),change.equals("block-forward")?OWNER_ID:owner,change.equals("block-forward")?owner:OWNER_ID,Timestamp.from(COMPLETED));
			case "progress" -> jdbc.update("update shared_card set kind='PROGRESS' where id=?",card);
			case "abandoned" -> {jdbc.update("update wish set state='ABANDONED',completed_at=null,abandoned_at=?,abandonment_amount=1000 where id=?",Timestamp.from(COMPLETED),wish);jdbc.update("update shared_card set kind='ABANDONMENT' where id=?",card);}
			case "card-missing" -> jdbc.update("delete from shared_card where id=?",card);
			case "other-academy" -> {
				UUID other=UUID.randomUUID();
				jdbc.update("insert into card_balance_account(id,student_id,academy_id,opened_at) values (?,?,?,?)",other,owner,OTHER_ACADEMY_ID,Timestamp.from(CREATED));
				jdbc.update("update wish set account_id=?,academy_id=? where id=?",other,OTHER_ACADEMY_ID,wish);
			}
			case "self" -> jdbc.update("update wish set account_id=? where id=?",OWNER_ACCOUNT_ID,wish);
		}
		asOwner(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.result.page3AcademySuccessStories.stories").isEmpty());
		verifyNoInteractions(storage);
	}

	@Test void followersRequireViewerToOwnerAndAreRecheckedAfterUnfollow() throws Exception {
		jdbc.update("update shared_card set visibility='FOLLOWERS' where id=?",card);
		follow(owner,OWNER_ID);
		asOwner(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.result.page3AcademySuccessStories.stories").isEmpty());
		UUID forward=follow(OWNER_ID,owner); assertThat(story()).containsEntry("wishId",wish.toString());
		jdbc.update("update student_follow set ended_at=? where id=?",Timestamp.from(COMPLETED.plusSeconds(1)),forward);
		asOwner(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.result.page3AcademySuccessStories.stories").isEmpty());
	}
	private UUID follow(UUID source,UUID target) {
		UUID id=UUID.randomUUID();jdbc.update("insert into student_follow(id,academy_id,source_id,target_id,started_at) values (?,?,?,?,?)",id,PRIMARY_ACADEMY_ID,source,target,Timestamp.from(COMPLETED));return id;
	}
	private void attachPhoto() {
		jdbc.update("insert into wish_photo(id,owner_student_id,attached_wish_id,state,content_digest,object_prefix,created_at,expires_at) values (?,?,?,'ATTACHED',?,?,?,?)",UUID.randomUUID(),owner,wish,"a".repeat(64),"test-photo/"+wish,Timestamp.from(CREATED),Timestamp.from(COMPLETED));
	}
	@SuppressWarnings("unchecked") private Map<String,Object> story() throws Exception {
		var response=asOwner(get(PATH)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
				.andExpect(jsonPath("$.status").value("SUCCEEDED")).andReturn().getResponse();
		Map<String,Object> body=mapper.readValue(response.getContentAsString(),Map.class);
		return ((List<Map<String,Object>>)((Map<?,?>)((Map<?,?>)body.get("result")).get("page3AcademySuccessStories")).get("stories")).getFirst();
	}
}
