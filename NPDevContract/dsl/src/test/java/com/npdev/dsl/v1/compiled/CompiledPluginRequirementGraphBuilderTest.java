package com.npdev.dsl.v1.compiled;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledPluginRequirementGraphBuilderTest {

    @Test
    void collectsDeterministicCapabilityRequirementsFromFlows() throws Exception {
        ModelAst ast = parse("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "TriggerEntity",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "persistence",
                      "type": "PersistenceCapability",
                      "operations": ["save"]
                    },
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
                  ],
                  "bindings": [
                    { "capability": "customExtension", "adapter": "plugin:custom-procedure" }
                  ],
                  "flows": [
                    {
                      "name": "SubmitTrigger",
                      "concept": "TriggerEntity",
                      "steps": [
                        {
                          "name": "persist",
                          "type": "capabilityCall",
                          "capability": "persistence",
                          "operation": "save",
                          "input": "payload",
                          "output": "saved"
                        },
                        {
                          "name": "invoke-custom",
                          "type": "capabilityCall",
                          "capability": "customExtension",
                          "operation": "run",
                          "input": "saved",
                          "output": "result"
                        }
                      ]
                    }
                  ]
                }
                """);

        CompiledPluginRequirementGraph graph = new CompiledPluginRequirementGraphBuilder().build(ast);

        assertEquals(2, graph.getRequirements().size());

        CompiledPluginRequirement custom = graph.getRequirements().stream()
                .filter(candidate -> "customExtension".equals(candidate.capabilityName()))
                .findFirst()
                .orElseThrow();

        assertEquals("CustomProcedureCapability", custom.capabilityType());
        assertEquals("run", custom.operationName());
        assertEquals("SubmitTrigger", custom.flowName());
        assertEquals("invoke-custom", custom.stepName());
        assertEquals("plugin:custom-procedure", custom.boundAdapter());
        assertTrue(custom.externalCandidate());

        List<String> orderedCapabilityNames = graph.getRequirements().stream()
                .map(CompiledPluginRequirement::capabilityName)
                .toList();
        assertEquals(List.of("customExtension", "persistence"), orderedCapabilityNames);
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-plugin-graph-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}
