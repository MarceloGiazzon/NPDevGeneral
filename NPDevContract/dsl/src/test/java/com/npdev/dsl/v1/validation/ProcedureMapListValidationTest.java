package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A / Gap 6): a {@code mapList} procedure step
 * needs a non-blank {@code items} (shared with {@code forEach}'s own loop-items check), a
 * non-empty {@code select}, and a {@code target} naming the produced list.
 */
class ProcedureMapListValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String modelJson(String stepJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.qmaplist", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "procedures": [
                { "name": "MapParsedLines", "steps": [ %s ] }
              ]
            }
            """.formatted(stepJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    @Test
    void wellFormedMapListPasses() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "map-lines", "type": "mapList", "items": "$parsed.itens", "as": "linha",
              "select": { "produtoId": "$linha.codigo", "quantidade": "$linha.qtd" },
              "target": "itensMapeados" }
            """));
        assertTrue(errors.stream().noneMatch(e -> e.contains("mapList")), "unexpected errors: " + errors);
    }

    @Test
    void missingItemsIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "map-lines", "type": "mapList",
              "select": { "produtoId": "$linha.codigo" }, "target": "itensMapeados" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("mapList requires items")),
                "expected a missing-items error, got: " + errors);
    }

    @Test
    void missingSelectIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "map-lines", "type": "mapList", "items": "$parsed.itens", "as": "linha",
              "target": "itensMapeados" }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("select is required for mapList")),
                "expected a missing-select error, got: " + errors);
    }

    @Test
    void missingTargetIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "map-lines", "type": "mapList", "items": "$parsed.itens", "as": "linha",
              "select": { "produtoId": "$linha.codigo" } }
            """));
        assertTrue(errors.stream().anyMatch(e -> e.contains("target is required for mapList")),
                "expected a missing-target error, got: " + errors);
    }
}
