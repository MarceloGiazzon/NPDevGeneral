package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 8 D3 (item G6, docs/MOVE8_CLOSE_TABLE_SPEC.md): {@code selection.dataSource.procedure} must
 * name a real declared procedure, the same class of check every other procedure reference on an
 * AutoPanel already gets (workbench actions, hooks, etc.).
 */
class AutoPanelSelectionDataSourceValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String selectionJson) throws Exception {
        String modelJson = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.d3.validation", "version": "1.0",
              "concepts": [
                { "name": "Cliente", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "procedures": [
                { "name": "RealProcedure", "steps": [ { "name": "ret", "type": "return", "value": "$input" } ] }
              ],
              "autoPanels": [ { "concept": "Cliente", "selection": %s } ]
            }
            """.formatted(selectionJson);
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(modelJson));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void realProcedureNamePassesCleanly() throws Exception {
        List<String> errors = validate("{ \"dataSource\": { \"procedure\": \"RealProcedure\" } }");
        assertTrue(errors.isEmpty(), "expected no validation errors, got: " + errors);
    }

    @Test
    void unknownProcedureNameIsRejected() throws Exception {
        List<String> errors = validate("{ \"dataSource\": { \"procedure\": \"NoSuchProcedure\" } }");
        assertTrue(errors.stream().anyMatch(e -> e.contains("selection.dataSource")
                        && e.contains("procedure not found: NoSuchProcedure")),
                "expected a procedure-not-found error, got: " + errors);
    }

    @Test
    void noDataSourceDeclaredIsUnaffected() throws Exception {
        List<String> errors = validate("{}");
        assertTrue(errors.isEmpty(), "expected no validation errors, got: " + errors);
    }
}
