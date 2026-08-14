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

    /**
     * R10 (plugin:java-controller): a mounted @RestController is never invoked by a flow/procedure
     * capabilityCall step -- HTTP clients hit it directly -- so nothing in this graph's normal
     * flow/procedure walk would ever discover a plugin:java-controller binding. Confirms the
     * synthesized fallback: the capability is declared and bound but referenced by ZERO
     * flows/procedures, and still yields exactly one requirement with a fixed "mount" operation
     * sentinel (never a real flow/procedure operation name, so it can never collide with one).
     */
    @Test
    void synthesizesOneRequirementForAnUncalledJavaControllerBinding() throws Exception {
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
                  "customCapabilities": [
                    {
                      "name": "adminTools",
                      "type": "AdminToolsController"
                    }
                  ],
                  "bindings": [
                    { "capability": "adminTools", "adapter": "plugin:java-controller" }
                  ]
                }
                """);

        CompiledPluginRequirementGraph graph = new CompiledPluginRequirementGraphBuilder().build(ast);

        assertEquals(1, graph.getRequirements().size());
        CompiledPluginRequirement requirement = graph.getRequirements().get(0);
        assertEquals("adminTools", requirement.capabilityName());
        assertEquals("AdminToolsController", requirement.capabilityType());
        assertEquals("mount", requirement.operationName());
        assertEquals("", requirement.flowName());
        assertEquals("", requirement.stepName());
        assertEquals("plugin:java-controller", requirement.boundAdapter());
        assertTrue(requirement.externalCandidate());
    }

    /**
     * A capability call that DOES reference the same capability (e.g. a legacy flow that also
     * invokes it) must not produce a second, duplicate requirement -- the synthesis only fills the
     * gap for capabilities no real call site ever discovered.
     */
    @Test
    void doesNotDuplicateAJavaControllerBindingAlreadyCalledByAFlow() throws Exception {
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
                  "customCapabilities": [
                    {
                      "name": "adminTools",
                      "type": "AdminToolsController",
                      "operations": ["ping"]
                    }
                  ],
                  "bindings": [
                    { "capability": "adminTools", "adapter": "plugin:java-controller" }
                  ],
                  "flows": [
                    {
                      "name": "PingAdminTools",
                      "concept": "TriggerEntity",
                      "steps": [
                        {
                          "name": "ping",
                          "type": "capabilityCall",
                          "capability": "adminTools",
                          "operation": "ping",
                          "input": "payload",
                          "output": "result"
                        }
                      ]
                    }
                  ]
                }
                """);

        CompiledPluginRequirementGraph graph = new CompiledPluginRequirementGraphBuilder().build(ast);

        assertEquals(1, graph.getRequirements().size());
        assertEquals("ping", graph.getRequirements().get(0).operationName());
        assertEquals("PingAdminTools", graph.getRequirements().get(0).flowName());
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-plugin-graph-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}
