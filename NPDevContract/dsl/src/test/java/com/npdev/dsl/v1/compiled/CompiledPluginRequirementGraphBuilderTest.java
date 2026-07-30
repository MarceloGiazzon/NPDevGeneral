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

    /**
     * REG (Move 3 G4, docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md, C10 investigation): a capability
     * referenced ONLY by a procedure's capabilityCall step (never by any flow) was never mounted --
     * no Java source compiled, no plugin-manifest entry -- so ProcedureRunner's own dispatch (REG-73)
     * correctly resolved an adapter id the runtime had never registered, failing at boot with
     * "Adapter ... is not declared in active plugin manifest". Every custom capability tried so far
     * had ALSO been called from a pre-existing flow, which masked this until one wasn't.
     */
    @Test
    void collectsCapabilityRequirementsFromProcedureStepsToo() throws Exception {
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
                      "name": "procedureOnlyExtension",
                      "type": "ProcedureOnlyCapability",
                      "operations": ["run"]
                    }
                  ],
                  "bindings": [
                    { "capability": "procedureOnlyExtension", "adapter": "plugin:java-source" }
                  ],
                  "procedures": [
                    {
                      "name": "InvokeProcedureOnlyCapability",
                      "steps": [
                        {
                          "name": "invoke-custom",
                          "type": "capabilityCall",
                          "capability": "procedureOnlyExtension",
                          "operation": "run",
                          "args": { "input": "$input" },
                          "target": "result"
                        },
                        { "name": "return-result", "type": "return", "value": "$result" }
                      ]
                    }
                  ]
                }
                """);

        CompiledPluginRequirementGraph graph = new CompiledPluginRequirementGraphBuilder().build(ast);

        assertEquals(1, graph.getRequirements().size());
        CompiledPluginRequirement requirement = graph.getRequirements().get(0);
        assertEquals("procedureOnlyExtension", requirement.capabilityName());
        assertEquals("ProcedureOnlyCapability", requirement.capabilityType());
        assertEquals("run", requirement.operationName());
        assertEquals("InvokeProcedureOnlyCapability", requirement.flowName());
        assertEquals("invoke-custom", requirement.stepName());
        assertEquals("plugin:java-source", requirement.boundAdapter());
        assertTrue(requirement.externalCandidate());
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-plugin-graph-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}
