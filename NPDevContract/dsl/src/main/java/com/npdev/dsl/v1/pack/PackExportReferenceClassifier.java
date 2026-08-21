package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * PACK-14: the single implementation of the "export concepts to a pack" reference-classification
 * rules, so the generated {@code GeneratedPackCatalogController.exportConceptToPack} and the CLI
 * {@code npdev pack export} do not each grow their own copy. Rules:
 * <ul>
 *   <li>a target whose bare name is among the exported concepts is rewritten to INTRA-PACK form
 *       (the bare name), regardless of any qualifier it carried;</li>
 *   <li>a target with an {@code otherPack::Name} qualifier naming a real sibling pack under
 *       {@code packsRoot} is left AS-IS and {@code otherPack} is recorded as a {@code ^major.minor}
 *       cross-pack dependency;</li>
 *   <li>anything else is UNRESOLVED and must be refused by the caller (or recorded in
 *       {@code metadata.unresolvedReferences}), never dropped silently.</li>
 * </ul>
 */
public final class PackExportReferenceClassifier {

    /** Result of a classification pass: rewrites applied in-place, plus what to record on the pack. */
    public record Result(List<Map<String, String>> rewrites,
                         Map<String, String> crossPackVersions,
                         List<Map<String, String>> unresolved) {
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private PackExportReferenceClassifier() {
    }

    /**
     * Classifies and rewrites, in place, every reference-bearing string on the exported concepts.
     *
     * @param exportedConcepts deep copies of the concepts being exported (mutated in place)
     * @param exportSet       the bare names of every exported concept (what travels with the pack)
     * @param packsRoot       the directory whose subdirectories are candidate sibling packs
     */
    public static Result classify(List<ObjectNode> exportedConcepts, Set<String> exportSet, Path packsRoot) {
        List<Map<String, String>> rewrites = new ArrayList<>();
        Map<String, String> crossPackVersions = new LinkedHashMap<>();
        List<Map<String, String>> unresolved = new ArrayList<>();

        for (ObjectNode concept : exportedConcepts) {
            String conceptName = concept.path("name").asText("?");
            JsonNode satelliteOf = concept.get("satelliteOf");
            if (satelliteOf != null && satelliteOf.isTextual() && !satelliteOf.asText().isBlank()) {
                classify(conceptName + ".satelliteOf", satelliteOf.asText(),
                        value -> concept.put("satelliteOf", value),
                        exportSet, packsRoot, rewrites, crossPackVersions, unresolved);
            }
            JsonNode fields = concept.get("fields");
            if (fields != null && fields.isArray()) {
                for (JsonNode field : fields) {
                    if (!(field instanceof ObjectNode fieldObject)) {
                        continue;
                    }
                    String fieldName = fieldObject.path("name").asText("?");
                    JsonNode reference = fieldObject.get("reference");
                    if (reference != null && reference.isTextual() && !reference.asText().isBlank()) {
                        classify(conceptName + "." + fieldName + ".reference", reference.asText(),
                                value -> fieldObject.put("reference", value),
                                exportSet, packsRoot, rewrites, crossPackVersions, unresolved);
                    } else if (reference != null && reference.isObject()) {
                        JsonNode target = reference.get("target");
                        if (target != null && target.isTextual() && !target.asText().isBlank()) {
                            classify(conceptName + "." + fieldName + ".reference.target", target.asText(),
                                    value -> ((ObjectNode) reference).put("target", value),
                                    exportSet, packsRoot, rewrites, crossPackVersions, unresolved);
                        }
                    }
                }
            }
        }
        return new Result(rewrites, crossPackVersions, unresolved);
    }

    private static void classify(String fieldLabel, String target, Consumer<String> setter,
                                 Set<String> exportSet, Path packsRoot,
                                 List<Map<String, String>> rewrites,
                                 Map<String, String> crossPackVersions,
                                 List<Map<String, String>> unresolved) {
        String localName = target.contains("::") ? target.substring(target.indexOf("::") + 2) : target;
        if (exportSet.contains(localName)) {
            if (!target.equals(localName)) {
                setter.accept(localName);
                rewrites.add(Map.of("field", fieldLabel, "from", target, "to", localName));
            }
            return;
        }
        if (target.contains("::")) {
            String prefix = target.substring(0, target.indexOf("::"));
            Path siblingPackJson = packsRoot.resolve(prefix).resolve("pack.json");
            if (Files.isRegularFile(siblingPackJson)) {
                crossPackVersions.putIfAbsent(prefix, readVersionConstraint(siblingPackJson));
                return;
            }
        }
        unresolved.add(Map.of("field", fieldLabel, "target", target));
    }

    private static String readVersionConstraint(Path siblingPackJson) {
        try {
            JsonNode sibling = OBJECT_MAPPER.readTree(siblingPackJson.toFile());
            String version = sibling.path("version").asText("").trim();
            String[] parts = version.split("\\.");
            if (parts.length >= 2 && parts[0].matches("\\d+") && parts[1].matches("\\d+")) {
                return "^" + parts[0] + "." + parts[1];
            }
        } catch (IOException ignored) {
            // Unreadable sibling -> fall through to the ^0.0 floor rather than failing the export.
        }
        return "^0.0";
    }
}
