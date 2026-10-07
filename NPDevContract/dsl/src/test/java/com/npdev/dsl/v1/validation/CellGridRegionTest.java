package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledCellGrid;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3b (Pigmentampas plan, 2026-10-07): transaction.regions.<collection> with render "cellGrid" -- a
 * child collection painted as a board. Validation checks every named field where it is read from;
 * the config survives the canonical JSON round trip and reaches the workbench descriptor.
 */
class CellGridRegionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID_GRID = """
            { "rowField": "row", "colField": "col", "valueField": "capId",
              "rowsField": "rows", "colsField": "cols", "defaultRows": 20, "defaultCols": 33,
              "layout": "hexOffset",
              "palette": { "query": "Caps", "labelField": "label", "colorField": "color" } }
            """;

    private static String model(String regionsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "cellgrid.test", "version": "1.0",
              "concepts": [
                { "name": "Board", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "rows", "type": "integer" },
                  { "name": "cols", "type": "integer" } ] },
                { "name": "Cap", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "label", "type": "string" },
                  { "name": "color", "type": "string" } ] },
                { "name": "Brewery", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Cell", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "boardId", "type": "uuid" },
                  { "name": "row", "type": "integer" },
                  { "name": "col", "type": "integer" },
                  { "name": "capId", "type": "reference", "reference": { "target": "Cap" } } ] }
              ],
              "queries": [
                { "name": "Caps", "concept": "Cap" },
                { "name": "Breweries", "concept": "Brewery" }
              ],
              "aggregates": [
                { "name": "BoardAggregate", "root": "Board",
                  "collections": [ { "name": "cells", "concept": "Cell", "childField": "boardId", "ownership": "owned" } ] }
              ],
              "autoPanels": [ { "name": "Boards", "aggregate": "BoardAggregate",
                "transaction": { "regions": %s } } ]
            }
            """.formatted(regionsJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static List<String> validateGrid(String gridJson) throws Exception {
        return validate(model("{ \"cells\": { \"render\": \"cellGrid\", \"cellGrid\": " + gridJson + " } }"));
    }

    private static void assertError(List<String> errors, String fragment) {
        assertTrue(errors.stream().anyMatch(e -> e.contains("transaction.regions.cells") && e.contains(fragment)),
                "expected an error containing '" + fragment + "', got: " + errors);
    }

    @Test
    void validCellGridPassesCleanly() throws Exception {
        List<String> errors = validateGrid(VALID_GRID);
        assertTrue(errors.isEmpty(), "expected no validation errors, got: " + errors);
    }

    @Test
    void cellGridWithoutConfigIsRejected() throws Exception {
        assertError(validate(model("{ \"cells\": { \"render\": \"cellGrid\" } }")), "no cellGrid block is declared");
    }

    @Test
    void cellGridOnTheHeaderIsRejected() throws Exception {
        List<String> errors = validate(model("{ \"header\": { \"render\": \"cellGrid\", \"cellGrid\": " + VALID_GRID + " } }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("top-level collection region")), "got: " + errors);
    }

    @Test
    void unknownFieldsAreRejectedWhereTheyAreRead() throws Exception {
        assertError(validateGrid(VALID_GRID.replace("\"rowField\": \"row\"", "\"rowField\": \"rowz\"")),
                "cellGrid.rowField 'rowz' is not a field of Cell");
        assertError(validateGrid(VALID_GRID.replace("\"rowsField\": \"rows\"", "\"rowsField\": \"height\"")),
                "cellGrid.rowsField 'height' is not a field of Board");
        assertError(validateGrid(VALID_GRID.replace("\"labelField\": \"label\"", "\"labelField\": \"name\"")),
                "cellGrid.palette.labelField 'name' is not a field of Cap");
    }

    @Test
    void paletteQueryMustExistAndMatchTheValueReference() throws Exception {
        assertError(validateGrid(VALID_GRID.replace("\"query\": \"Caps\"", "\"query\": \"Nope\"")),
                "palette.query not found: Nope");
        assertError(validateGrid(VALID_GRID
                        .replace("\"query\": \"Caps\"", "\"query\": \"Breweries\"")
                        .replace(", \"labelField\": \"label\", \"colorField\": \"color\"", "")),
                "both must be the same concept");
    }

    @Test
    void unknownLayoutIsRejectedByTheSchema() {
        ModelSchemaValidationException e = assertThrows(ModelSchemaValidationException.class,
                () -> validateGrid(VALID_GRID.replace("hexOffset", "triangle")));
        assertTrue(e.getMessage().contains("cellGrid.layout"), e.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void configSurvivesCanonicalRoundTripAndReachesTheWorkbenchDescriptor() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(
                model("{ \"cells\": { \"render\": \"cellGrid\", \"cellGrid\": " + VALID_GRID + " } }")));
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));

        CompiledCellGrid grid = restored.getAutoPanels().get(0).transaction().regions().get("cells").cellGrid();
        assertNotNull(grid, "cellGrid lost in the canonical JSON round trip");
        assertEquals(new CompiledCellGrid("row", "col", "capId", "rows", "cols", 20, 33, "hexOffset",
                "Caps", "label", null, "color"), grid);

        CompiledPanel workbench = compiled.getPanels().stream()
                .filter(p -> p.metadata() != null && p.metadata().get("workbench") != null).findFirst()
                .orElseThrow(() -> new AssertionError("expected a workbench panel"));
        Map<String, Object> wb = (Map<String, Object>) workbench.metadata().get("workbench");
        Map<String, Object> cells = ((List<Map<String, Object>>) wb.get("sections")).get(0);
        assertEquals("cellGrid", cells.get("render"));
        Map<String, Object> descriptor = (Map<String, Object>) cells.get("cellGrid");
        assertEquals("capId", descriptor.get("valueField"));
        assertEquals("hexOffset", descriptor.get("layout"));
        assertEquals(33, descriptor.get("defaultCols"));
        assertEquals("Caps", ((Map<String, Object>) descriptor.get("palette")).get("query"));
    }
}
