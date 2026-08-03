package com.npdev.generator.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.ast.ConceptAccessAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.InvariantAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.QueryAst;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The NPDev AI Authoring Contract's Custodian enforcement (E2, "the load-bearing piece" --
 * {@code AI_AUTHORING_CONTRACT-2026-07-31.md} Part 9). Derives the REAL diff between a previous
 * and submitted model.json and NEVER trusts the Author's manifest as fact (C2) -- the manifest
 * states intent, this class checks that intent against reality:
 *
 * <ul>
 *   <li>{@code previousModelSha256} matches the previous model's ACTUAL bytes (F6 -- the
 *       compare-and-swap that stops two authoring sessions from silently clobbering each other)</li>
 *   <li>{@code submittedModelVersion > previousModelVersion}, and both match what the real models
 *       declare (A7)</li>
 *   <li>every removed concept/field name is accounted for by exactly one of a real
 *       {@code renamedFrom} marker in the submitted model, or {@code manifest.deliberateRemovals}
 *       (A2, C2) -- an unaccounted removal is a hard failure</li>
 *   <li>every {@code renamedFrom} in the submitted model names something that existed in the
 *       previous model (A4) -- catches a hallucinated rename</li>
 *   <li>a rename does not also change the field's shape in the same step (A5)</li>
 *   <li>a name declared as a deliberate removal does not still exist in the submitted model (A3)
 *       -- catches a retired name reused for a different thing</li>
 *   <li>every {@code access}/{@code permissionRequirements}/{@code invariant}/{@code sensitive}
 *       delta is declared in {@code manifest.securityChanges} (A9, A10, E6)</li>
 * </ul>
 *
 * <p><b>Never infers a rename (C3).</b> This class only ever checks that a rename the AUTHOR
 * already declared (via the submitted model's own {@code renamedFrom} field, the real mechanism
 * the schema-diff engine trusts) is consistent with reality. It is never the one supplying the
 * old→new mapping -- that guess is exactly what {@code docs/ACCEPTED_BOUNDARIES.md} boundary B1
 * forbids.
 */
public final class AuthoringDiffGate {

    private AuthoringDiffGate() {
    }

    public enum Severity { ERROR, WARNING }

    public record Violation(String code, Severity severity, String message, String path, String suggestedFix) {
        static Violation error(String code, String message, String path, String suggestedFix) {
            return new Violation(code, Severity.ERROR, message, path, suggestedFix);
        }
    }

    public record GateResult(List<Violation> violations) {
        public GateResult {
            violations = List.copyOf(violations);
        }

        public boolean passed() {
            return violations.stream().noneMatch(v -> v.severity() == Severity.ERROR);
        }

        public List<Violation> errors() {
            return violations.stream().filter(v -> v.severity() == Severity.ERROR).toList();
        }
    }

