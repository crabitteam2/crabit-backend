package com.crabit.backend.wish;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SharedCardCompletionMapperTest {
	@Test void preservesDatesAndClampsNegativeDurationIndependentlyOfCalendarDates() {
		var created=Instant.parse("2026-09-01T00:00:00Z");
		var row=new SharedCardQueryRepository.Row(UUID.randomUUID(),SharedCardKind.COMPLETION,UUID.randomUUID(),"name",12,UUID.randomUUID(),UUID.randomUUID(),Instant.EPOCH,null,UUID.randomUUID(),"purpose",1000,0,WishState.COMPLETED,LocalDate.parse("2026-09-02"),LocalDate.parse("2026-09-03"),created,created.minusSeconds(1),null,created,false);
		var result=SharedCardCompletionMapper.project(row,null);
		assertThat(result.actualDurationSeconds()).isZero();
		assertThat(result.startDate()).isEqualTo(LocalDate.parse("2026-09-02"));
		assertThat(result.targetDate()).isEqualTo(LocalDate.parse("2026-09-03"));
		assertThat(result.photo()).isNull(); assertThat(result.progressPercent()).isEqualTo(100);
	}
}
