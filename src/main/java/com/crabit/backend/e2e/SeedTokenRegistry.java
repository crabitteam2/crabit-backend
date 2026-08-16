package com.crabit.backend.e2e;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e")
public final class SeedTokenRegistry {

	private final Map<String, SeedPrincipal> principalsByToken;

	public SeedTokenRegistry(SeedFixtureCatalog fixtures) {
		this.principalsByToken = fixtures.personas().stream().collect(Collectors.toUnmodifiableMap(
				SeedFixtureCatalog.Persona::token,
				persona -> new SeedPrincipal(
						persona.id(), persona.role(), persona.academyId(), persona.key())));
	}

	public Optional<SeedPrincipal> resolve(String token) {
		return Optional.ofNullable(principalsByToken.get(token));
	}

	public Map<String, SeedPrincipal> all() {
		return principalsByToken;
	}
}