    public static GateResult evaluate(
            String actualPreviousModelSha256Hex,
            ModelAst previousModel,
            ModelAst submittedModel,
            JsonNode manifest
    ) {
        List<Violation> violations = new ArrayList<>();

        if (manifest == null || manifest.isMissingNode() || manifest.isNull()) {
            // C1: refuse an undiffed submission outright -- every other check is meaningless
            // without a manifest to check against.
            violations.add(Violation.error("AUTHORING_MANIFEST_MISSING",
                    "No submission manifest was supplied. An authoring submission against an "
                            + "existing app MUST carry a manifest (AI_AUTHORING_CONTRACT Part 6, rule C1).",
                    null, "Supply a npdev-authoring-submission.v1 manifest alongside the submitted model."));
            return new GateResult(violations);
        }

        checkPreviousModelSha256(actualPreviousModelSha256Hex, manifest, violations);
        checkVersionBump(previousModel, submittedModel, manifest, violations);

        Map<String, ConceptAst> previousConcepts = byName(previousModel.getConcepts(), ConceptAst::getName);
        Map<String, ConceptAst> submittedConcepts = byName(submittedModel.getConcepts(), ConceptAst::getName);

        // Concept-level rename resolution: a submitted concept whose renamedFrom names a previous
        // concept is "the same concept, formerly <old name>" for every check below.
        Map<String, String> submittedToPreviousConceptName = new LinkedHashMap<>();
        for (ConceptAst submitted : submittedModel.getConcepts()) {
            String renamedFrom = normalize(submitted.getRenamedFrom());
            if (renamedFrom != null && previousConcepts.containsKey(renamedFrom)) {
                submittedToPreviousConceptName.put(submitted.getName(), renamedFrom);
            }
        }

        checkConceptRenamedFromExists(submittedModel, previousConcepts, violations);
        checkConceptRemovalsAccounted(previousModel, submittedConcepts, submittedToPreviousConceptName, manifest, violations);
        checkNoReusedRemovedConceptName(submittedConcepts, manifest, violations);

        for (ConceptAst submittedConcept : submittedModel.getConcepts()) {
            String previousConceptName = submittedToPreviousConceptName.getOrDefault(submittedConcept.getName(), submittedConcept.getName());
            ConceptAst previousConcept = previousConcepts.get(previousConceptName);
            if (previousConcept == null) {
                continue; // a genuinely new concept -- nothing to diff against
            }
            checkFieldRenamedFromExists(submittedConcept, previousConcept, violations);
            checkFieldRemovalsAccounted(submittedConcept, previousConcept, manifest, violations);
            checkNoReusedRemovedFieldName(submittedConcept, previousConcept, manifest, violations);
            checkNoRenameShapeChange(submittedConcept, previousConcept, violations);
            checkAccessDeltaDeclared(submittedConcept, previousConcept, manifest, violations);
            checkSensitiveDeltaDeclared(submittedConcept, previousConcept, manifest, violations);
            checkInvariantDeltaDeclared(submittedConcept, previousConcept, manifest, violations);
        }

        checkPermissionRequirementsDeltaDeclared(
                "procedure", previousModel.getProcedures(), submittedModel.getProcedures(),
                ProcedureAst::name, ProcedureAst::permissionRequirements, manifest, violations);
        checkPermissionRequirementsDeltaDeclared(
                "query", previousModel.getQueries(), submittedModel.getQueries(),
                QueryAst::name, QueryAst::permissionRequirements, manifest, violations);

        return new GateResult(violations);
    }

    /**
     * S5 ({@code __OutsideRepo\s5\S5_SPEC.md} H3/I4, {@code AuthoringMergeGate}): the SAME
     * access/sensitive/invariant/permissionRequirements delta detection {@link #evaluate} runs,
     * exposed standalone so a MERGE can check the delta between BASE and the MERGED whole (a
     * document neither Author individually submitted a manifest against) without re-running the
     * unrelated SHA/version/rename/removal checks, which do not apply to a synthesized merge
     * result. Reuses {@code AUTHORING_UNDECLARED_SECURITY_CHANGE} verbatim (H3: "reuse it").
     */
    static List<Violation> securityDeltaViolations(ModelAst previousModel, ModelAst submittedModel, JsonNode manifest) {
        List<Violation> violations = new ArrayList<>();
        Map<String, ConceptAst> previousConcepts = byName(previousModel.getConcepts(), ConceptAst::getName);

        for (ConceptAst submittedConcept : submittedModel.getConcepts()) {
            ConceptAst previousConcept = previousConcepts.get(submittedConcept.getName());
            if (previousConcept == null) {
                continue; // a genuinely new concept -- nothing to have weakened
            }
            checkAccessDeltaDeclared(submittedConcept, previousConcept, manifest, violations);
            checkSensitiveDeltaDeclared(submittedConcept, previousConcept, manifest, violations);
            checkInvariantDeltaDeclared(submittedConcept, previousConcept, manifest, violations);
        }

        checkPermissionRequirementsDeltaDeclared(
                "procedure", previousModel.getProcedures(), submittedModel.getProcedures(),
                ProcedureAst::name, ProcedureAst::permissionRequirements, manifest, violations);
        checkPermissionRequirementsDeltaDeclared(
                "query", previousModel.getQueries(), submittedModel.getQueries(),
                QueryAst::name, QueryAst::permissionRequirements, manifest, violations);

        return violations;
    }

    // ------------------------------------------------------------------------------------------
    // F6 / A7
    // ------------------------------------------------------------------------------------------

