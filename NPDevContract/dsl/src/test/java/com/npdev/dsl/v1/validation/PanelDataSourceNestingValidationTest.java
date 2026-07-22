package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelDataSourceNestingValidationTest {

    private static ValidationResult validate(String panelDataSourcesJson) throws Exception {
        Path modelPath = Files.createTempFile("npdev-panel-nesting-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "validation.panel.nesting.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Parent", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "Child", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
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
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new SemanticValidator().validateWithWarnings(ast);
    }

    @Test
    void legalParentChildDeclarationProducesNoErrors() throws Exception {
        ValidationResult result = validate("""
                [
                  { "name": "parents", "concept": "Parent" },
                  { "name": "children", "concept": "Child", "parentDataSource": "parents", "parentField": "id", "childField": "parentId" }
                ]
                """);
        assertFalse(result.hasErrors(), "Expected no errors for a legal one-level parent/child declaration: " + result.getErrors());
    }

    @Test
    void selfReferencingParentDataSourceIsRejected() throws Exception {
        ValidationResult result = validate("""
                [
                  { "name": "parents", "concept": "Parent", "parentDataSource": "parents", "childField": "parentId" }
                ]
                """);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("cannot declare itself as parentDataSource")),
                "Errors: " + result.getErrors());
    }

    @Test
    void missingParentDataSourceIsRejected() throws Exception {
        ValidationResult result = validate("""
                [
                  { "name": "children", "concept": "Child", "parentDataSource": "doesNotExist", "childField": "parentId" }
                ]
                """);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("parentDataSource not found among sibling dataSources")),
                "Errors: " + result.getErrors());
    }

    @Test
    void twoLevelNestingIsRejected() throws Exception {
        ValidationResult result = validate("""
                [
                  { "name": "grandparents", "concept": "Parent" },
                  { "name": "parents", "concept": "Child", "parentDataSource": "grandparents", "childField": "grandparentId" },
                  { "name": "children", "concept": "Child", "parentDataSource": "parents", "childField": "parentId" }
                ]
                """);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("nesting is limited to one level")),
                "Errors: " + result.getErrors());
    }

    @Test
    void missingChildFieldIsRejected() throws Exception {
        ValidationResult result = validate("""
                [
                  { "name": "parents", "concept": "Parent" },
                  { "name": "children", "concept": "Child", "parentDataSource": "parents" }
                ]
                """);
        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("childField is required when parentDataSource is declared")),
                "Errors: " + result.getErrors());
    }

    @Test
    void procedureBoundChildDataSourceIsRejected() throws Exception {
        ValidationResult result = validate("""
                [
                  { "name": "parents", "concept": "Parent" },
                  { "name": "children", "procedure": "SomeProcedure", "parentDataSource": "parents", "childField": "parentId" }
                ]
                """);
        assertTrue(result.hasErrors());
        List<String> errors = result.getErrors();
        assertTrue(errors.stream().anyMatch(e -> e.contains("must be concept/query-bound, not procedure-bound")),
                "Errors: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("procedure not found: SomeProcedure")),
                "Expect the pre-existing procedure-existence check to also fire: " + errors);
    }
}
