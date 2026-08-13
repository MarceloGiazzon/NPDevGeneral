package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * PK-4 Stage D: injects a {@link PackMigrationComposer.ComposedRenames} result onto a pack's raw
 * {@code concepts[]} JSON, as an ordinary {@code renamedFrom} value -- exactly where a human author
 * would already put one by hand. This is the whole of Stage D's contract: nothing downstream (the
 * generator's merge, {@code SchemaRealizationEmitter}, or any RuntimeHost schema-engine class) has
 * to know a marker was synthesized rather than typed, because it is byte-for-byte the same shape
 * either way. {@code renamedFrom} is already schema-legal here -- {@code pack.schema.json}'s
 * {@code concepts[]} entries are the same {@code model.schema.json#/$defs/concept}/{@code #/$defs/
 * field} definitions a model author uses, both of which already declare {@code renamedFrom}.
 *
 * <p><b>The physical-qualifier hazard (found by running the real end-to-end proof, not by
 * design review).</b> PK-2 bakes a pack's own major version into every one of its concepts'
 * physical table names ({@code ModelSourceResolver.recordPhysicalQualifiers}: {@code
 * "<packId>_v<major>"}) -- and since a rename is BREAKING (Stage A) and BREAKING requires at
 * least a major bump (Stage B), EVERY rename-bearing chain hop, by construction, also crosses a
 * major-version boundary. That means a hop's rename is invisible to the schema engine unless the
 * TABLE's own physical identity is also declared as renamed, or {@code SchemaLifecycleExecutor}
 * sees a wholly different table name and treats it as an ordinary drop+create -- exactly the
 * destructive failure this whole card exists to prevent, just one layer up from the column it
 * already handles correctly. When the caller supplies a non-blank {@code oldPhysicalQualifier}
 * (computed from the composed range's {@code fromVersion} whenever its major differs from {@code
 * toVersion}'s), every concept in the pack gets a QUALIFIED {@code renamedFrom} -- {@code
 * "<oldQualifier>::<bareName>"} -- regardless of whether that concept's OWN bare name changed.
 * This reuses {@code SchemaRealizationEmitter.conceptTableRename}'s existing {@code
 * renamedFrom}-non-blank branch and {@code SqlIdentifierSupport}'s existing {@code toSnake}
 * (which already turns {@code "::"} into {@code "_"}) unmodified -- no new consumption machinery,
 * just a value in the shape that machinery already expects.
 */
public final class PackMigrationChainSynthesizer {

    private PackMigrationChainSynthesizer() {
    }

    /**
     * @param oldPhysicalQualifier the pack's own {@code "<packId>_v<major>"} qualifier as of the
     *                             composed range's start version, or blank/null if the major
     *                             version did not change across the range (the common case for a
     *                             minor/patch-only regenerate, where no table identity shifted).
     */
    public static ObjectNode applyComposedRenames(
            ObjectNode rawPackNode, PackMigrationComposer.ComposedRenames composed, String oldPhysicalQualifier) {
        if (rawPackNode == null) {
            throw new IllegalArgumentException("rawPackNode must not be null");
        }
        boolean qualifierShifted = oldPhysicalQualifier != null && !oldPhysicalQualifier.isBlank();
        if (composed.isEmpty() && !qualifierShifted) {
            return rawPackNode.deepCopy();
        }

        ObjectNode copy = rawPackNode.deepCopy();
        JsonNode conceptsNode = copy.get("concepts");
        if (conceptsNode == null || !conceptsNode.isArray()) {
            return copy;
        }
        for (JsonNode conceptNode : (ArrayNode) conceptsNode) {
            if (!conceptNode.isObject()) {
                continue;
            }
            ObjectNode concept = (ObjectNode) conceptNode;
            String conceptName = textOrBlank(concept, "name");
            if (conceptName.isBlank()) {
                continue;
            }

            String originalConceptName = composed.conceptRenames().get(conceptName);
            if (qualifierShifted) {
                String oldBareName = originalConceptName != null ? originalConceptName : conceptName;
                applyRenamedFrom(concept, "concepts['" + conceptName + "']", oldPhysicalQualifier + "::" + oldBareName);
            } else if (originalConceptName != null) {
                applyRenamedFrom(concept, "concepts['" + conceptName + "']", originalConceptName);
            }

            Map<String, String> fieldRenames = composed.fieldRenamesByConcept().get(conceptName);
            if (fieldRenames == null || fieldRenames.isEmpty()) {
                continue;
            }
            JsonNode fieldsNode = concept.get("fields");
            if (fieldsNode == null || !fieldsNode.isArray()) {
                continue;
            }
            for (JsonNode fieldNode : (ArrayNode) fieldsNode) {
                if (!fieldNode.isObject()) {
                    continue;
                }
                ObjectNode field = (ObjectNode) fieldNode;
                String fieldName = textOrBlank(field, "name");
                String originalFieldName = fieldRenames.get(fieldName);
                if (originalFieldName != null) {
                    applyRenamedFrom(field, "concepts['" + conceptName + "'].fields['" + fieldName + "']", originalFieldName);
                }
            }
        }
        return copy;
    }

    private static void applyRenamedFrom(ObjectNode target, String location, String originalName) {
        JsonNode existing = target.get("renamedFrom");
        if (existing != null && existing.isTextual() && !existing.asText().isBlank()) {
            String handAuthored = existing.asText();
            if (!handAuthored.equals(originalName)) {
                throw new IllegalArgumentException(
                        location + " already declares renamedFrom '" + handAuthored
                                + "', which conflicts with the migration chain's own composed value '" + originalName
                                + "' -- remove the hand-authored declaration or make it agree with the chain");
            }
            return;
        }
        target.put("renamedFrom", originalName);
    }

    private static String textOrBlank(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }
}
