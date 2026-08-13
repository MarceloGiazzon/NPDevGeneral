package com.npdev.dsl.v1.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PK-4 Stage D: {@link PackMigrationChainSynthesizer} injects a composed rename onto raw pack JSON
 * in exactly the shape a hand-authored {@code renamedFrom} already uses.
 */
class PackMigrationChainSynthesizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode pack(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void emptyComposedRenamesReturnsAnUnmodifiedCopy() {
        ObjectNode original = pack("""
                { "pack": "identity", "version": "3.0.0", "concepts": [ { "name": "User", "fields": [] } ] }
                """);
        ObjectNode result = PackMigrationChainSynthesizer.applyComposedRenames(
                original, PackMigrationComposer.ComposedRenames.empty());
        assertEquals(original, result);
        assertNotSame(original, result, "must return a copy, not the same instance");
    }

    @Test
    void injectsFieldLevelRenamedFrom() {
        ObjectNode original = pack("""
                {
                  "pack": "identity", "version": "3.0.0",
                  "concepts": [ { "name": "User", "fields": [
                    { "name": "displayName", "type": "string" },
                    { "name": "email", "type": "string" }
                  ] } ]
                }
                """);
        PackMigrationComposer.ComposedRenames composed = new PackMigrationComposer.ComposedRenames(
                Map.of(), Map.of("User", Map.of("displayName", "name")));

        ObjectNode result = PackMigrationChainSynthesizer.applyComposedRenames(original, composed);

        JsonNode displayNameField = result.get("concepts").get(0).get("fields").get(0);
        assertEquals("name", displayNameField.get("renamedFrom").asText());
        JsonNode emailField = result.get("concepts").get(0).get("fields").get(1);
        assertNull(emailField.get("renamedFrom"), "an unrelated field must not gain a renamedFrom");
    }

    @Test
    void injectsConceptLevelRenamedFrom() {
        ObjectNode original = pack("""
                { "pack": "identity", "version": "2.0.0", "concepts": [ { "name": "Customer", "fields": [] } ] }
                """);
        PackMigrationComposer.ComposedRenames composed =
                new PackMigrationComposer.ComposedRenames(Map.of("Customer", "Client"), Map.of());

        ObjectNode result = PackMigrationChainSynthesizer.applyComposedRenames(original, composed);

        assertEquals("Client", result.get("concepts").get(0).get("renamedFrom").asText());
    }

    @Test
    void matchingHandAuthoredRenamedFromIsAHarmlessNoOp() {
        ObjectNode original = pack("""
                {
                  "pack": "identity", "version": "3.0.0",
                  "concepts": [ { "name": "User", "fields": [
                    { "name": "displayName", "type": "string", "renamedFrom": "name" }
                  ] } ]
                }
                """);
        PackMigrationComposer.ComposedRenames composed = new PackMigrationComposer.ComposedRenames(
                Map.of(), Map.of("User", Map.of("displayName", "name")));

        ObjectNode result = PackMigrationChainSynthesizer.applyComposedRenames(original, composed);

        assertEquals("name", result.get("concepts").get(0).get("fields").get(0).get("renamedFrom").asText());
    }

    @Test
    void conflictingHandAuthoredRenamedFromRefusesLoudly() {
        ObjectNode original = pack("""
                {
                  "pack": "identity", "version": "3.0.0",
                  "concepts": [ { "name": "User", "fields": [
                    { "name": "displayName", "type": "string", "renamedFrom": "somethingElse" }
                  ] } ]
                }
                """);
        PackMigrationComposer.ComposedRenames composed = new PackMigrationComposer.ComposedRenames(
                Map.of(), Map.of("User", Map.of("displayName", "name")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PackMigrationChainSynthesizer.applyComposedRenames(original, composed));
        assertTrue(e.getMessage().contains("somethingElse"), e.getMessage());
        assertTrue(e.getMessage().contains("name"), e.getMessage());
    }

    @Test
    void originalNodeIsNeverMutated() {
        ObjectNode original = pack("""
                {
                  "pack": "identity", "version": "3.0.0",
                  "concepts": [ { "name": "User", "fields": [ { "name": "displayName", "type": "string" } ] } ]
                }
                """);
        PackMigrationComposer.ComposedRenames composed = new PackMigrationComposer.ComposedRenames(
                Map.of(), Map.of("User", Map.of("displayName", "name")));

        PackMigrationChainSynthesizer.applyComposedRenames(original, composed);

        assertNull(original.get("concepts").get(0).get("fields").get(0).get("renamedFrom"),
                "the original node passed in must not be mutated");
    }
}
