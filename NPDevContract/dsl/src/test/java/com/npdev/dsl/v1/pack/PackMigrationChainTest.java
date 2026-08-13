package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-4 Stage C: parsing a pack's raw {@code migrations} object into {@link PackMigrationChain}'s
 * hop list -- pure, no filesystem, matching {@code PackDiffEngineTest}'s own JSON-fixture style.
 */
class PackMigrationChainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void missingMigrationsNodeParsesAsEmpty() {
        assertTrue(PackMigrationChain.parse(null).hops().isEmpty());
    }

    @Test
    void nullMigrationsNodeParsesAsEmpty() {
        assertTrue(PackMigrationChain.parse(json("null")).hops().isEmpty());
    }

    @Test
    void emptyMigrationsObjectParsesAsEmpty() {
        assertTrue(PackMigrationChain.parse(json("{}")).hops().isEmpty());
    }

    @Test
    void singleHopWithEveryOpKindParses() {
        JsonNode node = json("""
                {
                  "1.0.0 -> 2.0.0": [
                    { "op": "renameField", "concept": "User", "from": "name", "to": "displayName" },
                    { "op": "addField", "concept": "User", "field": "notes" },
                    { "op": "dropField", "concept": "User", "field": "legacyFlag" },
                    { "op": "renameConcept", "from": "Client", "to": "Customer" }
                  ]
                }
                """);
        PackMigrationChain chain = PackMigrationChain.parse(node);
        assertEquals(1, chain.hops().size());
        PackMigrationChain.HopEntry hop = chain.hops().get(0);
        assertEquals(new PackVersion(1, 0, 0), hop.from());
        assertEquals(new PackVersion(2, 0, 0), hop.to());
        assertEquals(List.of(
                new PackMigrationOp.RenameField("User", "name", "displayName"),
                new PackMigrationOp.AddField("User", "notes"),
                new PackMigrationOp.DropField("User", "legacyFlag"),
                new PackMigrationOp.RenameConcept("Client", "Customer")
        ), hop.ops());
    }

    @Test
    void emptyOpsArrayParsesAsAZeroOpHop() {
        PackMigrationChain chain = PackMigrationChain.parse(json("""
                { "2.0.0 -> 3.0.0": [] }
                """));
        assertEquals(1, chain.hops().size());
        assertTrue(chain.hops().get(0).ops().isEmpty());
    }

    @Test
    void hopsStartingAtFindsAllMatchesEvenWhenBranching() {
        PackMigrationChain chain = PackMigrationChain.parse(json("""
                {
                  "1.0.0 -> 1.4.0": [],
                  "1.0.0 -> 2.0.0": []
                }
                """));
        assertEquals(2, chain.hopsStartingAt(new PackVersion(1, 0, 0)).size());
        assertTrue(chain.hopsStartingAt(new PackVersion(9, 0, 0)).isEmpty());
    }

    @Test
    void malformedKeyWithNoArrowRefuses() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PackMigrationChain.parse(json("""
                        { "1.0.0": [] }
                        """)));
        assertTrue(e.getMessage().contains("1.0.0"), e.getMessage());
    }

    @Test
    void keyWithNonMonotonicTargetRefuses() {
        assertThrows(IllegalArgumentException.class, () -> PackMigrationChain.parse(json("""
                { "2.0.0 -> 1.0.0": [] }
                """)));
    }

    @Test
    void keyWithEqualVersionsRefuses() {
        assertThrows(IllegalArgumentException.class, () -> PackMigrationChain.parse(json("""
                { "1.0.0 -> 1.0.0": [] }
                """)));
    }

    @Test
    void nonArrayHopValueRefuses() {
        assertThrows(IllegalArgumentException.class, () -> PackMigrationChain.parse(json("""
                { "1.0.0 -> 2.0.0": {} }
                """)));
    }

    @Test
    void unknownOpRefuses() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PackMigrationChain.parse(json("""
                        { "1.0.0 -> 2.0.0": [ { "op": "renameTable", "concept": "User" } ] }
                        """)));
        assertTrue(e.getMessage().contains("renameTable"), e.getMessage());
    }

    @Test
    void opMissingRequiredFieldRefuses() {
        assertThrows(IllegalArgumentException.class, () -> PackMigrationChain.parse(json("""
                { "1.0.0 -> 2.0.0": [ { "op": "renameField", "concept": "User", "from": "name" } ] }
                """)));
    }

    @Test
    void nonObjectOpRefuses() {
        assertThrows(IllegalArgumentException.class, () -> PackMigrationChain.parse(json("""
                { "1.0.0 -> 2.0.0": [ "not an object" ] }
                """)));
    }
}
