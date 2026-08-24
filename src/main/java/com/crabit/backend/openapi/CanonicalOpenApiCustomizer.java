package com.crabit.backend.openapi;

import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;

public final class CanonicalOpenApiCustomizer implements OpenApiCustomizer {
	private final CanonicalOpenApiProjection projection;
	private final ImplementedOpenApiRoutes routes;

	public CanonicalOpenApiCustomizer(CanonicalOpenApiProjection projection, ImplementedOpenApiRoutes routes) {
		this.projection = projection;
		this.routes = routes;
	}

	@Override
	public void customise(OpenAPI generated) {
		try {
			List<Server> requestServers = generated.getServers();
			OpenAPI canonical = Json31.mapper().treeToValue(projection.project(routes.operationKeys()), OpenAPI.class);
			generated.setSpecVersion(canonical.getSpecVersion()); generated.setOpenapi(canonical.getOpenapi());
			generated.setInfo(canonical.getInfo()); generated.setExternalDocs(canonical.getExternalDocs());
			generated.setServers(requestServers == null || requestServers.isEmpty()
					? canonical.getServers()
					: requestServers);
			generated.setSecurity(canonical.getSecurity());
			generated.setTags(canonical.getTags()); generated.setPaths(canonical.getPaths());
			generated.setComponents(canonical.getComponents()); generated.setWebhooks(canonical.getWebhooks());
			generated.setJsonSchemaDialect(canonical.getJsonSchemaDialect()); generated.setExtensions(canonical.getExtensions());
		} catch (Exception exception) {
			throw new IllegalStateException("Canonical OpenAPI projection cannot be materialized", exception);
		}
	}
}
