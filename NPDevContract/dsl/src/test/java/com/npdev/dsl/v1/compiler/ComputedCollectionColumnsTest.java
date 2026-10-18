package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6-5: {@code transaction.computed[].collection} makes a computed column live per row of a workbench
 * section/band/sub-band (evaluated in the browser by npdev-expr.js). It must reach the descriptor, survive
 * canonical JSON, and be refused when it names nothing real or reads a field that does not exist.
 */
class ComputedCollectionColumnsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String computedJson, String derivedJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "f6.computedcol", "version": "1.0",
              "concepts": [
                { "name": "Ord", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "discount", "type": "integer" } ] },
                { "name": "Line", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "ordId", "type": "uuid" },
                  { "name": "qty", "type": "integer" }, { "name": "price", "type": "integer" } ] },
                { "name": "Part", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "lineId", "type": "uuid" }, { "name": "qty", "type": "integer" } ] }
              ],
              "aggregates": [
                { "name": "Ord", "root": "Ord", "collections": [
                  { "name": "lines", "concept": "Line", "childField": "ordId", "ownership": "owned", "collections": [
                    { "name": "parts", "concept": "Part", "childField": "lineId", "ownership": "owned" } ] } ] }
              ],
              "autoPanels": [ { "aggregate": "Ord", "transaction": { "computed": %s%s } } ]
            }
            """.formatted(computedJson, derivedJson.isEmpty() ? "" : ", \"derivedFields\": " + derivedJson);
    }

    private static final String GOOD = """
        [ { "col": "total", "expr": "qty * price", "collection": "lines" },
          { "col": "net", "expr": "qty * price - $root.discount", "collection": "lines" },
          { "col": "total", "expr": "qty * 2", "collection": "lines.parts" } ]""";

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static ValidationResult validate(String computedJson, String derivedJson) throws Exception {
        return new SemanticValidator().validateWithWarnings(parse(model(computedJson, derivedJson)));
    }

    private static boolean anyError(ValidationResult result, String fragment) {
        return result.getErrors().stream().anyMatch(e -> e.contains(fragment) && e.contains("suggestedFix"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workbench(CompiledModel compiled) {
        CompiledPanel panel = compiled.getPanels().stream().filter(p -> "OrdWorkbench".equals(p.name())).findFirst().orElseThrow();
        return (Map<String, Object>) panel.metadata().get("workbench");
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectionComputedColumnsReachTheSectionAndBandDescriptors() throws Exception {
        Map<String, Object> wb = workbench(new ModelCompiler().compile(parse(model(GOOD, ""))));
        Map<String, Object> lines = ((List<Map<String, Object>>) wb.get("sections")).get(0);
        List<Map<String, Object>> lineColumns = (List<Map<String, Object>>) lines.get("computed");
        assertEquals(List.of("total", "net"), lineColumns.stream().map(c -> c.get("col")).toList());
        assertEquals("qty * price", lineColumns.get(0).get("expr"));
        Map<String, Object> parts = ((List<Map<String, Object>>) lines.get("bands")).get(0);
        List<Map<String, Object>> partColumns = (List<Map<String, Object>>) parts.get("computed");
        assertEquals(1, partColumns.size());
        assertEquals("qty * 2", partColumns.get(0).get("expr"));
    }

    @Test
    void aModelWithoutCollectionColumnsLeavesTheDescriptorUntouched() throws Exception {
        Map<String, Object> wb = workbench(new ModelCompiler().compile(parse(model("[ { \"col\": \"x\", \"expr\": \"1\" } ]", ""))));
        @SuppressWarnings("unchecked")
        Map<String, Object> lines = ((List<Map<String, Object>>) wb.get("sections")).get(0);
        assertNull(lines.get("computed"), "a column without a collection is not a per-row workbench column");
    }

    @Test
    void collectionSurvivesCanonicalJson() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model(GOOD, "")));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        assertTrue(json.contains("\"collection\" : \"lines.parts\"") || json.contains("\"collection\":\"lines.parts\""));
        CompiledModel reread = CompiledModelCanonicalJsonReader.fromJson(json);
        var computed = reread.getAutoPanels().get(0).transaction().computed();
        assertEquals(3, computed.size());
        assertEquals("lines.parts", computed.get(2).collection());
        CompiledModel plain = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(
                new ModelCompiler().compile(parse(model("[ { \"col\": \"x\", \"expr\": \"1\" } ]", "")))));
        assertNull(plain.getAutoPanels().get(0).transaction().computed().get(0).collection(),
                "a column declared without a collection stays without one");
    }

    @Test
    void validCollectionColumnsHaveNoErrorsAndNoRecomputeWarning() throws Exception {
        ValidationResult result = validate(GOOD, "");
        assertEquals(List.of(), result.getErrors());
        assertTrue(result.getWarnings().stream().noneMatch(w -> w.contains("computed[]") && w.contains("metadata only")),
                "live browser columns need no recompute procedure: " + result.getWarnings());
    }

    @Test
    void sameColumnNameOnDifferentCollectionsIsFineButTwiceOnOneIsNot() throws Exception {
        assertEquals(List.of(), validate(GOOD, "").getErrors());
        ValidationResult twice = validate("[ { \"col\": \"t\", \"expr\": \"1\", \"collection\": \"lines\" }, { \"col\": \"t\", \"expr\": \"2\", \"collection\": \"lines\" } ]", "");
        assertTrue(twice.getErrors().stream().anyMatch(e -> e.contains("duplicate computed column")), "got: " + twice.getErrors());
    }

    @Test
    void unknownCollectionPathIsAnError() throws Exception {
        assertTrue(anyError(validate("[ { \"col\": \"t\", \"expr\": \"1\", \"collection\": \"nope\" } ]", ""),
                "collection 'nope' is not a collection path of aggregate Ord"));
        assertTrue(anyError(validate("[ { \"col\": \"t\", \"expr\": \"1\", \"collection\": \"lines.nope\" } ]", ""),
                "collection 'lines.nope' is not a collection path"));
    }

    @Test
    void expressionMustReadRealFields() throws Exception {
        assertTrue(anyError(validate("[ { \"col\": \"t\", \"expr\": \"qty * ghost\", \"collection\": \"lines\" } ]", ""),
                "'ghost' is not a field of Line"));
        assertTrue(anyError(validate("[ { \"col\": \"t\", \"expr\": \"qty - $root.ghost\", \"collection\": \"lines\" } ]", ""),
                "$root.ghost is not a field of Ord"));
        assertTrue(anyError(validate("[ { \"col\": \"t\", \"expr\": \"price\", \"collection\": \"lines.parts\" } ]", ""),
                "'price' is not a field of Part"));
    }

    @Test
    void collectionNeedsAnAggregateBoundTransaction() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "f6.computedcol2", "version": "1.0",
              "concepts": [ { "name": "Thing", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true }, { "name": "a", "type": "integer" } ] } ],
              "autoPanels": [ { "concept": "Thing", "selection": { "computed": [ { "col": "t", "expr": "a", "collection": "x" } ] } } ]
            }
            """;
        ValidationResult result = new SemanticValidator().validateWithWarnings(parse(json));
        assertTrue(anyError(result, "collection is only valid on an aggregate-bound transaction"), "got: " + result.getErrors());
    }

    @Test
    void richDerivedExpressionsMustParse() throws Exception {
        assertEquals(List.of(), validate("[]", "{ \"net\": { \"expression\": \"sum(lines.qty) - discount\" } }").getErrors());
        ValidationResult bad = validate("[]", "{ \"net\": { \"expression\": \"sum(lines.qty) -\" } }");
        assertTrue(anyError(bad, "derivedFields.net: invalid expression"), "got: " + bad.getErrors());
        assertNotNull(bad);
    }
}