    private static void checkPreviousModelSha256(String actualHex, JsonNode manifest, List<Violation> violations) {
        String declared = textOrNull(manifest, "previousModelSha256");
        if (declared == null) {
            violations.add(Violation.error("AUTHORING_SHA_MISSING",
                    "manifest.previousModelSha256 is required.", "previousModelSha256", null));
            return;
        }
        String declaredHex = declared.trim().toLowerCase(Locale.ROOT);
        if (declaredHex.startsWith("sha256:")) {
            declaredHex = declaredHex.substring("sha256:".length());
        }
        if (!declaredHex.equals(actualHex.toLowerCase(Locale.ROOT))) {
            violations.add(Violation.error("AUTHORING_SHA_MISMATCH",
                    "manifest.previousModelSha256 does not match the previous model's actual content. "
                            + "Either the manifest was built against a different model than the one supplied "
                            + "as --previous, or another submission already changed the base out from under "
                            + "this one (F6 -- two authoring sessions racing).",
                    "previousModelSha256",
                    "Re-fetch the current model (I1), recompute its SHA-256, and re-derive the submission "
                            + "from that fresh base."));
        }
    }

    private static void checkVersionBump(ModelAst previousModel, ModelAst submittedModel, JsonNode manifest, List<Violation> violations) {
        String manifestPrevious = textOrNull(manifest, "previousModelVersion");
        String manifestSubmitted = textOrNull(manifest, "submittedModelVersion");
        String actualPrevious = previousModel.getVersion();
        String actualSubmitted = submittedModel.getVersion();

        if (manifestPrevious != null && actualPrevious != null && !manifestPrevious.equals(actualPrevious)) {
            violations.add(Violation.error("AUTHORING_VERSION_MISMATCH",
                    "manifest.previousModelVersion (" + manifestPrevious + ") does not match the previous "
                            + "model's actual version (" + actualPrevious + ").",
                    "previousModelVersion", null));
        }
        if (manifestSubmitted != null && actualSubmitted != null && !manifestSubmitted.equals(actualSubmitted)) {
            violations.add(Violation.error("AUTHORING_VERSION_MISMATCH",
                    "manifest.submittedModelVersion (" + manifestSubmitted + ") does not match the submitted "
                            + "model's actual version (" + actualSubmitted + ").",
                    "submittedModelVersion", null));
        }
        if (actualPrevious != null && actualSubmitted != null && compareVersions(actualSubmitted, actualPrevious) <= 0) {
            violations.add(Violation.error("AUTHORING_VERSION_NOT_INCREASED",
                    "version did not increase: previous=" + actualPrevious + " submitted=" + actualSubmitted
                            + " (A7 -- version MUST strictly increase on every submission).",
                    "version", "Bump the submitted model's version above " + actualPrevious + "."));
        }
    }

