package com.npdev.adapters.externalai.inproc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * P4 (G3): builds the smallest deterministic instance of a JSON Schema -- the offline "answer" the
 * inproc adapter gives a structured prompt, so a flow calling {@code externalAi.generate} runs end to
 * end in tests and air-gapped apps with no vendor. Honours the keywords a prompt schema realistically
 * uses (type, properties, items, enum, const, minimum, minItems, minLength); anything fancier still
 * yields a well-typed value, and the caller's real schema validation remains the judge.
 */
final class SchemaSampler {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_DEPTH = 8;

    private SchemaSampler() {
    }

    static JsonNode sample(JsonNode schema) {
        return sample(schema, 0);
    }

    private static JsonNode sample(JsonNode schema, int depth) {
        if (schema == null || !schema.isObject() || depth > MAX_DEPTH) {
            return NODES.nullNode();
        }
        if (schema.has("const")) {
            return schema.get("const");
        }
        if (schema.path("enum").isArray() && !schema.path("enum").isEmpty()) {
            return schema.path("enum").get(0);
        }
        String type = schema.path("type").isArray()
                ? schema.path("type").path(0).asText("")
                : schema.path("type").asText("");
        if (type.isEmpty()) {
            type = schema.has("properties") ? "object" : schema.has("items") ? "array" : "string";
        }
        return switch (type) {
            case "object" -> {
                ObjectNode object = NODES.objectNode();
                Iterator<Map.Entry<String, JsonNode>> fields = schema.path("properties").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    object.set(field.getKey(), sample(field.getValue(), depth + 1));
                }
                yield object;
            }
            case "array" -> {
                ArrayNode array = NODES.arrayNode();
                int count = Math.max(1, schema.path("minItems").asInt(1));
                if (schema.has("maxItems")) {
                    count = Math.min(count, schema.path("maxItems").asInt());
                }
                for (int i = 0; i < count; i++) {
                    array.add(sample(schema.path("items"), depth + 1));
                }
                yield array;
            }
            case "integer" -> NODES.numberNode(schema.path("minimum").asLong(0));
            case "number" -> NODES.numberNode(schema.path("minimum").asDouble(0));
            case "boolean" -> NODES.booleanNode(false);
            case "null" -> NODES.nullNode();
            default -> {
                int minLength = schema.path("minLength").asInt(0);
                String value = "offline";
                while (value.length() < minLength) {
                    value = value + "-offline";
                }
                yield NODES.textNode(value);
            }
        };
    }
}
