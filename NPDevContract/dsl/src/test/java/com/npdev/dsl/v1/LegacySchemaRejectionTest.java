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
                  "dslVersion": "1.0.0",
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
}
