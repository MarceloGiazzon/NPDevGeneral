package com.npdev.dsl.v1;

import com.npdev.dsl.v1.parser.DeprecationException;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacySchemaRejectionTest {

    @Test
    void rejectsRootEntitiesWithDiagnosticPathAndSuggestedFix() throws Exception {
        Path modelPath = Files.createTempFile("npdev-legacy-entities-", ".json");
        Files.writeString(modelPath, """
                {
                  "$schema": "NPDevContract/schemas/model.schema.json",
                  "schemaVersion": "1.0.0",
                  "namespace": "demo.legacy",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "entities": [
                    {
                      "name": "Customer",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);

        DeprecationException exception = assertThrows(DeprecationException.class, () -> new JsonModelParser().parse(modelPath));
        assertNotNull(exception.getDiagnostic());
        assertEquals("LEGACY_ENTITIES_ROOT", exception.getDiagnostic().getCode());
        assertEquals("$.entities", exception.getDiagnostic().getPath());
        assertTrue(exception.getDiagnostic().getSuggestedFix().contains("migrate legacy-model"));
    }

    /** FORM P3-T11: every root key removed from the DSL is refused with a code, a path and a migration message. */
    @Test
    void rejectsEachRemovedRootKeyWithItsOwnCodeAndPath() throws Exception {
        assertRemovedRootKey("\"model\": \"demo\"", "LEGACY_MODEL_ROOT_KEY", "$.model", "namespace", false);
        assertRemovedRootKey("\"contexts\": []", "LEGACY_CONTEXTS_ROOT_KEY", "$.contexts", "modules", true);
        assertRemovedRootKey("\"fragments\": []", "LEGACY_FRAGMENTS_ROOT_KEY", "$.fragments", "$ref", true);
        assertRemovedRootKey("\"ruleProfiles\": []", "LEGACY_RULE_PROFILES_ROOT_KEY", "$.ruleProfiles", "Delete", true);
        assertRemovedRootKey("\"webhooks\": []", "LEGACY_WEBHOOKS_ROOT_KEY", "$.webhooks", "capabilities[].inbound", true);
    }

    private static void assertRemovedRootKey(String member, String code, String path, String fixMentions, boolean withNamespace)
            throws Exception {
        Path modelPath = Files.createTempFile("npdev-removed-key-", ".json");
        Files.writeString(modelPath, "{ " + (withNamespace ? "\"namespace\": \"demo\", " : "")
                + "\"dslVersion\": \"3.0.0\", \"version\": \"1.0\", " + member
                + ", \"concepts\": [ { \"name\": \"Customer\", \"fields\": [ { \"name\": \"id\", \"type\": \"uuid\", \"id\": true, \"required\": true } ] } ] }");

        DeprecationException exception = assertThrows(DeprecationException.class, () -> new JsonModelParser().parse(modelPath));
        assertEquals(code, exception.getDiagnostic().getCode());
        assertEquals(path, exception.getDiagnostic().getPath());
        assertTrue(exception.getDiagnostic().getSuggestedFix().contains(fixMentions), exception.getDiagnostic().getSuggestedFix());
    }
}
