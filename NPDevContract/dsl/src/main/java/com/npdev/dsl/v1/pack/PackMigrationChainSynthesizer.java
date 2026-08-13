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
 */
public final class PackMigrationChainSynthesizer {

    private PackMigrationChainSynthesizer() {
    }

    public static ObjectNode applyComposedRenames(ObjectNode rawPackNode, PackMigrationComposer.ComposedRenames composed) {
        if (rawPackNode == null) {
            throw new IllegalArgumentException("rawPackNode must not be null");
        }
        if (composed.isEmpty()) {
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
            if (originalConceptName != null) {
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
