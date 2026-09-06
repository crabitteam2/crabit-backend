package com.crabit.backend.recap;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecapRegenerationCommandTest {
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
	@Test void requiresExactlyTheCanonicalCompletedTargetAndAnExplicitUuidKey() {
		var args = args("MONTHLY", "2026-08"); assertThat(RecapRegenerationCommand.parse(args, clock).kind()).isEqualTo(RecapKind.MONTHLY);
		for (String period : new String[]{"2026-09", "2026-8", "0000-08", "-0001-08", "10000-08"})
			assertThatThrownBy(() -> RecapRegenerationCommand.parse(args("MONTHLY", period), clock)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RecapRegenerationCommand.parse(args("WEEKLY", "0000-01-03"), clock)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RecapRegenerationCommand.parse(args("WEEKLY", "2026-08-25"), clock)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RecapRegenerationCommand.parse(new String[]{args[0],args[1],args[2],args[3],"--spring.main.web-application-type=servlet"},clock)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RecapRegenerationCommand.parse(new String[]{args[0],args[1],args[2],args[3],args[0]},clock)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RecapRegenerationCommand.parse(new String[]{args[0],args[1],args[2]},clock)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RecapRegenerationCommand.parse(new String[]{args[0],args[1],args[2],"--request-key=1-1-1-1-1"},clock)).isInstanceOf(IllegalArgumentException.class);
	}
	private String[] args(String kind, String period) { return new String[]{"--account="+UUID.randomUUID(),"--kind="+kind,"--period="+period,"--request-key="+UUID.randomUUID()}; }
}
