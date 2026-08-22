package com.npdev.generator.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S5 ({@code __OutsideRepo\s5\S5_SPEC.md}) -- multi-author merge at element granularity. Raises
 * {@link AuthoringDiffGate}'s whole-document compare-and-swap ceiling: today, {@code X != Y}
 * (the base moved under a submission) always refuses, however disjoint the two authors' actual
 * edits were. This class computes A = touched(BASE, OURS) and B = touched(BASE, THEIRS) via
 * {@link ElementDiffer} and, when {@code A ∩ B = ∅}, applies THEIRS onto OURS instead of refusing.
 *
 * <p>Four hard rules from the spec, each with a corresponding step below:
 * <ul>
 *   <li><b>H1</b> -- conservative by construction: a {@code wholeDocument} result on EITHER side
 *       refuses unconditionally, never "probably disjoint".</li>
 *   <li><b>H2</b> -- element-disjoint does not mean semantically valid: the merged whole is run
 *       through the REAL {@link SemanticValidator}, not just re-checked for disjointness.</li>
 *   <li><b>H3</b> -- security routing survives the merge: any access/permissionRequirements/
 *       invariant/sensitive delta on EITHER side, undeclared by either side's manifest, refuses
 *       via {@link AuthoringDiffGate#securityDeltaViolations}, reusing
 *       {@code AUTHORING_UNDECLARED_SECURITY_CHANGE} verbatim.</li>
 *   <li><b>H4</b> -- the result is a new base: the merged model gets its own version (the higher
 *       of OURS'/THEIRS') and its own SHA-256, computed here, never re-derived by a caller.</li>
 * </ul>
 *
 * <p><b>Why {@code version} is stripped before diffing (see {@link #withoutVersion}).</b>
 * {@link ElementDiffer} has no special case for {@code version} -- a real submission always bumps
 * it (A7), so if the differ's raw opinion were used directly, EVERY merge attempt would collapse to
 * {@code wholeDocument} purely because both sides' version differs from BASE, which would make
 * disjoint-element merging impossible by construction. Version's OWN bump discipline is
 * {@link AuthoringDiffGate}'s job (A7); this merge computes CONTENT disjointness only, and assigns
 * the merged result its own version per H4 below.
 */
public final class AuthoringMergeGate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuthoringMergeGate() {
    }

    public record Violation(String code, String message, String path) {
        static Violation of(String code, String message, String path) {
            return new Violation(code, message, path);
        }
    }

    public record MergeResult(
            List<Violation> violations,
            ObjectNode mergedModel,
            String mergedVersion,
            String mergedModelSha256Hex,
            Set<ElementDiffer.ElementKey> elementsFromOurs,
            Set<ElementDiffer.ElementKey> elementsFromTheirs
    ) {
        public MergeResult {
            violations = List.copyOf(violations);
            // REG-175/REG-146: AuthoringDiffGateMain writes these straight into report/mergedManifest
            // JSON "elementsFromOurs"/"elementsFromTheirs" arrays via unsorted iteration -- Set.copyOf's
            // JEP 269 iteration-order randomization was reaching that emitted output.
            elementsFromOurs = elementsFromOurs == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(elementsFromOurs));
            elementsFromTheirs = elementsFromTheirs == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(elementsFromTheirs));
        }

        public boolean merged() {
            return violations.isEmpty() && mergedModel != null;
        }
    }

    public static MergeResult merge(
            JsonNode baseJson, ModelAst baseModel,
            JsonNode oursJson, ModelAst oursModel,
            JsonNode theirsJson, ModelAst theirsModel,
            JsonNode oursManifest, JsonNode theirsManifest
    ) {
        ElementDiffer.DiffResult oursDiff = ElementDiffer.diff(withoutVersion(baseJson), withoutVersion(oursJson));
        ElementDiffer.DiffResult theirsDiff = ElementDiffer.diff(withoutVersion(baseJson), withoutVersion(theirsJson));

        if (oursDiff.wholeDocument() || theirsDiff.wholeDocument()) {
            List<String> reasons = new ArrayList<>();
            reasons.addAll(oursDiff.wholeDocumentReasons());
            reasons.addAll(theirsDiff.wholeDocumentReasons());
            return refused("AUTHORING_MERGE_WHOLE_DOCUMENT_CONFLICT",
                    "At least one side's change cannot be attributed to a specific element, so a "
                            + "disjoint-element merge cannot be attempted (H1: never guess). Reasons: "
                            + String.join("; ", reasons),
                    Set.of(), Set.of());
        }

        Set<ElementDiffer.ElementKey> oursTouched = oursDiff.touchedKeys();
        Set<ElementDiffer.ElementKey> theirsTouched = theirsDiff.touchedKeys();
        Set<ElementDiffer.ElementKey> collision = new LinkedHashSet<>(oursTouched);
        collision.retainAll(theirsTouched);
        if (!collision.isEmpty()) {
            return refused("AUTHORING_MERGE_ELEMENT_COLLISION",
                    "Both submissions changed the same element(s): " + collision + " -- refused; "
                            + "resubmit against the current base once it lands.",
                    oursTouched, theirsTouched);
        }

        ObjectNode mergedJson = applyElementChanges(oursJson, theirsJson, theirsDiff);
        String mergedVersion = AuthoringDiffGate.compareVersions(oursModel.getVersion(), theirsModel.getVersion()) >= 0
                ? oursModel.getVersion() : theirsModel.getVersion();
        mergedJson.put("version", mergedVersion);

        ModelAst mergedModelAst;
        try {
            mergedModelAst = new JsonModelParser().parse(mergedJson);
        } catch (IOException exception) {
            return refused("AUTHORING_MERGE_UNPARSEABLE_RESULT",
                    "The merged model failed to parse: " + exception.getMessage(),
                    oursTouched, theirsTouched);
        }

        // H2: element-disjoint does not mean semantically valid -- run the REAL validator.
        List<String> semanticErrors = new SemanticValidator().validate(mergedModelAst);
        if (!semanticErrors.isEmpty()) {
            List<Violation> violations = new ArrayList<>();
            for (String error : semanticErrors) {
                violations.add(Violation.of("AUTHORING_MERGE_INVALID_RESULT", error, null));
            }
            return new MergeResult(violations, null, null, null, oursTouched, theirsTouched);
        }

        // H3: any access/permission/invariant/sensitive delta on EITHER side, undeclared by
        // EITHER side's own manifest, routes to acknowledgement -- never silently composited.
        JsonNode unionManifest = unionSecurityChanges(oursManifest, theirsManifest);
        List<AuthoringDiffGate.Violation> securityViolations =
                AuthoringDiffGate.securityDeltaViolations(baseModel, mergedModelAst, unionManifest);
        if (!securityViolations.isEmpty()) {
            List<Violation> violations = new ArrayList<>();
            for (AuthoringDiffGate.Violation violation : securityViolations) {
                violations.add(Violation.of(violation.code(), violation.message(), violation.path()));
            }
            return new MergeResult(violations, null, null, null, oursTouched, theirsTouched);
        }

        String mergedSha256Hex = sha256Hex(toJson(mergedJson));
        return new MergeResult(List.of(), mergedJson, mergedVersion, mergedSha256Hex, oursTouched, theirsTouched);
    }

    private static MergeResult refused(
            String code, String message,
            Set<ElementDiffer.ElementKey> oursTouched, Set<ElementDiffer.ElementKey> theirsTouched
    ) {
        return new MergeResult(List.of(Violation.of(code, message, null)), null, null, null, oursTouched, theirsTouched);
    }

    /**
     * I2: applies THEIRS' element changes onto a copy of OURS at element granularity -- never by
     * re-serialising a whole document. OURS' existing array order is preserved for retained/
     * modified elements; THEIRS' newly added elements are appended at the end, in THEIRS' own
     * declared order, so the result is deterministic regardless of hash-based iteration.
     */
    private static ObjectNode applyElementChanges(JsonNode oursJson, JsonNode theirsJson, ElementDiffer.DiffResult theirsDiff) {
        ObjectNode merged = ((ObjectNode) oursJson).deepCopy();

        Map<String, List<ElementDiffer.ElementChange>> changesByArrayKey = new LinkedHashMap<>();
        for (ElementDiffer.ElementChange change : theirsDiff.changes()) {
            changesByArrayKey.computeIfAbsent(change.key().arrayKey(), key -> new ArrayList<>()).add(change);
        }

        for (Map.Entry<String, List<ElementDiffer.ElementChange>> entry : changesByArrayKey.entrySet()) {
            String arrayKey = entry.getKey();
            Map<String, JsonNode> mergedByName = new LinkedHashMap<>();
            JsonNode existingArray = merged.get(arrayKey);
            if (existingArray != null && existingArray.isArray()) {
                for (JsonNode element : existingArray) {
                    if (element.isObject() && element.get("name") != null && element.get("name").isTextual()) {
                        mergedByName.put(element.get("name").asText(), element);
                    }
                }
            }
            Map<String, JsonNode> theirsByName = indexArrayByName(theirsJson, arrayKey);

            for (ElementDiffer.ElementChange change : entry.getValue()) {
                String name = change.key().name();
                switch (change.kind()) {
                    case ADDED, MODIFIED -> mergedByName.put(name, theirsByName.get(name));
                    case REMOVED -> mergedByName.remove(name);
                }
            }

            ArrayNode rebuilt = JsonNodeFactory.instance.arrayNode();
            for (JsonNode element : mergedByName.values()) {
                rebuilt.add(element);
            }
            merged.set(arrayKey, rebuilt);
        }
        return merged;
    }

    private static Map<String, JsonNode> indexArrayByName(JsonNode document, String arrayKey) {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        JsonNode array = document.get(arrayKey);
        if (array != null && array.isArray()) {
            for (JsonNode element : array) {
                if (element.isObject() && element.get("name") != null && element.get("name").isTextual()) {
                    byName.put(element.get("name").asText(), element.deepCopy());
                }
            }
        }
        return byName;
    }

    private static ObjectNode withoutVersion(JsonNode json) {
        ObjectNode copy = ((ObjectNode) json).deepCopy();
        copy.remove("version");
        return copy;
    }

    private static JsonNode unionSecurityChanges(JsonNode oursManifest, JsonNode theirsManifest) {
        ArrayNode combined = JsonNodeFactory.instance.arrayNode();
        appendSecurityChanges(combined, oursManifest);
        appendSecurityChanges(combined, theirsManifest);
        ObjectNode manifest = JsonNodeFactory.instance.objectNode();
        manifest.set("securityChanges", combined);
        return manifest;
    }

    private static void appendSecurityChanges(ArrayNode target, JsonNode manifest) {
        if (manifest == null) {
            return;
        }
        JsonNode array = manifest.get("securityChanges");
        if (array != null && array.isArray()) {
            for (JsonNode entry : array) {
                target.add(entry.deepCopy());
            }
        }
    }

    /** I2's own round-trip DoD: {@code toJson(fromJson(toJson(m))) == toJson(m)}. */
    public static String toJson(ObjectNode model) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(model) + "\n";
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize merged model as JSON", exception);
        }
    }

    public static ObjectNode fromJson(String json) throws IOException {
        JsonNode node = MAPPER.readTree(json);
        if (!(node instanceof ObjectNode objectNode)) {
            throw new IOException("Merged model JSON must be a JSON object");
        }
        return objectNode;
    }

    private static String sha256Hex(String json) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required to hash the merged model", exception);
        }
    }
}
