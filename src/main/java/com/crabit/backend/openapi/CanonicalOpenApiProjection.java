package com.crabit.backend.openapi;

import com.crabit.backend.openapi.CanonicalOpenApiDocument.OperationKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CanonicalOpenApiProjection {
	private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
	private final ObjectNode canonical;

	public CanonicalOpenApiProjection(CanonicalOpenApiDocument document) {
		canonical = document.root();
	}

	public ObjectNode project(Set<OperationKey> implemented) {
		ObjectNode projected = canonical.deepCopy();
		ObjectNode paths = projected.objectNode();
		canonical.withObject("paths").properties().forEach(entry -> {
			ObjectNode retained = entry.getValue().deepCopy();
			METHODS.forEach(method -> {
				if (!implemented.contains(new OperationKey(method, entry.getKey()))) retained.remove(method);
			});
			if (METHODS.stream().anyMatch(retained::has)) paths.set(entry.getKey(), retained);
		});
		projected.set("paths", paths);
		Set<String> usedTags = new LinkedHashSet<>();
		walk(paths, node -> {
			if (node.get("tags") instanceof ArrayNode tags) tags.forEach(tag -> usedTags.add(tag.asText()));
		});
		if (canonical.path("tags") instanceof ArrayNode tags) {
			ArrayNode retained = projected.arrayNode();
			tags.forEach(tag -> { if (usedTags.contains(tag.path("name").asText())) retained.add(tag.deepCopy()); });
			projected.set("tags", retained);
		}
		projected.set("components", closure(paths));
		return projected;
	}

	private ObjectNode closure(JsonNode paths) {
		ObjectNode source = canonical.withObject("components");
		ObjectNode target = canonical.objectNode();
		Deque<String> pending = new ArrayDeque<>();
		Set<String> seen = new LinkedHashSet<>();
		collect(paths, pending);
		while (!pending.isEmpty()) {
			String ref = pending.removeFirst();
			if (!seen.add(ref)) continue;
			String[] parts = ref.substring("#/components/".length()).split("/", 2);
			JsonNode value = parts.length == 2 ? source.path(parts[0]).path(parts[1]) : null;
			if (value == null || value.isMissingNode()) throw new IllegalStateException("Missing canonical component: " + ref);
			target.withObject(parts[0]).set(parts[1], value.deepCopy());
			collect(value, pending);
		}
		return target;
	}

	private static void collect(JsonNode root, Deque<String> refs) {
		walk(root, node -> {
			JsonNode ref = node.get("$ref");
			if (ref != null && ref.isTextual() && ref.asText().startsWith("#/components/")) refs.add(ref.asText());
			if (node.get("security") instanceof ArrayNode security) security.forEach(
					requirement -> requirement.fieldNames().forEachRemaining(
							name -> refs.add("#/components/securitySchemes/" + name)));
			JsonNode mapping = node.path("discriminator").path("mapping");
			if (mapping.isObject()) mapping.elements().forEachRemaining(value -> {
				if (value.isTextual() && value.asText().startsWith("#/components/")) refs.add(value.asText());
			});
		});
	}

	private static void walk(JsonNode node, java.util.function.Consumer<ObjectNode> visitor) {
		if (node instanceof ObjectNode object) {
			visitor.accept(object);
			object.elements().forEachRemaining(child -> walk(child, visitor));
		} else if (node instanceof ArrayNode array) array.forEach(child -> walk(child, visitor));
	}
}
