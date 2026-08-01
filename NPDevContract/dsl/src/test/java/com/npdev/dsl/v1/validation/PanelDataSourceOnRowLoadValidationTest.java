package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 6 Move C (docs/MOVE6_TYPED_SURFACE_PLAN.md §4): {@code panelDataSource.onRowLoad} must name
 * a declared procedure, validated the same way {@code procedure} already was on the same object.
 */
class PanelDataSourceOnRowLoadValidationTest {

    private static ValidationResult validate(String panelDataSourceJson) throws Exception {
        Path modelPath = Files.createTempFile("npdev-onrowload-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "validation.onrowload.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ],
                  "procedures": [
                    { "name": "EnrichWidgetRowsProcedure", "steps": [
                        { "name": "echo-rows", "type": "mapValue", "value": "$input.rows", "target": "rows" },
                        { "name": "ret", "type": "return", "value": "$rows" } ] }
                  ],
                  "panels": [
                    {
                      "name": "TestPanel",
                      "route": "/test-panel",
                      "dataSources": [ %s ]
                    }
                  ]
                }
                """.formatted(panelDataSourceJson));
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new SemanticValidator().validateWithWarnings(ast);
    }

    @Test
    void onRowLoadNamingADeclaredProcedureProducesNoErrors() throws Exception {
        ValidationResult result = validate(
                "{ \"name\": \"widgets\", \"concept\": \"Widget\", \"onRowLoad\": \"EnrichWidgetRowsProcedure\" }");
        assertFalse(result.hasErrors(), "Expected no errors: " + result.getErrors());
    }

    @Test
    void onRowLoadNamingAnUndeclaredProcedureIsRejected() throws Exception {
        ValidationResult result = validate(
                "{ \"name\": \"widgets\", \"concept\": \"Widget\", \"onRowLoad\": \"NoSuchProcedure\" }");
        assertTrue(result.hasErrors(), "Expected an onRowLoad-not-found error");
        assertTrue(result.getErrors().stream().anyMatch(
                        e -> e.contains("onRowLoad names a procedure not found") && e.contains("NoSuchProcedure")),
                "expected an onRowLoad-not-found error, got: " + result.getErrors());
    }

    @Test
    void onRowLoadAndProduceProcedureCanCoexistIndependently() throws Exception {
        // procedure() REPLACES the row source (produce); onRowLoad ENRICHES rows the gateway
        // produced (patch) -- deliberately distinct, both may be declared on the same data source.
        ValidationResult result = validate(
                "{ \"name\": \"widgets\", \"procedure\": \"EnrichWidgetRowsProcedure\", "
                        + "\"onRowLoad\": \"EnrichWidgetRowsProcedure\" }");
        assertFalse(result.hasErrors(), "Expected no errors: " + result.getErrors());
    }
}
