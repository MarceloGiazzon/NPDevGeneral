package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledCapabilityCall;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DslCapabilityPolicyTest {

    @Test
    void compilesCapabilityPolicyFromFlowStep() throws Exception {
        ModelAst ast = parse("""
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
                    {
                      "name":"persistence",
                      "type":"PersistenceCapability",
                      "operations":["save"]
                    }
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
                            "retryDelayMs":500,
                            "timeoutMs":3000,
                            "idempotencyKeyField":"$input.requestId",
                            "failureClassification":"TRANSIENT"
                          }
                        },
                        {"type":"return","value":"$saved"}
                      ]
                    }
                  ]
                }
                """);

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors but got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledFlow flow = compiled.findFlow("CreateUser").orElseThrow();
        CompiledCapabilityCall call = flow.getSteps().get(0).getCapabilityCall();

        assertEquals(3, call.getExecutionPolicy().getRetryCount());
        assertEquals(500L, call.getExecutionPolicy().getRetryDelayMs());
        assertEquals(3000L, call.getExecutionPolicy().getTimeoutMs());
        assertEquals("$input.requestId", call.getExecutionPolicy().getIdempotencyKeyField());
        assertEquals("TRANSIENT", call.getExecutionPolicy().getFailureClassification());
    }

    @Test
    void rejectsInvalidCapabilityPolicyValuesAtSchemaLayer() throws Exception {
        IOException error = assertThrows(IOException.class, () -> parse("""
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
                    {
                      "name":"persistence",
                      "type":"PersistenceCapability",
                      "operations":["save"]
                    }
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
                          "policy":{"retryCount":0}
                        }
                      ]
                    }
                  ]
                }
                """));
        assertTrue(error.getMessage().contains("Model schema validation failed"));
        assertTrue(error.getMessage().contains("retryCount"));
    }

    private static ModelAst parse(String json) throws Exception {
        Path model = Files.createTempFile("npdev-cap-policy-", ".json");
        Files.writeString(model, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(model);
    }
}
