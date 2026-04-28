package com.npdev.dsl.v1;

import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DeprecatedSchemaVersionRejectionTest {

    @Test
    void rejectsDeprecatedSchemaTarget() throws Exception {
        Path modelPath = Files.createTempFile("npdev-deprecated-schema-", ".json");
        Files.writeString(modelPath, """
                {
                  "$schema": "../../NPDevContract/schemas/model-1.0.0.schema.json",
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
                """);

        try {
            new JsonModelParser().parse(modelPath);
            fail("Expected deprecated schema target to be rejected.");
        } catch (Exception exception) {
            String message = exception.getMessage();
            assertTrue(message.contains("deprecated schema"), "Expected deprecated schema rejection message.");
            assertTrue(message.contains("model.schema.json"), "Expected migration hint toward canonical schema.");
        }
    }
}
