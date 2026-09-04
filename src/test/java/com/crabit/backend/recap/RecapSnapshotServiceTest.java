package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RecapSnapshotServiceTest {
	@Test void representativeWishAchievementUsesTheViewerZeroToOneHundredScale() {
		assertThat(RecapSnapshotService.achievementRate(25_000, 100_000)).isEqualTo(25.0);
		assertThat(RecapSnapshotService.achievementRate(-1, 100_000)).isZero();
		assertThat(RecapSnapshotService.achievementRate(150_000, 100_000)).isEqualTo(100.0);
		assertThatThrownBy(() -> RecapSnapshotService.achievementRate(1, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
