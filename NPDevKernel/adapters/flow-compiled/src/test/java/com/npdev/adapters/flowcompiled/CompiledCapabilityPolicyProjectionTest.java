package com.npdev.adapters.flowcompiled;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.kernel.FlowDefinition;
import com.npdev.kernel.FlowStepDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledCapabilityPolicyProjectionTest {

    @Test
    void mapsCompiledCapabilityPolicyIntoKernelStepPolicy() throws Exception {
        CompiledModel compiled = compile("""
                {
                  "namespace":"demo",
                  "dslVersion":"1.0.0",
                  "version":"v1",
                  "concepts":[
                    {
                      "name":"User",
                      "fields":[
                        {"name":"id","type":"uuid","id":true},
                        {"name":"email","type":"string","required":true}
                      ]
                    }
                  ],
                  "capabilities":[
                    {"name":"persistence","type":"PersistenceCapability","operations":["save"]}
                  ],
                  "bindings":[
                    {"capability":"persistence","adapter":"inmemory"}
                  ],
                  "flows":[
                    {
                      "name":"CreateUser",
                      "concept":"User",
                      "steps":[
                        {
                          "name":"save",
                          "type":"capabilityCall",
                          "cap":"persistence",
                          "op":"save",
                          "args":["$input"],
                          "out":"$saved",
                          "policy":{
                            "retryCount":3,
                            "retryDelayMs":250,
                            "timeoutMs":1000,
                            "idempotencyKeyField":"$input.requestId",
                            "failureClassification":"contract"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        CompiledModelFlowDefinitionProvider provider = new CompiledModelFlowDefinitionProvider(compiled);
        FlowDefinition flow = provider.getFlow("CreateUser");
        FlowStepDefinition step = flow.getSteps().get(0);

        assertEquals(3, step.getCapabilityExecutionPolicy().retryCount());
        assertEquals(250L, step.getCapabilityExecutionPolicy().retryDelayMs());
        assertEquals(1000L, step.getCapabilityExecutionPolicy().timeoutMs());
        assertEquals("$input.requestId", step.getCapabilityExecutionPolicy().idempotencyKeyField());
        assertEquals("CONTRACT", step.getCapabilityExecutionPolicy().failureClassification().name());
    }

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-flow-policy-", ".json");
        Files.writeString(modelPath, json, StandardCharsets.UTF_8);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected valid model but got: " + errors);
        return new ModelCompiler().compile(ast);
    }
}
