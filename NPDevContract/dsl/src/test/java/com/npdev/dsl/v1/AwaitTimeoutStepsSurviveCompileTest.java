package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flow that waits for an event can give up after a deadline and run its {@code onTimeout} steps instead (an
 * escalation, a reminder). The deadline and those steps must reach the compiled model unchanged, or a flow that was
 * meant to escalate would simply wait forever; nothing else in the unit tests names {@code onTimeout}.
 */
class AwaitTimeoutStepsSurviveCompileTest {

    @Test
    void timeoutAndOnTimeoutStepsReachTheCompiledAwaitStep(@TempDir Path tempDir) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "await.timeout.demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ],
                  "events": [
                    { "name": "OrderApproved", "payload": [{ "name": "orderId", "type": "uuid" }] },
                    { "name": "OrderEscalated", "payload": [{ "name": "orderId", "type": "uuid" }] }
                  ],
                  "flows": [
                    { "name": "EscalateOrder", "concept": "Order", "steps": [
                      { "name": "wait-for-approval", "type": "awaitEvent", "awaitEvent": "OrderApproved",
                        "match": { "correlation": true }, "awaitRef": "approval",
                        "timeout": 3600,
                        "onTimeout": [
                          { "name": "escalate", "type": "emitEvent", "event": "OrderEscalated", "from": "$input" }
                        ] },
                      { "name": "done", "type": "return", "value": "$input" }
                    ] }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "expected no validation errors, got: " + validation.getErrors());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledFlow flow = compiled.findFlow("EscalateOrder").orElseThrow();
        CompiledFlowStep wait = flow.getSteps().stream()
                .filter(step -> step.getName().equals("wait-for-approval"))
                .findFirst()
                .orElseThrow();

        assertEquals(3600L, wait.getTimeoutSeconds(), "the deadline of the wait");
        assertEquals(1, wait.getOnTimeoutSteps().size(), "the steps that run when the deadline passes");
        assertEquals("escalate", wait.getOnTimeoutSteps().get(0).getName());
    }
}
