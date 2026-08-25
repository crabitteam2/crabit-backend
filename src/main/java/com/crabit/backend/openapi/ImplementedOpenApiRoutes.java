package com.crabit.backend.openapi;

import com.crabit.backend.openapi.CanonicalOpenApiDocument.OperationKey;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

public final class ImplementedOpenApiRoutes implements SmartInitializingSingleton {
	private static final Pattern REGEX_VARIABLE = Pattern.compile("\\{([^}:]+):[^}]+}");
	private final RequestMappingHandlerMapping mappings;
	private final Set<OperationKey> canonical;
	private Set<OperationKey> implemented = Set.of();

	public ImplementedOpenApiRoutes(RequestMappingHandlerMapping mappings, CanonicalOpenApiDocument document) {
		this.mappings = mappings;
		canonical = document.operationKeys();
	}

	@Override
	public void afterSingletonsInstantiated() {
		Set<OperationKey> discovered = new LinkedHashSet<>();
		mappings.getHandlerMethods().forEach((mapping, handler) -> add(mapping, handler, discovered));
		Set<OperationKey> missing = new LinkedHashSet<>(discovered);
		missing.removeAll(canonical);
		if (!missing.isEmpty()) throw new IllegalStateException("Implemented /v1 routes are absent from canonical OpenAPI: " + missing);
		implemented = Set.copyOf(discovered);
	}

	public Set<OperationKey> operationKeys() { return implemented; }

	private static void add(RequestMappingInfo mapping, HandlerMethod handler, Set<OperationKey> result) {
		if (AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), Hidden.class)
				|| AnnotatedElementUtils.hasAnnotation(handler.getMethod(), Hidden.class)) return;
		Set<RequestMethod> methods = mapping.getMethodsCondition().getMethods();
		for (String raw : mapping.getPatternValues()) {
			String path = REGEX_VARIABLE.matcher(raw).replaceAll("{$1}");
			if (!path.startsWith("/v1/")) continue;
			if (methods.isEmpty()) throw new IllegalStateException("Eligible /v1 mapping must declare method: " + path);
			methods.forEach(method -> result.add(new OperationKey(method.name().toLowerCase(Locale.ROOT), path)));
		}
	}
}
