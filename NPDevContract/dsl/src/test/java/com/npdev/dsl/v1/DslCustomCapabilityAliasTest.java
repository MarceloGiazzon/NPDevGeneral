package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DslCustomCapabilityAliasTest {

    @Test
    void parserMergesCustomCapabilitiesIntoCanonicalCapabilitiesList() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "TriggerEntity",
                      "fields": [
                        {"name":"id","type":"uuid","id":true}
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "persistence",
                      "type": "PersistenceCapability",
                      "operations": ["save"]
                    }
                  ],
                  "customCapabilities": [
                    {
                      "name": "customExtension",
                      "type": "CustomProcedureCapability",
                      "operations": [
                        {
                          "name": "run",
                          "input": { "conceptRef": "TriggerEntity" }
                        }
                      ]
                    }
                  ]
                }
                """;

        ModelAst ast = parse(json);

        assertEquals(2, ast.getCapabilities().size());

        CapabilityAst external = ast.getCapabilities().stream()
                .filter(cap -> "customExtension".equals(cap.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("CustomProcedureCapability", external.getType());
        assertEquals(1, external.getOperations().size());
        assertEquals("run", external.getOperations().get(0).getName());
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-custom-capability-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}