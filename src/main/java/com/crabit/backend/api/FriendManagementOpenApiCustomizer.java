package com.crabit.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.lang.reflect.Field;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Keeps generated Springdoc schemas byte-semantically aligned with the approved static contract
 * while the repository contract is available. Packaged runtimes still retain the annotated
 * schemas when the source checkout is not present.
 */
@Component
public class FriendManagementOpenApiCustomizer implements OpenApiCustomizer {

	private static final Field REQUIRED_FIELD = requiredField();

	private static final List<String> SCHEMAS = List.of(
			"RelationshipState", "FriendRequestStatus", "StudentSummary", "StudentRelationship",
			"StudentRelationshipPage", "Friend", "FriendPage", "CreateFriendRequestRequest",
			"FriendRequest", "FriendRequestPage", "CreateStudentBlockRequest", "StudentBlock",
			"StudentBlockPage");

	private final Map<String, Schema> approvedSchemas;

	public FriendManagementOpenApiCustomizer() {
		Path path = Path.of("api", "openapi.yaml");
		if (!Files.isRegularFile(path)) {
			approvedSchemas = Map.of();
			return;
		}
		try {
			ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
			Map<?, ?> document = mapper.readValue(path.toFile(), Map.class);
			Map<?, ?> components = (Map<?, ?>) document.get("components");
			Map<?, ?> schemas = (Map<?, ?>) components.get("schemas");
			Map<String, Schema> parsed = new LinkedHashMap<>();
			SCHEMAS.forEach(name -> {
				Object normalized = normalizeNullableTypes(schemas.get(name));
				Schema schema = mapper.convertValue(normalized, Schema.class);
				promoteOpenApi31Types(schema);
				restoreRequiredOrder(normalized, schema);
				parsed.put(name, schema);
			});
			approvedSchemas = Map.copyOf(parsed);
		} catch (IOException exception) {
			throw new IllegalStateException("Approved OpenAPI contract cannot be parsed", exception);
		}
	}

	@SuppressWarnings("unchecked")
	private static Object normalizeNullableTypes(Object value) {
		if (value instanceof Map<?, ?> raw) {
			Map<String, Object> normalized = new LinkedHashMap<>();
			raw.forEach((key, child) -> normalized.put(key.toString(), normalizeNullableTypes(child)));
			Object type = normalized.get("type");
			if (type instanceof List<?> types && types.contains("null") && types.size() == 2) {
				normalized.put("type", types.stream().filter(candidate -> !"null".equals(candidate))
						.findFirst().orElseThrow());
				normalized.put("nullable", true);
			}
			return normalized;
		}
		if (value instanceof List<?> values) {
			return values.stream().map(FriendManagementOpenApiCustomizer::normalizeNullableTypes).toList();
		}
		return value;
	}

	private static void promoteOpenApi31Types(Schema schema) {
		if (schema == null) {
			return;
		}
		if (schema.getType() != null) {
			LinkedHashSet<String> types = new LinkedHashSet<>();
			types.add(schema.getType());
			if (Boolean.TRUE.equals(schema.getNullable())) {
				types.add("null");
			}
			schema.setTypes(types);
		}
		if (schema.getProperties() != null) {
			schema.getProperties().values().forEach(value -> promoteOpenApi31Types((Schema) value));
		}
		promoteOpenApi31Types(schema.getItems());
		if (schema.getAllOf() != null) schema.getAllOf().forEach(value -> promoteOpenApi31Types((Schema) value));
		if (schema.getOneOf() != null) schema.getOneOf().forEach(value -> promoteOpenApi31Types((Schema) value));
		if (schema.getAnyOf() != null) schema.getAnyOf().forEach(value -> promoteOpenApi31Types((Schema) value));
	}

	private static void restoreRequiredOrder(Object raw, Schema schema) {
		if (!(raw instanceof Map<?, ?> map) || schema == null) {
			return;
		}
		if (map.get("required") instanceof List<?> required) {
			setRequiredInApprovedOrder(schema, required.stream().map(Object::toString).toList());
		}
		if (map.get("properties") instanceof Map<?, ?> properties && schema.getProperties() != null) {
			properties.forEach((name, child) -> restoreRequiredOrder(
					child, (Schema) schema.getProperties().get(name.toString())));
		}
		if (map.get("items") != null) restoreRequiredOrder(map.get("items"), schema.getItems());
		restoreRequiredOrderList(map.get("allOf"), schema.getAllOf());
		restoreRequiredOrderList(map.get("oneOf"), schema.getOneOf());
		restoreRequiredOrderList(map.get("anyOf"), schema.getAnyOf());
	}

	private static Field requiredField() {
		try {
			Field field = Schema.class.getDeclaredField("required");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static void setRequiredInApprovedOrder(Schema schema, List<String> required) {
		try {
			REQUIRED_FIELD.set(schema, List.copyOf(required));
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("Approved OpenAPI required order cannot be retained", exception);
		}
	}

	private static void restoreRequiredOrderList(Object raw, List schemas) {
		if (!(raw instanceof List<?> values) || schemas == null) return;
		for (int index = 0; index < Math.min(values.size(), schemas.size()); index++) {
			restoreRequiredOrder(values.get(index), (Schema) schemas.get(index));
		}
	}

	@Override
	public void customise(OpenAPI generated) {
		if (approvedSchemas.isEmpty()) {
			return;
		}
		SCHEMAS.forEach(name -> generated.getComponents().addSchemas(
				name, approvedSchemas.get(name)));
		canonicalSuccess(generated, "/v1/academies/{academyId}/students", "StudentRelationshipPage");
		canonicalSuccess(generated, "/v1/academies/{academyId}/friends", "FriendPage");
		canonicalSuccess(generated, "/v1/academies/{academyId}/friend-requests/sent", "FriendRequestPage");
		canonicalSuccess(generated, "/v1/academies/{academyId}/friend-requests/received", "FriendRequestPage");
		canonicalSuccess(generated, "/v1/me/student-blocks", "StudentBlockPage");
	}

	private static void canonicalSuccess(OpenAPI openApi, String path, String schemaName) {
		var operation = openApi.getPaths().get(path).getGet();
		operation.getResponses().get("200").setContent(new Content().addMediaType(
				"application/json", new MediaType().schema(new Schema<>().$ref(
						"#/components/schemas/" + schemaName))));
	}
}
