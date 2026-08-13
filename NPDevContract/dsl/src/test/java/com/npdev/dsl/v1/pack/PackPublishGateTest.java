package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-4 Stage B: {@code PackPublishGate} wraps {@code PackDiffEngine} with the "does the version
 * bump match what the diff requires" refusal rule, plus the additive/patch-only empty
 * migration-chain-entry auto-generation.
 */
class PackPublishGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode pack(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void breakingChangeWithOnlyAPatchBump_isRefusedNamingWhatChangedAndWhatBumpIsRequired() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string", "required": true }
                  ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.1", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] }
                ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertFalse(decision.allowed());
        assertEquals(PackVersionBump.MAJOR, decision.requiredBump());
        assertEquals(PackVersionBump.PATCH, decision.actualBump());
        assertTrue(decision.message().contains("sku"), "refusal must name what changed: " + decision.message());
        assertTrue(decision.message().toLowerCase().contains("major"),
                "refusal must say what bump would have been required: " + decision.message());
        assertFalse(decision.shouldWriteEmptyMigrationEntry());
    }

    @Test
    void additiveChangeWithOnlyAMajorBumpVersion_isAllowed_sinceBiggerBumpsAlwaysSatisfySmallerRequirements() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "nickname", "type": "string", "required": false }
                  ] }
                ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertTrue(decision.allowed());
        assertEquals(PackVersionBump.MINOR, decision.requiredBump());
        assertEquals(PackVersionBump.MAJOR, decision.actualBump());
    }

    @Test
    void additiveOnlyBump_succeeds_andEmptyMigrationChainEntryIsWritten() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "nickname", "type": "string", "required": false }
                  ] }
                ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertTrue(decision.allowed());
        assertEquals(PackVersionBump.MINOR, decision.requiredBump());
        assertEquals(PackVersionBump.MINOR, decision.actualBump());
        assertTrue(decision.shouldWriteEmptyMigrationEntry());

        JsonNode written = PackPublishGate.withEmptyMigrationChainEntry(newPack, "1.0.0", "1.1.0");
        assertTrue(written.has("migrations"));
        assertTrue(written.get("migrations").has("1.0.0 -> 1.1.0"));
        assertTrue(written.get("migrations").get("1.0.0 -> 1.1.0").isArray());
        assertEquals(0, written.get("migrations").get("1.0.0 -> 1.1.0").size());
        // the original document handed in must not be mutated -- withEmptyMigrationChainEntry
        // returns a deep copy, per its own contract.
        assertFalse(newPack.has("migrations"));
    }

    @Test
    void patchOnlyBump_succeeds_andEmptyMigrationChainEntryIsWritten() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "description": "first draft",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.1",
                  "description": "better copy",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertTrue(decision.allowed());
        assertEquals(PackVersionBump.PATCH, decision.requiredBump());
        assertEquals(PackVersionBump.PATCH, decision.actualBump());
        assertTrue(decision.shouldWriteEmptyMigrationEntry());
    }

    @Test
    void patchOnlyChangeWithNoVersionBumpAtAll_isRefused() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "description": "first draft",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "description": "better copy",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertFalse(decision.allowed(), "content changed but the version field was left alone -- must be refused");
        assertEquals(PackVersionBump.PATCH, decision.requiredBump());
        assertEquals(PackVersionBump.NONE, decision.actualBump());
    }

    @Test
    void byteIdenticalRepublishWithNoVersionChange_isAllowed_asATrueNoOp() {
        String json = """
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """;
        PackPublishGate.Decision decision = PackPublishGate.evaluate(pack(json), pack(json));

        assertTrue(decision.allowed());
        assertEquals(PackVersionBump.NONE, decision.requiredBump());
        assertEquals(PackVersionBump.NONE, decision.actualBump());
        assertTrue(decision.shouldWriteEmptyMigrationEntry());
    }

    @Test
    void versionDowngrade_isAlwaysRefused_evenWithNoContentChange() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.9.0", "concepts": [
                  { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertFalse(decision.allowed());
        assertTrue(decision.message().toLowerCase().contains("decrease")
                        || decision.message().toLowerCase().contains("lower"),
                "downgrade refusal must say so plainly: " + decision.message());
    }

    @Test
    void breakingChangeWithACorrectMajorBump_isAllowed_butNoMigrationEntryIsWritten() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "sku", "type": "string", "required": true }
                  ] }
                ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0", "concepts": [
                  { "name": "Widget", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ] }
                ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertTrue(decision.allowed());
        assertEquals(PackVersionBump.MAJOR, decision.requiredBump());
        assertEquals(PackVersionBump.MAJOR, decision.actualBump());
        // Stage C (populating what a breaking migration actually replays) does not exist yet -- an
        // allowed breaking publish must NOT get an empty chain entry that could be mistaken for
        // "nothing further needed".
        assertFalse(decision.shouldWriteEmptyMigrationEntry());
    }

    @Test
    void missingVersionField_isRejectedWithAClearMessage() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets" }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0" }
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PackPublishGate.evaluate(oldPack, newPack));
        assertTrue(ex.getMessage().toLowerCase().contains("version"));
    }

    // ---- Stage C: chain immutability ------------------------------------------------------------

    @Test
    void alteringAnAlreadyPublishedHopRefusesRegardlessOfVersionBump() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0",
                  "migrations": { "1.0.0 -> 2.0.0": [
                    { "op": "renameField", "concept": "Widget", "from": "sku", "to": "code" }
                  ] },
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "code", "type": "string" } ] } ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "3.0.0",
                  "migrations": {
                    "1.0.0 -> 2.0.0": [
                      { "op": "renameField", "concept": "Widget", "from": "sku", "to": "identifier" }
                    ],
                    "2.0.0 -> 3.0.0": []
                  },
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "code", "type": "string" } ] } ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("1.0.0 -> 2.0.0"), decision.message());
        assertTrue(decision.message().toLowerCase().contains("immutable"), decision.message());
    }

    @Test
    void removingAnAlreadyPublishedHopRefuses() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0",
                  "migrations": { "1.0.0 -> 2.0.0": [] },
                  "concepts": [] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "3.0.0",
                  "migrations": { "2.0.0 -> 3.0.0": [] },
                  "concepts": [] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("missing"), decision.message());
    }

    @Test
    void unchangedHistoryPlusAPatchOnlyChangeIsUnaffectedByTheImmutabilityCheck() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0",
                  "migrations": { "1.0.0 -> 2.0.0": [] },
                  "concepts": [ { "name": "Widget", "fields": [] } ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.1", "description": "updated docs",
                  "migrations": { "1.0.0 -> 2.0.0": [] },
                  "concepts": [ { "name": "Widget", "fields": [] } ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertTrue(decision.allowed(), decision.message());
    }

    // ---- Stage C: diff-consistency cross-check --------------------------------------------------

    @Test
    void declaredRenameFieldWithNoMatchingDiffRefuses() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "sku", "type": "string" } ] } ] }
                """);
        // A fabricated rename op: sku was never removed, code was never added -- nothing actually
        // changed on Widget in this diff at all.
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0",
                  "migrations": { "1.0.0 -> 2.0.0": [
                    { "op": "renameField", "concept": "Widget", "from": "sku", "to": "code" }
                  ] },
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "sku", "type": "string" } ] } ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("no matching change"), decision.message());
    }

    @Test
    void declaredRenameFieldMatchingTheRealDiffIsAllowed() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "sku", "type": "string" } ] } ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0",
                  "migrations": { "1.0.0 -> 2.0.0": [
                    { "op": "renameField", "concept": "Widget", "from": "sku", "to": "code" }
                  ] },
                  "concepts": [ { "name": "Widget", "fields": [ { "name": "code", "type": "string" } ] } ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertTrue(decision.allowed(), decision.message());
    }

    @Test
    void declaredAddFieldWithNoMatchingDiffRefuses() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0",
                  "concepts": [ { "name": "Widget", "fields": [] } ] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.1.0",
                  "migrations": { "1.0.0 -> 1.1.0": [
                    { "op": "addField", "concept": "Widget", "field": "notes" }
                  ] },
                  "concepts": [ { "name": "Widget", "fields": [] } ] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("no matching change"), decision.message());
    }

    @Test
    void malformedHopArrayIsRefusedRatherThanThrowing() {
        JsonNode oldPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "1.0.0", "concepts": [] }
                """);
        JsonNode newPack = pack("""
                { "dslVersion": "1.0.0", "pack": "widgets", "version": "2.0.0",
                  "migrations": { "1.0.0 -> 2.0.0": [ { "op": "notARealOp" } ] },
                  "concepts": [] }
                """);

        PackPublishGate.Decision decision = PackPublishGate.evaluate(oldPack, newPack);

        assertFalse(decision.allowed());
        assertTrue(decision.message().contains("malformed"), decision.message());
    }
}
