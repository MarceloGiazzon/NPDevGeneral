package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-4 Stage A: one test per classification rule {@code PackDiffEngine} promises, over small
 * synthetic pack.json pairs -- no database, no filesystem, matching the engine's own pure-function
 * contract.
 */
class PackDiffEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode pack(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static PackChangeClassification classificationOf(List<PackDiffFinding> findings, String path) {
        return findings.stream()
                .filter(f -> f.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding at path " + path + " among " + findings))
                .classification();
    }

    @Test
    void identicalPacks_produceNoFindings() {
        String json = """
                {
                  "dslVersion": "1.0.0",
                  "pack": "widgets",
                  "version": "1.0.0",
                  "concepts": [
                    { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """;
        PackDiffResult result = PackDiffEngine.diff(pack(json), pack(json));
        assertTrue(result.isEmpty(), "identical documents must produce zero findings, got: " + result.findings());
        assertTrue(result.worstClassification().isEmpty());
    }

    @Test
    void newNullableFieldOnExistingConcept_isAdditive() {
        JsonNode oldPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);
        JsonNode newPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "nickname", "type": "string", "required": false }
                    ] }
                  ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.ADDITIVE), result.worstClassification());
        assertEquals(PackChangeClassification.ADDITIVE,
                classificationOf(result.findings(), "concepts.Widget.fields.nickname"));
    }

    @Test
    void fieldRemoved_isBreaking() {
        JsonNode oldPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "nickname", "type": "string", "required": false }
                    ] }
                  ]
                }
                """);
        JsonNode newPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0",
                  "concepts": [
                    { "name": "Widget", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.BREAKING), result.worstClassification());
        assertEquals(PackChangeClassification.BREAKING,
                classificationOf(result.findings(), "concepts.Widget.fields.nickname"));
    }

    @Test
    void descriptionOnlyChange_isPatch() {
        JsonNode oldPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "description": "Widgets, first draft.",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);
        JsonNode newPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.1",
                  "description": "Widgets, now with a better README paragraph.",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(1, result.findings().size(), "expected exactly one finding, got: " + result.findings());
        assertEquals(PackChangeClassification.PATCH, result.findings().get(0).classification());
    }

    @Test
    void fieldDescriptionOnlyChange_isPatch() {
        JsonNode oldPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string", "required": true, "description": "old copy" }
                  ] } ]
                }
                """);
        JsonNode newPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.1",
                  "concepts": [ { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string", "required": true, "description": "new copy" }
                  ] } ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.PATCH), result.worstClassification());
    }

    /**
     * PK-4's explicit, conservative rule: there is no migration-chain mechanism yet (that's Stage C,
     * a future card's job), so this engine does NOT try to detect "this is really a rename" at all --
     * it has no special "declared rename" concept. A rename is simply what removing the old field
     * name and adding the new one looks like: one BREAKING finding (the removal) and one ADDITIVE
     * finding (the addition), and the pack-level aggregate is the worse of the two, BREAKING. This
     * test exists specifically to document that this is intentional, not a missed rename-detection
     * feature -- see PackDiffEngine's class doc and PackChangeClassification#BREAKING's javadoc.
     */
    @Test
    void fieldRenameHasNoDedicatedDetection_soItIsClassifiedBreakingViaRemovePlusAdd() {
        JsonNode oldPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "oldName", "type": "string", "required": false }
                  ] } ]
                }
                """);
        JsonNode newPack = pack("""
                {
                  "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0",
                  "concepts": [ { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "newName", "type": "string", "required": false }
                  ] } ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.BREAKING), result.worstClassification());
        assertEquals(PackChangeClassification.BREAKING,
                classificationOf(result.findings(), "concepts.Widget.fields.oldName"),
                "the old field name must be seen as removed (BREAKING)");
        assertEquals(PackChangeClassification.ADDITIVE,
                classificationOf(result.findings(), "concepts.Widget.fields.newName"),
                "the new field name must be seen as added (ADDITIVE) -- no rename detection exists");
    }

    @Test
    void wholeNewConcept_isAdditive() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                  { "name": "Gadget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.ADDITIVE), result.worstClassification());
        assertEquals(PackChangeClassification.ADDITIVE, classificationOf(result.findings(), "concepts.Gadget"));
    }

    @Test
    void conceptRemoved_isBreaking() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                  { "name": "Gadget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.BREAKING), result.worstClassification());
        assertEquals(PackChangeClassification.BREAKING, classificationOf(result.findings(), "concepts.Gadget"));
    }

    @Test
    void newPanel_isAdditive() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "panels": [ { "name": "WidgetPanel", "route": "/widgets" } ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.ADDITIVE), result.worstClassification());
        assertEquals(PackChangeClassification.ADDITIVE, classificationOf(result.findings(), "panels.WidgetPanel"));
    }

    @Test
    void panelRemoved_isBreaking() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "panels": [ { "name": "WidgetPanel", "route": "/widgets" } ]
                }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.BREAKING), result.worstClassification());
        assertEquals(PackChangeClassification.BREAKING, classificationOf(result.findings(), "panels.WidgetPanel"));
    }

    @Test
    void newQuery_isAdditive() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "queries": [ { "name": "AllWidgets", "concept": "Widget" } ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.ADDITIVE), result.worstClassification());
        assertEquals(PackChangeClassification.ADDITIVE, classificationOf(result.findings(), "queries.AllWidgets"));
    }

    @Test
    void newProcedure_isAdditive() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "procedures": [ { "name": "ArchiveWidget" } ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(Optional.of(PackChangeClassification.ADDITIVE), result.worstClassification());
        assertEquals(PackChangeClassification.ADDITIVE, classificationOf(result.findings(), "procedures.ArchiveWidget"));
    }

    @Test
    void fieldTypeNarrowedOrRetyped_isBreaking() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "quantity", "type": "string", "required": true }
                  ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "quantity", "type": "int", "required": true }
                  ] }
                ] }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(PackChangeClassification.BREAKING,
                classificationOf(result.findings(), "concepts.Widget.fields.quantity.type"));
    }

    @Test
    void fieldMadeRequired_isBreaking() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string", "required": false }
                  ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string", "required": true }
                  ] }
                ] }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(PackChangeClassification.BREAKING,
                classificationOf(result.findings(), "concepts.Widget.fields.sku.required"));
    }

    @Test
    void fieldRelaxedFromRequiredToOptional_isAdditive() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string", "required": true }
                  ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string", "required": false }
                  ] }
                ] }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertEquals(PackChangeClassification.ADDITIVE,
                classificationOf(result.findings(), "concepts.Widget.fields.sku.required"));
    }

    @Test
    void reorderingAListOfImportsWithNoOtherChange_producesNoFinding() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "imports": ["alpha", "beta"],
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "imports": ["beta", "alpha"],
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertTrue(result.isEmpty(), "pure reordering must not be reported: " + result.findings());
    }

    @Test
    void versionAndSchemaPointerChanges_areNeverDiffed() {
        JsonNode oldPack = pack("""
                { "$schema": "../../schemas/pack.schema.json", "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);
        JsonNode newPack = pack("""
                { "$schema": "pack.schema.json", "dslVersion": "1.0.0", "pack": "widgets", "version": "9.9.9",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);

        PackDiffResult result = PackDiffEngine.diff(oldPack, newPack);

        assertTrue(result.isEmpty(), "$schema and version must be excluded from the diff itself: " + result.findings());
    }

    @Test
    void nonObjectInputsAreRejected() {
        JsonNode notAnObject = pack("[1, 2, 3]");
        JsonNode validPack = pack("{ \"dslVersion\": \"1.0.0\", \"pack\": \"widgets\", \"version\": \"1.0.0\" }");
        assertFalse(notAnObject.isObject());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PackDiffEngine.diff(notAnObject, validPack));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PackDiffEngine.diff(validPack, notAnObject));
    }
}
