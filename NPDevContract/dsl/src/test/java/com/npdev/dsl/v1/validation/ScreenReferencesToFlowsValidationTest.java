package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P3-T5c (stage 3): a screen's {@code procedure} / {@code onRowLoad} reference names a procedure OR the flow that
 * replaced it, so a model can move a procedure to flows[] without touching its panels.
 */
class ScreenReferencesToFlowsValidationTest {

    private static ValidationResult validate(String panelDataSourceJson) throws Exception {
        Path modelPath = Files.createTempFile("npdev-screen-flow-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "validation.screenflow.demo",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ],
                  "flows": [
                    { "name": "EnrichWidgetRows", "expose": "ui",
                      "steps": [ { "name": "echo", "type": "set", "value": "$input.rows", "output": "rows" },
                                 { "name": "ret", "type": "return", "value": "$rows" } ] }
                  ],
                  "panels": [
                    { "name": "TestPanel", "route": "/test-panel", "dataSources": [ %s ] }
                  ]
                }
                """.formatted(panelDataSourceJson));
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new SemanticValidator().validateWithWarnings(ast);
    }

    @Test
    void aDataSourceProcedureAndOnRowLoadMayNameAFlow() throws Exception {
        ValidationResult result = validate(
                "{ \"name\": \"widgets\", \"procedure\": \"EnrichWidgetRows\", \"onRowLoad\": \"EnrichWidgetRows\" }");
        assertFalse(result.hasErrors(), "Expected no errors: " + result.getErrors());
    }

    @Test
    void aNameNeitherAProcedureNorAFlowIsStillRejected() throws Exception {
        ValidationResult result = validate("{ \"name\": \"widgets\", \"concept\": \"Widget\", \"onRowLoad\": \"NoSuch\" }");
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("onRowLoad names a procedure not found")),
                result.getErrors().toString());
    }
}
