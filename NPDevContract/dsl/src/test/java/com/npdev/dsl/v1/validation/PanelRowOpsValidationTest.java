package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** LIFT-ROWOPS-P1: `rowOps: [add, delete]` on a declared Panel dataSource. */
class PanelRowOpsValidationTest {

    private static Path modelPath(String panelDataSourcesJson) throws Exception {
        Path modelPath = Files.createTempFile("npdev-panel-rowops-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "validation.panel.rowops.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "sku", "type": "string" } ] }
                  ],
                  "panels": [
                    {
                      "name": "TestPanel",
                      "route": "/test-panel",
                      "dataSources": %s
                    }
                  ]
                }
                """.formatted(panelDataSourcesJson));
        return modelPath;
    }

    private static ValidationResult validate(String panelDataSourcesJson) throws Exception {
        ModelAst ast = new JsonModelParser().parse(modelPath(panelDataSourcesJson));
        return new SemanticValidator().validateWithWarnings(ast);
    }

    @Test
    void addAndDeleteRowOpsCompileAndValidate() throws Exception {
        String dataSources = """
                [
                  { "name": "rows", "concept": "Order", "rowOps": ["add", "delete"], "addFormFields": ["sku"] }
                ]
                """;
        ValidationResult result = validate(dataSources);
        assertFalse(result.hasErrors(), "Expected no errors: " + result.getErrors());

        ModelAst ast = new JsonModelParser().parse(modelPath(dataSources));
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledPanelDataSource compiledDs = compiled.getPanels().stream()
                .findFirst().orElseThrow().dataSources().get(0);
        assertTrue(compiledDs.supportsAdd());
        assertTrue(compiledDs.supportsDelete());
        assertEquals(List.of("sku"), compiledDs.addFormFields());
    }

    @Test
    void unsupportedRowOpValueIsRejectedAtSchemaLevel() {
        // The JSON Schema's enum: [add, delete] on rowOps items rejects this before
        // SemanticValidator even runs -- an earlier, stricter gate than the DSL-level check below.
        ModelSchemaValidationException exception = assertThrows(ModelSchemaValidationException.class, () -> {
            new JsonModelParser().parse(modelPath("""
                    [
                      { "name": "rows", "concept": "Order", "rowOps": ["archive"] }
                    ]
                    """));
        });
        assertTrue(exception.getMessage().contains("rowOps"), "Message: " + exception.getMessage());
    }

    @Test
    void duplicateRowOpValueIsRejected() throws Exception {
        ValidationResult result = validate("""
                [
                  { "name": "rows", "concept": "Order", "rowOps": ["add", "add"] }
                ]
                """);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("duplicate rowOps value 'add'")),
                "Errors: " + result.getErrors());
    }

    @Test
    void rowOpsWithoutConceptIsRejected() throws Exception {
        ValidationResult result = validate("""
                [
                  { "name": "rows", "query": "SomeQuery", "rowOps": ["add"] }
                ]
                """);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("rowOps requires a concept-bound dataSource")),
                "Errors: " + result.getErrors());
    }

    @Test
    void unknownAddFormFieldIsRejected() throws Exception {
        ValidationResult result = validate("""
                [
                  { "name": "rows", "concept": "Order", "rowOps": ["add"], "addFormFields": ["bogus"] }
                ]
                """);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("addFormFields references unknown field bogus")),
                "Errors: " + result.getErrors());
    }

    @Test
    void panelWithoutRowOpsStillCompiles() throws Exception {
        String dataSources = """
                [
                  { "name": "rows", "concept": "Order" }
                ]
                """;
        ValidationResult result = validate(dataSources);
        assertFalse(result.hasErrors(), "Expected no errors: " + result.getErrors());

        ModelAst ast = new JsonModelParser().parse(modelPath(dataSources));
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledPanelDataSource compiledDs = compiled.getPanels().stream()
                .findFirst().orElseThrow().dataSources().get(0);
        assertFalse(compiledDs.supportsAdd());
        assertFalse(compiledDs.supportsDelete());
    }
}
