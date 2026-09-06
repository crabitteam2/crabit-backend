package com.crabit.backend.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Loads and validates the approved OpenAPI contract from its packaged runtime resource. */
public final class CanonicalOpenApiDocument {

    public static final String RESOURCE_PATH = "META-INF/crabit/openapi/openapi.yaml";
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");

    private final ObjectNode root;
    private final Set<OperationKey> operationKeys;

    public CanonicalOpenApiDocument(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            Object yaml =
                    new org.yaml.snakeyaml.Yaml(
                                    new org.yaml.snakeyaml.constructor.SafeConstructor(
                                            new org.yaml.snakeyaml.LoaderOptions()) {
                                        {
                                            yamlConstructors.put(
                                                    org.yaml.snakeyaml.nodes.Tag.TIMESTAMP,
                                                    new ConstructYamlStr());
                                        }
                                    })
                            .load(input);
            JsonNode parsed = new ObjectMapper().valueToTree(yaml);
            if (!(parsed instanceof ObjectNode object)) {
                throw new IllegalStateException("Packaged OpenAPI contract must be an object");
            }
            this.root = object;
            validateRoot();
            this.operationKeys = Set.copyOf(readOperationKeys(root.path("paths")));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Packaged OpenAPI contract cannot be loaded from " + resource, exception);
        }
    }

    public ObjectNode root() {
        return root.deepCopy();
    }

    public Set<OperationKey> operationKeys() {
        return operationKeys;
    }

    private void validateRoot() {
        if (!root.path("openapi").asText().startsWith("3.1.")) {
            throw new IllegalStateException("Packaged contract must use OpenAPI 3.1");
        }
        if (!root.path("info").isObject()
                || !root.path("paths").isObject()
                || !root.path("components").isObject()) {
            throw new IllegalStateException(
                    "Packaged contract must define info, paths, and components objects");
        }
    }

    private static Set<OperationKey> readOperationKeys(JsonNode paths) {
        Set<OperationKey> keys = new LinkedHashSet<>();
        paths.properties()
                .forEach(
                        path ->
                                path.getValue()
                                        .properties()
                                        .forEach(
                                                operation -> {
                                                    String method =
                                                            operation
                                                                    .getKey()
                                                                    .toLowerCase(Locale.ROOT);
                                                    if (HTTP_METHODS.contains(method)) {
                                                        keys.add(
                                                                new OperationKey(
                                                                        method, path.getKey()));
                                                    }
                                                }));
        return keys;
    }

    public record OperationKey(String method, String path) {
        public OperationKey {
            method = method.toLowerCase(Locale.ROOT);
        }
    }
}
