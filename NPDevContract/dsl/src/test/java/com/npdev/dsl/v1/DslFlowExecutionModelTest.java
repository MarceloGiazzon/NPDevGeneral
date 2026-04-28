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

class DslFlowExecutionModelTest {

    @Test
    void parsesValidatesAndCompilesBranchAwaitAndDataMapping() throws Exception {
        String json = """
                {
                  "namespace": "billing",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "needsApproval", "type": "boolean", "required": true }
                      ]
                    }
                  ],
                  "events": [
                    { "name": "GovernmentApproved", "payload": [{ "name": "receipt", "type": "string" }] },
                    { "name": "InvoiceSubmitted", "payload": [{ "name": "receipt", "type": "string" }] }
                  ],
                  "flows": [
                    {
                      "name": "FinalizeInvoice",
                      "input": { "concept": "Invoice", "mode": "update" },
                      "steps": [
                        {
                          "type": "if",
                          "condition": "$input.needsApproval == true",
                          "then": [
                            {
                              "type": "awaitEvent",
                              "awaitEvent": "GovernmentApproved",
                              "as": "approval",
                              "match": {
                                "correlation": true,
                                "payload": { "receipt": "$input.id" }
                              }
                            },
                            { "type": "emitEvent", "event": "InvoiceSubmitted", "data": { "receipt": "$approval.receipt" } }
                          ],
                          "else": [
                            { "type": "emitEvent", "event": "InvoiceSubmitted", "data": { "receipt": "$input.id" } }
                          ]
                        },
                        { "type": "return", "value": "$input.id" }
                      ]
                    }
                  ]
                }
                """;

        ModelAst ast = parse(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledFlow flow = compiled.findFlow("FinalizeInvoice").orElseThrow();
        assertEquals(2, flow.getSteps().size());

        CompiledFlowStep branchStep = flow.getSteps().get(0);
        assertEquals("branch", branchStep.getType());
        assertEquals("$input.needsApproval == true", branchStep.getCondition());
        assertEquals(2, branchStep.getThenSteps().size());
        assertEquals("await", branchStep.getThenSteps().get(0).getType());
        assertEquals("GovernmentApproved", branchStep.getThenSteps().get(0).getAwaitEventName());
        assertEquals(Boolean.TRUE, branchStep.getThenSteps().get(0).getAwaitMatchCorrelation());
        assertEquals("$input.id", branchStep.getThenSteps().get(0).getAwaitPayloadMatch().get("receipt"));
        assertEquals("InvoiceSubmitted", branchStep.getThenSteps().get(1).getEventName());
        assertEquals("$approval.receipt", branchStep.getThenSteps().get(1).getEventDataRefs().get("receipt"));

        CompiledFlowStep returnStep = flow.getSteps().get(1);
        assertEquals("return", returnStep.getType());
        assertEquals("$input.id", returnStep.getReturnValueRef());
    }

    @Test
    void validatorRejectsInvalidBranchAndUnknownAwaitEvent() throws Exception {
        String json = """
                {
                  "namespace": "billing",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "events": [
                    { "name": "InvoiceSubmitted", "payload": [{ "name": "receipt", "type": "string" }] }
                  ],
                  "flows": [
                    {
                      "name": "FinalizeInvoice",
                      "input": { "concept": "Invoice", "mode": "update" },
                      "steps": [
                        { "name": "bad-branch", "type": "branch", "condition": "$input.id != null", "then": [] },
                        { "name": "bad-await", "type": "await", "awaitEvent": "GovernmentApproved" }
                      ]
                    }
                  ]
                }
                """;

        List<String> errors = new SemanticValidator().validate(parse(json));
        assertTrue(errors.stream().anyMatch(error -> error.contains("branch step must define non-empty then steps")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("await step references unknown event")));
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-flow-execution-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}
