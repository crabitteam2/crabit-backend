package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.crabit.backend.account.CardBalanceAccount;
import com.crabit.backend.account.CardBalanceAccountRepository;
import com.crabit.backend.wish.SharedCardQueryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RecapQueryServicePrivacyTest {
	@Test void omitsStoredStoriesThatAreNoLongerVisibleWithoutExposingInternalMetrics(){
		UUID accountId=UUID.randomUUID(),student=UUID.randomUUID(),academy=UUID.randomUUID(),wish=UUID.randomUUID();
		var accounts=mock(CardBalanceAccountRepository.class); var generations=mock(RecapGenerationRepository.class); var cards=mock(SharedCardQueryRepository.class);
		when(accounts.findById(accountId)).thenReturn(Optional.of(CardBalanceAccount.reconstitute(accountId,student,academy,Instant.EPOCH,null)));
		var generation=new RecapGeneration(UUID.randomUUID(),accountId,student,academy,RecapKind.WEEKLY,LocalDate.parse("2026-08-24"),LocalDate.parse("2026-08-31"),1,"sha256:x","{}",Instant.EPOCH);
		generation.succeed("{\"page3_academy_success_stories\":{\"message_summary\":\"private\",\"stories\":[{\"wish_id\":\""+wish+"\",\"type_title\":\"GOAL\"}]}}","{\"secret\":true}",Instant.parse("2026-08-31T00:00:00Z")); generation.makeCurrent();
		when(generations.findFirstByAccountIdAndKindAndPeriodStartAndPeriodEndExclusiveAndCurrentVersionTrueOrderByGenerationVersionDesc(accountId,RecapKind.WEEKLY,LocalDate.parse("2026-08-24"),LocalDate.parse("2026-08-31"))).thenReturn(Optional.of(generation));
		when(cards.findVisibleWishIds(eq(student),eq(academy),any(),eq(5))).thenReturn(List.of());
		var service=new RecapQueryService(accounts,generations,cards,new ObjectMapper(),Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"),ZoneOffset.UTC));
		var response=service.weekly(student,academy,accountId,"2026-08-24");
		@SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)response.result();
		@SuppressWarnings("unchecked") Map<String,Object> page=(Map<String,Object>)result.get("page3AcademySuccessStories");
		assertThat(page.get("stories")).isEqualTo(List.of()); assertThat(page.get("messageSummary")).isEqualTo("현재 볼 수 있는 성공 story가 없어요.");
		assertThat(result.toString()).doesNotContain("secret");
	}
}
