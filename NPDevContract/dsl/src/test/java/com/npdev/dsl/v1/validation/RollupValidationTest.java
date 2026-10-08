package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledRollup;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P8 prelude: concept {@code rollups[]} -- parse, compile-time checks, canonical JSON round trip. */
class RollupValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> rollupErrors(String rollups) throws Exception {
        return new SemanticValidator().validate(parse(model(rollups))).stream()
                .filter(e -> e.contains("rollups[")).toList();
    }

    private static String model(String rollups) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "demo.rollup", "version": "1.0",
              "concepts": [
                { "name": "Mosaic", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string" },
                  { "name": "likeCount", "type": "integer" },
                  { "name": "totalWeight", "type": "decimal" } ],
                  "rollups": %s },
                { "name": "Like", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "mosaicId", "type": "reference", "reference": { "target": "Mosaic" } },
                  { "name": "note", "type": "string" },
                  { "name": "weight", "type": "decimal" } ] }
              ]
            }
            """.formatted(rollups);
    }

    @Test
    void validRollupsPassAndRoundTripThroughCanonicalJson() throws Exception {
        String rollups = """
            [ { "field": "likeCount", "from": "Like", "via": "mosaicId" },
              { "field": "totalWeight", "from": "Like", "via": "mosaicId", "fn": "sum", "of": "weight" } ]""";
        assertEquals(List.of(), rollupErrors(rollups));

        CompiledModel compiled = new ModelCompiler().compile(parse(model(rollups)));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        CompiledConcept mosaic = CompiledModelCanonicalJsonReader.fromJson(json).getConcepts().stream()
                .filter(c -> c.getName().equals("Mosaic")).findFirst().orElseThrow();
        assertEquals(List.of(
                new CompiledRollup("likeCount", "Like", "mosaicId", "count", null),
                new CompiledRollup("totalWeight", "Like", "mosaicId", "sum", "weight")), mosaic.getRollups());
    }

    @Test
    void conceptWithoutRollupsKeepsCanonicalJsonUnchanged() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model("[]")));
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("\"rollups\""));
    }

    @Test
    void refusesAnUnknownOrNonNumericTargetField() throws Exception {
        assertTrue(rollupErrors("[ { \"field\": \"nope\", \"from\": \"Like\", \"via\": \"mosaicId\" } ]")
                .stream().anyMatch(e -> e.contains(".field: unknown field 'nope'")));
        assertTrue(rollupErrors("[ { \"field\": \"title\", \"from\": \"Like\", \"via\": \"mosaicId\" } ]")
                .stream().anyMatch(e -> e.contains("must be a non-id int/integer/long/decimal field")));
    }

    @Test
    void refusesAViaThatIsNotAReferenceBackToTheParent() throws Exception {
        assertTrue(rollupErrors("[ { \"field\": \"likeCount\", \"from\": \"Like\", \"via\": \"note\" } ]")
                .stream().anyMatch(e -> e.contains("Like.note must be a reference to Mosaic")));
        assertTrue(rollupErrors("[ { \"field\": \"likeCount\", \"from\": \"Ghost\", \"via\": \"mosaicId\" } ]")
                .stream().anyMatch(e -> e.contains(".from: unknown concept 'Ghost'")));
    }

    @Test
    void sumNeedsANumericOfAndCountTakesNone() throws Exception {
        assertTrue(rollupErrors("[ { \"field\": \"totalWeight\", \"from\": \"Like\", \"via\": \"mosaicId\", \"fn\": \"sum\" } ]")
                .stream().anyMatch(e -> e.contains("needs the numeric Like field")));
        assertTrue(rollupErrors("[ { \"field\": \"totalWeight\", \"from\": \"Like\", \"via\": \"mosaicId\", \"fn\": \"sum\", \"of\": \"note\" } ]")
                .stream().anyMatch(e -> e.contains("Like.note must be int/integer/long/decimal")));
        assertTrue(rollupErrors("[ { \"field\": \"likeCount\", \"from\": \"Like\", \"via\": \"mosaicId\", \"of\": \"weight\" } ]")
                .stream().anyMatch(e -> e.contains("count counts rows and takes no 'of' field")));
    }

    @Test
    void refusesTwoRollupsMaintainingTheSameField() throws Exception {
        assertTrue(rollupErrors("""
            [ { "field": "likeCount", "from": "Like", "via": "mosaicId" },
              { "field": "likeCount", "from": "Like", "via": "mosaicId" } ]""")
                .stream().anyMatch(e -> e.contains("already maintained by another rollup")));
    }
}