    /** Dotted-numeric comparison (1.4.0 vs 1.5.0); falls back to a plain string compare for any
     *  non-numeric component so an unconventional version string still gets a deterministic answer
     *  rather than crashing the gate. Package-private: {@code AuthoringMergeGate} (S5, H4) reuses
     *  this exact comparator to pick the merged model's version rather than a second copy. */
    static int compareVersions(String a, String b) {
        String[] partsA = a.split("\\.");
        String[] partsB = b.split("\\.");
        int length = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < length; i++) {
            String pa = i < partsA.length ? partsA[i] : "0";
            String pb = i < partsB.length ? partsB[i] : "0";
            Integer numA = tryParseInt(pa);
            Integer numB = tryParseInt(pb);
            int cmp = (numA != null && numB != null) ? Integer.compare(numA, numB) : pa.compareTo(pb);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static Integer tryParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // A2 / A3 / A4 / A5 / C2 -- concept-level
    // ------------------------------------------------------------------------------------------

    private static void checkConceptRenamedFromExists(ModelAst submittedModel, Map<String, ConceptAst> previousConcepts, List<Violation> violations) {
        for (ConceptAst submitted : submittedModel.getConcepts()) {
            String renamedFrom = normalize(submitted.getRenamedFrom());
            if (renamedFrom != null && !previousConcepts.containsKey(renamedFrom)) {
                violations.add(Violation.error("AUTHORING_HALLUCINATED_RENAME",
                        "Concept '" + submitted.getName() + "' declares renamedFrom '" + submitted.getRenamedFrom()
                                + "', which does not exist in the previous model (A4 -- a hallucinated rename "
                                + "is worse than no declaration, because it looks authoritative).",
                        "concepts[" + submitted.getName() + "].renamedFrom", null));
            }
        }
    }

    private static void checkConceptRemovalsAccounted(
            ModelAst previousModel, Map<String, ConceptAst> submittedConcepts,
            Map<String, String> submittedToPreviousConceptName, JsonNode manifest, List<Violation> violations
    ) {
        Set<String> renamedAwayFrom = new LinkedHashSet<>(submittedToPreviousConceptName.values());
        Set<String> declaredRemovedConcepts = manifestNames(manifest, "deliberateRemovals", "concept", null);
        for (ConceptAst previous : previousModel.getConcepts()) {
            String name = previous.getName();
            if (submittedConcepts.containsKey(name) || renamedAwayFrom.contains(name)) {
                continue; // still present, or explained by a real renamedFrom marker
            }
            if (!declaredRemovedConcepts.contains(name)) {
                violations.add(Violation.error("AUTHORING_UNACCOUNTED_REMOVAL",
                        "Concept '" + name + "' is present in the previous model and absent from the "
                                + "submission, but is not accounted for by a renamedFrom marker or a "
                                + "manifest.deliberateRemovals entry (A2, C2 -- every removal MUST carry "
                                + "an explicit intent).",
                        "concepts[" + name + "]",
                        "Either add renamedFrom:\"" + name + "\" to whatever concept replaces it, or add "
                                + "a deliberateRemovals entry naming it and why."));
            }
        }
    }

    private static void checkNoReusedRemovedConceptName(Map<String, ConceptAst> submittedConcepts, JsonNode manifest, List<Violation> violations) {
        for (String removedName : manifestNames(manifest, "deliberateRemovals", "concept", null)) {
            if (submittedConcepts.containsKey(removedName)) {
                violations.add(Violation.error("AUTHORING_REUSED_REMOVED_NAME",
                        "Concept '" + removedName + "' is declared as a deliberate removal but still exists "
                                + "in the submitted model (A3 -- a name MUST NOT be reused for a different "
                                + "thing in the same submission).",
                        "concepts[" + removedName + "]",
                        "Use a different name for the new concept, or retire the removal declaration in a "
                                + "later submission once the old name is free."));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // A2 / A3 / A4 / A5 / C2 -- field-level (within one resolved concept pair)
    // ------------------------------------------------------------------------------------------

    private static void checkFieldRenamedFromExists(ConceptAst submittedConcept, ConceptAst previousConcept, List<Violation> violations) {
        Set<String> previousFieldNames = fieldNames(previousConcept);
        for (FieldAst field : submittedConcept.getFields()) {
            String renamedFrom = normalize(field.getRenamedFrom());
            if (renamedFrom != null && !previousFieldNames.contains(renamedFrom)) {
                violations.add(Violation.error("AUTHORING_HALLUCINATED_RENAME",
                        "Field '" + submittedConcept.getName() + "." + field.getName() + "' declares "
                                + "renamedFrom '" + field.getRenamedFrom() + "', which does not exist on "
                                + "concept '" + previousConcept.getName() + "' in the previous model (A4).",
                        "concepts[" + submittedConcept.getName() + "].fields[" + field.getName() + "].renamedFrom",
                        null));
            }
        }
    }

    private static void checkFieldRemovalsAccounted(ConceptAst submittedConcept, ConceptAst previousConcept, JsonNode manifest, List<Violation> violations) {
        Set<String> submittedFieldNames = fieldNames(submittedConcept);
        Set<String> renamedAwayFrom = new LinkedHashSet<>();
        for (FieldAst field : submittedConcept.getFields()) {
            String renamedFrom = normalize(field.getRenamedFrom());
            if (renamedFrom != null) {
                renamedAwayFrom.add(renamedFrom);
            }
        }
        Set<String> declaredRemovedFields = manifestNames(manifest, "deliberateRemovals", "concept", previousConcept.getName());
        for (FieldAst previousField : previousConcept.getFields()) {
            String name = previousField.getName();
            if (submittedFieldNames.contains(name) || renamedAwayFrom.contains(name)) {
                continue;
            }
            if (!declaredRemovedFields.contains(name)) {
                violations.add(Violation.error("AUTHORING_UNACCOUNTED_REMOVAL",
                        "Field '" + previousConcept.getName() + "." + name + "' is present in the previous "
                                + "model and absent from the submission, but is not accounted for by a "
                                + "renamedFrom marker or a manifest.deliberateRemovals entry (A2, C2).",
                        "concepts[" + previousConcept.getName() + "].fields[" + name + "]",
                        "Either add renamedFrom:\"" + name + "\" to whatever field replaces it, or add a "
                                + "deliberateRemovals entry naming it and why."));
            }
        }
    }

    private static void checkNoReusedRemovedFieldName(ConceptAst submittedConcept, ConceptAst previousConcept, JsonNode manifest, List<Violation> violations) {
        Set<String> submittedFieldNames = fieldNames(submittedConcept);
        for (String removedName : manifestNames(manifest, "deliberateRemovals", "concept", previousConcept.getName())) {
            if (submittedFieldNames.contains(removedName)) {
                violations.add(Violation.error("AUTHORING_REUSED_REMOVED_NAME",
                        "Field '" + previousConcept.getName() + "." + removedName + "' is declared as a "
                                + "deliberate removal but still exists in the submitted model (A3).",
                        "concepts[" + submittedConcept.getName() + "].fields[" + removedName + "]", null));
            }
        }
    }

    private static void checkNoRenameShapeChange(ConceptAst submittedConcept, ConceptAst previousConcept, List<Violation> violations) {
        Map<String, FieldAst> previousFieldsByName = byName(previousConcept.getFields(), FieldAst::getName);
        for (FieldAst field : submittedConcept.getFields()) {
            String renamedFrom = normalize(field.getRenamedFrom());
            if (renamedFrom == null) {
                continue;
            }
            FieldAst previousField = previousFieldsByName.get(renamedFrom);
            if (previousField == null) {
                continue; // already reported by checkFieldRenamedFromExists
            }
            boolean shapeChanged = !Objects.equals(normalize(previousField.getType()), normalize(field.getType()))
                    || previousField.isRequired() != field.isRequired()
                    || previousField.isUnique() != field.isUnique();
            if (shapeChanged) {
                violations.add(Violation.error("AUTHORING_RENAME_WITH_SHAPE_CHANGE",
                        "Field '" + previousConcept.getName() + "." + renamedFrom + "' was renamed to '"
                                + field.getName() + "' AND its shape changed in the same submission "
                                + "(type/required/unique) -- A5: a rename and a shape change MUST be split "
                                + "into separate submissions so the migration planner sees one intent per step.",
                        "concepts[" + submittedConcept.getName() + "].fields[" + field.getName() + "]",
                        "Submit the rename alone first; change the type/required/unique flag in a follow-up "
                                + "submission."));
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // A9 / A10 / E6 -- security-relevant delta detector
    // ------------------------------------------------------------------------------------------

    private static void checkAccessDeltaDeclared(ConceptAst submittedConcept, ConceptAst previousConcept, JsonNode manifest, List<Violation> violations) {
        ConceptAccessAst previousAccess = previousConcept.getAccess();
        ConceptAccessAst submittedAccess = submittedConcept.getAccess();
        String previousRead = previousAccess == null ? null : previousAccess.getRead();
        String submittedRead = submittedAccess == null ? null : submittedAccess.getRead();
        String previousWrite = previousAccess == null ? null : previousAccess.getWrite();
        String submittedWrite = submittedAccess == null ? null : submittedAccess.getWrite();

        if (!Objects.equals(previousRead, submittedRead)
                && !securityChangeDeclared(manifest, "access.read", submittedConcept.getName())) {
            violations.add(securityViolation("access.read", submittedConcept.getName(), previousRead, submittedRead));
        }
        if (!Objects.equals(previousWrite, submittedWrite)
                && !securityChangeDeclared(manifest, "access.write", submittedConcept.getName())) {
            violations.add(securityViolation("access.write", submittedConcept.getName(), previousWrite, submittedWrite));
        }
    }

    private static void checkSensitiveDeltaDeclared(ConceptAst submittedConcept, ConceptAst previousConcept, JsonNode manifest, List<Violation> violations) {
        Map<String, FieldAst> previousFieldsByName = byName(previousConcept.getFields(), FieldAst::getName);
        for (FieldAst field : submittedConcept.getFields()) {
            FieldAst previousField = previousFieldsByName.get(field.getName());
            if (previousField == null) {
                continue; // a new field -- nothing to have weakened
            }
            if (previousField.isSensitive() != field.isSensitive()
                    && !securityChangeDeclared(manifest, "sensitive", submittedConcept.getName())) {
                violations.add(securityViolation("sensitive",
                        submittedConcept.getName() + "." + field.getName(),
                        String.valueOf(previousField.isSensitive()), String.valueOf(field.isSensitive())));
            }
        }
    }

    private static void checkInvariantDeltaDeclared(ConceptAst submittedConcept, ConceptAst previousConcept, JsonNode manifest, List<Violation> violations) {
        Set<String> previousSignatures = invariantSignatures(previousConcept.getInvariants());
        Set<String> submittedSignatures = invariantSignatures(submittedConcept.getInvariants());
        if (!previousSignatures.equals(submittedSignatures)
                && !securityChangeDeclared(manifest, "invariant", submittedConcept.getName())) {
            violations.add(securityViolation("invariant", submittedConcept.getName(),
                    String.join("; ", previousSignatures), String.join("; ", submittedSignatures)));
        }
    }

    private static Set<String> invariantSignatures(List<InvariantAst> invariants) {
        Set<String> signatures = new LinkedHashSet<>();
        for (InvariantAst invariant : invariants) {
            signatures.add(normalize(invariant.getType()) + ":" + normalize(invariant.getExpression()) + ":"
                    + String.join(",", invariant.getFields() == null ? List.of() : invariant.getFields()));
        }
        return signatures;
    }

    private static <T> void checkPermissionRequirementsDeltaDeclared(
            String kindLabel, List<T> previousItems, List<T> submittedItems,
            java.util.function.Function<T, String> nameOf, java.util.function.Function<T, List<String>> permsOf,
            JsonNode manifest, List<Violation> violations
    ) {
        Map<String, T> previousByName = byName(previousItems, nameOf);
        for (T submitted : submittedItems) {
            T previous = previousByName.get(nameOf.apply(submitted));
            if (previous == null) {
                continue;
            }
            List<String> previousPerms = permsOf.apply(previous);
            List<String> submittedPerms = permsOf.apply(submitted);
            if (!new LinkedHashSet<>(previousPerms).equals(new LinkedHashSet<>(submittedPerms))
                    && !securityChangeDeclared(manifest, "permissionRequirements", nameOf.apply(submitted))) {
                violations.add(securityViolation("permissionRequirements (" + kindLabel + ")",
                        nameOf.apply(submitted), String.join(",", previousPerms), String.join(",", submittedPerms)));
            }
        }
    }

    private static Violation securityViolation(String kind, String subject, String from, String to) {
        return Violation.error("AUTHORING_UNDECLARED_SECURITY_CHANGE",
                "'" + kind + "' changed on '" + subject + "' (from " + quote(from) + " to " + quote(to)
                        + ") but is not declared in manifest.securityChanges (A9, A10 -- a security-relevant "
                        + "change MUST be declared separately and MUST NOT be bundled with unrelated work).",
                subject, "Add a securityChanges entry: {\"kind\":\"" + kind + "\",\"concept\":\"" + subject
                        + "\",\"from\":...,\"to\":...,\"rationale\":\"...\"}.");
    }

    private static String quote(String value) {
        return value == null ? "<absent>" : "\"" + value + "\"";
    }

    private static boolean securityChangeDeclared(JsonNode manifest, String kind, String subject) {
        JsonNode array = manifest.get("securityChanges");
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode entry : array) {
            String entryKind = textOrNull(entry, "kind");
            String entrySubject = textOrNull(entry, "concept");
            if (kind.equals(entryKind) && subject.equals(entrySubject)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------------------------------

    private static <T> Map<String, T> byName(List<T> items, java.util.function.Function<T, String> nameOf) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            map.put(nameOf.apply(item), item);
        }
        return map;
    }

    private static Set<String> fieldNames(ConceptAst concept) {
        Set<String> names = new LinkedHashSet<>();
        for (FieldAst field : concept.getFields()) {
            names.add(field.getName());
        }
        return names;
    }

    /** Names from manifest.<arrayKey>[] whose kind=="field" and concept==conceptFilter (or
     *  kind=="concept" when conceptFilter is null), read from the "name"/"from" field depending
     *  on which array this is (deliberateRemovals uses "name"; renames uses "from" for the old
     *  name) -- callers of this helper only ever pass "deliberateRemovals" today, so it reads
     *  "name". */
    private static Set<String> manifestNames(JsonNode manifest, String arrayKey, String kindFilterConceptOrField, String conceptFilter) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode array = manifest.get(arrayKey);
        if (array == null || !array.isArray()) {
            return names;
        }
        for (JsonNode entry : array) {
            String kind = textOrNull(entry, "kind");
            String concept = textOrNull(entry, "concept");
            String name = textOrNull(entry, "name");
            if (name == null) {
                continue;
            }
            if (conceptFilter == null) {
                if ("concept".equals(kind)) {
                    names.add(name);
                }
            } else {
                if ("field".equals(kind) && conceptFilter.equals(concept)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
