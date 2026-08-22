package com.npdev.dsl.v1.xref;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The ONE serialization of a {@link ReferenceIndex}, shared by the generator's {@code XrefEmitter}
 * (which writes {@code npdev/model-xref.json} into every generated app) and by
 * {@code ModelXrefMain} (which the {@code :NPDevContract:dsl:modelXref} Gradle task runs for
 * {@code npdev inspect usage}).
 *
 * <p>Deliberately one class rather than two writers: the CLI and the app read the SAME document,
 * and two writers of "the same" shape is how a consumer ends up correct against one producer and
 * quietly wrong against the other. Contract: {@code npdev-model-xref.v1}, schema
 * {@code schemas/ai/model-xref.schema.json}.
 *
 * <p>Determinism is a hard requirement, not a preference: {@code XrefEmitter}'s output is hashed by
 * {@code check-deterministic-generation.ps1} across two generator runs. Edges arrive already sorted
 * from {@link ReferenceIndex#edges()}, and every node here is an insertion-ordered
 * {@link ObjectNode}.
 */
public final class ReferenceIndexJson {

    public static final String SCHEMA_VERSION = "npdev-model-xref.v1";

    private ReferenceIndexJson() {
    }

    /**
     * @param modelIdentifier how the document names the model it describes -- the namespace for a
     *                        generated app, the model file path for a CLI run. Free text; nothing
     *                        parses it.
     */
    public static ObjectNode toJson(String modelIdentifier, ReferenceIndex index) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("model", modelIdentifier == null ? "" : modelIdentifier);

        int resolved = 0;
        int unresolved = 0;
        int undecidable = 0;
        for (ReferenceEdge edge : index.edges()) {
            switch (edge.resolution()) {
                case RESOLVED -> resolved++;
                case UNRESOLVED -> unresolved++;
                case UNDECIDABLE -> undecidable++;
            }
        }

        ObjectNode summary = JsonNodeFactory.instance.objectNode();
        summary.put("edges", index.edges().size());
        summary.put("resolved", resolved);
        summary.put("unresolved", unresolved);
        summary.put("undecidable", undecidable);
        root.set("summary", summary);

        ArrayNode edges = JsonNodeFactory.instance.arrayNode();
        for (ReferenceEdge edge : index.edges()) {
            edges.add(toJson(edge));
        }
        root.set("edges", edges);
        return root;
    }

    private static ObjectNode toJson(ReferenceEdge edge) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("fromKind", edge.fromKind());
        node.put("fromName", edge.fromName());
        node.put("site", edge.site());
        node.put("path", edge.path());
        node.put("toKind", edge.toKind());
        node.put("toName", edge.toName());
        // Explicitly null rather than absent for a non-field target: an absent key and a null key
        // read the same to a careless consumer, and only one of them is a promise.
        if (edge.ownerConcept() == null || edge.ownerConcept().isBlank()) {
            node.putNull("ownerConcept");
        } else {
            node.put("ownerConcept", edge.ownerConcept());
        }
        node.put("resolution", edge.resolution().name());
        return node;
    }
}
