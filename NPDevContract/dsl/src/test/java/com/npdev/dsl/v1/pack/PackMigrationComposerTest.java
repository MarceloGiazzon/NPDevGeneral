package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-4 Stage C: {@link PackMigrationComposer}'s walk-and-collapse algorithm -- the piece that must
 * get "identity@1.0 straight to @3.0, skipping @2.0" exactly right, since a wrong composed marker
 * either destroys data (names the wrong ancestor) or refuses correct upgrades (too conservative).
 */
class PackMigrationComposerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PackVersion v(String s) {
        return PackVersion.parse(s);
    }

    private static PackMigrationChain chain(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return PackMigrationChain.parse(node);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static PackMigrationComposer.Composed composedOf(PackMigrationComposer.Result result) {
        return assertInstanceOf(PackMigrationComposer.Composed.class, result);
    }

    private static PackMigrationComposer.Refused refusedOf(PackMigrationComposer.Result result) {
        return assertInstanceOf(PackMigrationComposer.Refused.class, result);
    }

    @Test
    void sameVersionIsANoOp() {
        PackMigrationComposer.Result result =
                PackMigrationComposer.compose("identity", PackMigrationChain.empty(), v("1.0.0"), v("1.0.0"));
        assertTrue(composedOf(result).renames().isEmpty());
    }

    @Test
    void singleHopRenameFieldComposesDirectly() {
        PackMigrationChain c = chain("""
                { "1.0.0 -> 2.0.0": [ { "op": "renameField", "concept": "User", "from": "name", "to": "displayName" } ] }
                """);
        PackMigrationComposer.ComposedRenames renames = composedOf(
                PackMigrationComposer.compose("identity", c, v("1.0.0"), v("2.0.0"))).renames();
        assertEquals(Map.of("displayName", "name"), renames.fieldRenamesByConcept().get("User"));
        assertTrue(renames.conceptRenames().isEmpty());
    }

    @Test
    void multiHopSkipComposesTheRoadmapScenario() {
        // The card's own headline scenario: 1.0 -> 3.0 skipping 2.0 entirely must still replay
        // 2.0's rename. 3.0 adds an unrelated field (must not appear in the composed renames).
        PackMigrationChain c = chain("""
                {
                  "1.0.0 -> 2.0.0": [ { "op": "renameField", "concept": "User", "from": "name", "to": "displayName" } ],
                  "2.0.0 -> 3.0.0": [ { "op": "addField", "concept": "User", "field": "notes" } ]
                }
                """);
        PackMigrationComposer.ComposedRenames renames = composedOf(
                PackMigrationComposer.compose("identity", c, v("1.0.0"), v("3.0.0"))).renames();
        assertEquals(Map.of("displayName", "name"), renames.fieldRenamesByConcept().get("User"));
    }

    @Test
    void doubleRenameOfTheSameFieldCollapsesToOldestToFinal() {
        PackMigrationChain c = chain("""
                {
                  "1.0.0 -> 2.0.0": [ { "op": "renameField", "concept": "User", "from": "name", "to": "displayName" } ],
                  "2.0.0 -> 3.0.0": [ { "op": "renameField", "concept": "User", "from": "displayName", "to": "fullName" } ]
                }
                """);
        PackMigrationComposer.ComposedRenames renames = composedOf(
                PackMigrationComposer.compose("identity", c, v("1.0.0"), v("3.0.0"))).renames();
        assertEquals(Map.of("fullName", "name"), renames.fieldRenamesByConcept().get("User"));
    }

    @Test
    void renameThenDropOfTheSameFieldWithinRangeDropsTheEntryEntirely() {
        PackMigrationChain c = chain("""
                {
                  "1.0.0 -> 2.0.0": [ { "op": "renameField", "concept": "User", "from": "name", "to": "displayName" } ],
                  "2.0.0 -> 3.0.0": [ { "op": "dropField", "concept": "User", "field": "displayName" } ]
                }
                """);
        PackMigrationComposer.ComposedRenames renames = composedOf(
                PackMigrationComposer.compose("identity", c, v("1.0.0"), v("3.0.0"))).renames();
        assertTrue(renames.isEmpty(), "a rename-then-drop within the same range must produce no marker: " + renames);
    }

    @Test
    void conceptRenameComposesAndCarriesFieldRenamesForwardToTheNewConceptKey() {
        PackMigrationChain c = chain("""
                {
                  "1.0.0 -> 2.0.0": [ { "op": "renameConcept", "from": "Client", "to": "Customer" } ],
                  "2.0.0 -> 3.0.0": [ { "op": "renameField", "concept": "Customer", "from": "name", "to": "displayName" } ]
                }
                """);
        PackMigrationComposer.ComposedRenames renames = composedOf(
                PackMigrationComposer.compose("identity", c, v("1.0.0"), v("3.0.0"))).renames();
        assertEquals(Map.of("Customer", "Client"), renames.conceptRenames());
        assertEquals(Map.of("displayName", "name"), renames.fieldRenamesByConcept().get("Customer"));
    }

    @Test
    void addFieldNeverAppearsInComposedRenames() {
        PackMigrationChain c = chain("""
                { "1.0.0 -> 2.0.0": [ { "op": "addField", "concept": "User", "field": "notes" } ] }
                """);
        PackMigrationComposer.ComposedRenames renames = composedOf(
                PackMigrationComposer.compose("identity", c, v("1.0.0"), v("2.0.0"))).renames();
        assertTrue(renames.isEmpty());
    }

    @Test
    void missingHopRefusesNamingTheGap() {
        PackMigrationChain c = chain("""
                { "1.0.0 -> 2.0.0": [] }
                """);
        PackMigrationComposer.Refused refused =
                refusedOf(PackMigrationComposer.compose("identity", c, v("1.0.0"), v("3.0.0")));
        assertTrue(refused.message().contains("no migration chain entry starts at version 2.0.0"), refused.message());
    }

    @Test
    void branchingChainRefuses() {
        PackMigrationChain c = chain("""
                {
                  "1.0.0 -> 1.4.0": [],
                  "1.0.0 -> 2.0.0": []
                }
                """);
        PackMigrationComposer.Refused refused =
                refusedOf(PackMigrationComposer.compose("identity", c, v("1.0.0"), v("2.0.0")));
        assertTrue(refused.message().contains("more than one migration chain hop"), refused.message());
    }

    @Test
    void overshootingHopRefuses() {
        PackMigrationChain c = chain("""
                { "1.0.0 -> 3.0.0": [] }
                """);
        PackMigrationComposer.Refused refused =
                refusedOf(PackMigrationComposer.compose("identity", c, v("1.0.0"), v("2.0.0")));
        assertTrue(refused.message().contains("overshoots"), refused.message());
    }

    @Test
    void emptyChainWithDifferentVersionsRefuses() {
        PackMigrationComposer.Refused refused =
                refusedOf(PackMigrationComposer.compose("identity", PackMigrationChain.empty(), v("1.0.0"), v("2.0.0")));
        assertTrue(refused.message().contains("no migration chain entry starts at version 1.0.0"), refused.message());
    }
}
