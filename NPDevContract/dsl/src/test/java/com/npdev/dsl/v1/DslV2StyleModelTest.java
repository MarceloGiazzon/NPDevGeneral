package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslV2StyleModelTest {

    @Test
    void parsesValidatesAndCompilesV2StyleModel() throws Exception {
        String json = """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        {"name":"id","type":"uuid","id":true},
                        {"name":"email","type":"string","required":true},
                        {"name":"name","type":"string","required":true}
                      ],
                      "invariants": [
                        {"name":"EmailRequired","expr":"email != null && email != ''"},
                        {"name":"EmailUnique","expr":"cap.persistence.unique('User','email', email)"}
                      ],
                      "events": [
                        {
                          "name":"UserCreated",
                          "payload": [{"name":"id","type":"uuid"},{"name":"email","type":"string"}]
                        }
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "persistence",
                      "type": "PersistenceCapability",
                      "operations": ["save", "unique", "findById"]
                    }
                  ],
                  "flows": [
                    {
                      "name": "CreateUser",
                      "input": {"concept":"User","mode":"create"},
                      "steps": [
                        {"type":"enforceInvariants","scope":"User"},
                        {"type":"capabilityCall","cap":"persistence","op":"save","args":["User"]},
                        {"type":"emitEvent","event":"UserCreated","from":"User"},
                        {"type":"return","value":"User"}
                      ]
                    }
                  ]
                }
                """;

        ModelAst ast = parse(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getConcepts().size());
        assertEquals(1, compiled.getCapabilities().size());
        assertEquals("PersistenceCapability", compiled.getCapabilities().get(0).getType());
        assertEquals(3, compiled.getCapabilities().get(0).getOperations().size());
        assertEquals(1, compiled.getEvents().size());
        assertEquals(1, compiled.getFlows().size());

        CompiledFlow flow = compiled.findFlow("CreateUser").orElseThrow();
        assertEquals("User", flow.getConcept());
        assertEquals("create", flow.getMode());
        assertEquals(4, flow.getSteps().size());

        CompiledFlowStep invariantStep = flow.getSteps().get(0);
        assertEquals("invariant", invariantStep.getType());
        assertEquals("User", invariantStep.getScope());
        assertEquals(4, invariantStep.getInvariants().size());
        assertEquals("EmailRequired", invariantStep.getInvariants().get(0));
        assertEquals("EmailUnique", invariantStep.getInvariants().get(1));

        CompiledFlowStep capabilityStep = flow.getSteps().get(1);
        assertEquals("capability", capabilityStep.getType());
        assertEquals("persistence", capabilityStep.getCapabilityCall().getCapabilityName());
        assertEquals("PersistenceCapability", capabilityStep.getCapabilityCall().getCapabilityType());
        assertEquals("save", capabilityStep.getCapabilityCall().getOperation());
        assertEquals(1, capabilityStep.getCapabilityCall().getArgsRefs().size());
        assertEquals("User", capabilityStep.getCapabilityCall().getArgsRefs().get(0));
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-v2-style-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}
