package com.npdev.adapters.flowcompiled;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.inproc.InProcCapabilityInvoker;
import com.npdev.kernel.inproc.InProcEventBus;
import com.npdev.kernel.inproc.SimpleInvariantEngine;
import com.npdev.kernel.mvp.ExecutionTrace;
import com.npdev.kernel.mvp.ExecutionTraceCanonicalJson;
import com.npdev.kernel.mvp.ExecutionTraceStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledModelKernelRunnerDeterminismTest {

    @Test
    void runningTwiceWithSameInputProducesIdenticalCanonicalTrace() throws Exception {
        CompiledModel compiledModel = compileModel("""
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
                      ],
                      "invariants":[
                        {"name":"EmailRequired","expr":"email != null"}
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
                  "events":[
                    {"name":"UserCreated","payload":[{"name":"id","type":"string"}]},
                    {"name":"UserAudited","payload":[{"name":"id","type":"string"}]}
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
                          "out":"$saved"
                        },
                        {"name":"emit-created","type":"emitEvent","event":"UserCreated","payload":"$saved"},
                        {"name":"emit-audited","type":"emitEvent","event":"UserAudited","payload":"$saved"},
                        {"name":"return-saved","type":"return","value":"$saved"}
                      ]
                    }
                  ]
                }
                """);

        InProcCapabilityInvoker capabilityInvoker = new InProcCapabilityInvoker()
                .register("persistence", "inmemory", "save", (input, context, state) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) input;
                    return Map.of("id", "user-1", "email", payload.get("email"));
                });

        CompiledModelKernelRunner runner = new CompiledModelKernelRunner(
                new InProcEventBus(),
                new SimpleInvariantEngine(),
                capabilityInvoker
        );
        ExecutionContext context = ExecutionContext.of("tenant-a", "actor-a").withTag("correlationId", "corr-fixed");

        ExecutionTrace first = runner.run(compiledModel, "CreateUser", Map.of("email", "ana@example.com"), context);
        ExecutionTrace second = runner.run(compiledModel, "CreateUser", Map.of("email", "ana@example.com"), context);

        assertEquals(ExecutionTraceStatus.SUCCESS, first.status());
        assertEquals(2, first.emittedEvents().size());
        assertEquals("UserCreated", first.emittedEvents().get(0).eventName());
        assertEquals("UserAudited", first.emittedEvents().get(1).eventName());
        assertEquals(
                ExecutionTraceCanonicalJson.toCanonicalJson(first),
                ExecutionTraceCanonicalJson.toCanonicalJson(second)
        );
    }

    @Test
    void missingCapabilityBindingReturnsTypedFailure() throws Exception {
        CompiledModel compiledModel = compileModel("""
                {
                  "namespace":"demo",
                  "dslVersion":"1.0.0",
                  "version":"v1",
                  "concepts":[
                    {
                      "name":"User",
                      "fields":[
                        {"name":"id","type":"uuid","id":true}
                      ]
                    }
                  ],
                  "capabilities":[
                    {"name":"persistence","type":"PersistenceCapability","operations":["save"]}
                  ],
                  "flows":[
                    {
                      "name":"CreateUser",
                      "concept":"User",
                      "steps":[
                        {"name":"save","type":"capabilityCall","cap":"persistence","op":"save","args":["$input"],"out":"$saved"}
                      ]
                    }
                  ]
                }
                """);

        CompiledModelKernelRunner runner = new CompiledModelKernelRunner(
                new InProcEventBus(),
                new SimpleInvariantEngine(),
                new InProcCapabilityInvoker()
        );
        ExecutionTrace trace = runner.run(compiledModel, "CreateUser", Map.of("id", "x"), ExecutionContext.anonymous());

        assertEquals(ExecutionTraceStatus.FAILURE, trace.status());
        assertTrue(trace.failure() != null && "CAPABILITY_BINDING_MISSING".equals(trace.failure().code()));
    }

    @Test
    void invariantFailureReturnsTypedFailureAtStep() throws Exception {
        CompiledModel compiledModel = compileModel("""
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
                      ],
                      "invariants":[
                        {"name":"EmailRequired","expr":"email != null"}
                      ]
                    }
                  ],
                  "flows":[
                    {
                      "name":"ValidateUser",
                      "concept":"User",
                      "steps":[
                        {"name":"validate","type":"invariant","checkpoint":"pre","invariants":["EmailRequired"]},
                        {"name":"return-input","type":"return","value":"$input"}
                      ]
                    }
                  ]
                }
                """);

        CompiledModelKernelRunner runner = new CompiledModelKernelRunner(
                new InProcEventBus(),
                new SimpleInvariantEngine(),
                new InProcCapabilityInvoker()
        );

        ExecutionTrace trace = runner.run(
                compiledModel,
                "ValidateUser",
                Map.of(
                        "__failInvariantRef", "EmailRequired",
                        "__invariantFailureMessage", "Email is required"
                ),
                ExecutionContext.anonymous()
        );

        assertEquals(ExecutionTraceStatus.FAILURE, trace.status());
        assertTrue(trace.failure() != null && "INVARIANT_FAILED".equals(trace.failure().code()));
        assertEquals("step-1-validate", trace.failure().stepId());
    }

    private static CompiledModel compileModel(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-compiled-model-kernel-runner-", ".json");
        Files.writeString(modelPath, json, StandardCharsets.UTF_8);
        ModelAst modelAst = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(modelAst);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Model validation failed: " + String.join(" | ", errors));
        }
        return new ModelCompiler().compile(modelAst);
    }
}
