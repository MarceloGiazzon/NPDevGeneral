package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generator.emitters.customization.model.CustomizationRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Path A P5.2 (NPDEV_PATH_A_REALIGNMENT_PLAN.md; docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md's
 * Customization Registry): reads the OPTIONAL sibling {@code customization-provenance.json} an author
 * may place next to {@code model.json}, declaring what/where/why/who for a customization
 * {@link ExtensionInventoryEmitter} (P0.3) already discovers by construction. Absent file or absent
 * entry both mean "undeclared" -- never a generation failure, per the same "declaring nothing never
 * blocks creation" rule P5.1 already established for the untrusted-extension zone.
 */
final class CustomizationProvenanceManifest {

    private static final String SCHEMA_VERSION = "npdev-customization-provenance.v1";
    private static final Set<String> AUTHOR_TYPES = Set.of("human", "ai");
    private static final Set<String> REGENERATION_INTENTS = Set.of("preserve", "replace", "ask");
    private static final Set<String> RELEASE_IMPACTS = Set.of("none", "minor", "major");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private CustomizationProvenanceManifest() {
    }

    /**
     * @param modelSourcePath the model.json this customization-provenance.json is a sibling of; may be
     *                        null (no provenance available, e.g. no model source on disk).
     * @return records keyed by {@code owner}; empty when the file is absent.
     */
    static Map<String, CustomizationRecord> readSibling(Path modelSourcePath) throws IOException {
        if (modelSourcePath == null || modelSourcePath.getParent() == null) {
            return Map.of();
        }
        Path manifestPath = modelSourcePath.toAbsolutePath().normalize().getParent()
                .resolve("customization-provenance.json").normalize();
        if (!Files.isRegularFile(manifestPath)) {
            return Map.of();
        }
        JsonNode root = OBJECT_MAPPER.readTree(manifestPath.toFile());
        if (!SCHEMA_VERSION.equals(root.path("schemaVersion").asText())) {
            throw new IllegalStateException("Unsupported customization provenance manifest schemaVersion.");
        }
        if (!root.path("entries").isArray()) {
            throw new IllegalStateException("customization-provenance.json entries must be an array.");
        }
        Map<String, CustomizationRecord> records = new LinkedHashMap<>();
        for (JsonNode node : root.path("entries")) {
            CustomizationRecord record = new CustomizationRecord(
                    requiredText(node, "owner"),
                    requiredText(node, "changeSummary"),
                    requiredText(node, "reason"),
                    requiredText(node, "author"),
                    requiredEnum(node, "authorType", AUTHOR_TYPES),
                    requiredEnum(node, "regenerationIntent", REGENERATION_INTENTS),
                    node.path("requiresRetest").asBoolean(),
                    requiredEnum(node, "releaseImpact", RELEASE_IMPACTS)
            );
            if (!node.has("requiresRetest") || !node.path("requiresRetest").isBoolean()) {
                throw new IllegalStateException("customization-provenance.json entry requiresRetest must be a boolean: " + record.owner());
            }
            if (records.put(record.owner(), record) != null) {
                throw new IllegalStateException("customization-provenance.json declares owner more than once: " + record.owner());
            }
        }
        return records;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        String text = value == null || value.isNull() ? "" : value.asText("").trim();
        if (text.isEmpty()) {
            throw new IllegalStateException("customization-provenance.json entry missing required field: " + field);
        }
        return text;
    }

    private static String requiredEnum(JsonNode node, String field, Set<String> allowed) {
        String value = requiredText(node, field);
        if (!allowed.contains(value)) {
            throw new IllegalStateException("customization-provenance.json entry " + field + " must be one of " + allowed + ": " + value);
        }
        return value;
    }
}
