package com.npdev.dsl.v1;

import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.DeprecationException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeprecatedSchemaVersionRejectionTest {

    @Test
    void rejectsDeprecatedSchemaTarget() throws Exception {
        Path modelPath = Files.createTempFile("npdev-deprecated-schema-", ".json");
        String legacySchema = "model-" + "1.0.0" + ".schema.json";
        Files.writeString(modelPath, """
                {
                  "$schema": "../../NPDevContract/schemas/archive/%s",
                  "schemaVersion": "1.0.0",
                  "namespace": "demo.deprecated",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Customer",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """.formatted(legacySchema));

        DeprecationException exception = assertThrows(DeprecationException.class, () -> new JsonModelParser().parse(modelPath));
        assertNotNull(exception.getDiagnostic(), "Expected structured diagnostic.");
        assertEquals("LEGACY_SCHEMA_TARGET", exception.getDiagnostic().getCode());
        assertEquals("$schema", exception.getDiagnostic().getPath());
        assertTrue(exception.getDiagnostic().getSuggestedFix().contains("model.schema.json"), "Expected migration hint toward canonical schema.");
    }
}
