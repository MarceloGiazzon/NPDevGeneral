package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledEvent;
import com.npdev.dsl.v1.compiled.CompiledEventField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
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

class DslEventModelTest {

    @Test
    void compilesEventSchemaAndEmitEventFlowStep() throws Exception {
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
                        { "name": "number", "type": "string", "required": true }
                      ]
                    }
                  ],
                  "events": [
                    {
                      "name": "InvoiceIssued",
                      "payload": [
                        { "name": "invoiceId", "type": "uuid" }
                      ]
                    }
                  ],
                  "flows": [
                    {
                      "name": "IssueInvoice",
                      "input": { "concept": "Invoice", "mode": "update" },
                      "steps": [
                        { "type": "emitEvent", "event": "InvoiceIssued", "from": "Invoice" }
                      ]
                    }
                  ]
                }
                """;

        ModelAst ast = parse(json);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no semantic errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledEvent event = compiled.findEvent("InvoiceIssued").orElseThrow();
        assertEquals(1, event.getPayloadFields().size());
        CompiledEventField payloadField = event.getPayloadFields().get(0);
        assertEquals("invoiceId", payloadField.getName());
        assertEquals("uuid", payloadField.getType());

        CompiledFlow flow = compiled.findFlow("IssueInvoice").orElseThrow();
        assertEquals("InvoiceIssued", flow.getSteps().get(0).getEventName());
        assertEquals("Invoice", flow.getSteps().get(0).getPayloadRef());
    }

    private static ModelAst parse(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-events-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(modelFile);
    }
}
