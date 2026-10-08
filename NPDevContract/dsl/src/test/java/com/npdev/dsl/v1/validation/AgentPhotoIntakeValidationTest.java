package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledAgentAccessPhotoIntake;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P8 (G5): {@code agentAccess.photoIntake} -- parse, compile-time checks, canonical JSON round trip. */
class AgentPhotoIntakeValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> intakeErrors(String json) throws Exception {
        return new SemanticValidator().validate(parse(json)).stream()
                .filter(e -> e.contains("photoIntake")).toList();
    }

    private static String model(String channels, String intake, String aggregates) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "demo.photo", "version": "1.0",
              "concepts": [
                { "name": "Cap", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "label", "type": "string", "required": true },
                  { "name": "image", "type": "file" },
                  { "name": "rarity", "type": "enum", "enumValues": ["COMMON", "RARE"] },
                  { "name": "secret", "type": "string", "sensitive": true },
                  { "name": "count", "type": "integer" } ] }
              ],
              "procedures": [
                { "name": "Identify", "steps": [ { "name": "done", "type": "return", "value": "$input" } ] }
              ],
              "aggregates": %s,
              "agentAccess": {
                "channels": %s,
                "expose": [ { "concept": "Cap", "operations": ["list"] } ],
                "photoIntake": %s
              }
            }
            """.formatted(aggregates, channels, intake);
    }

    private static final String TELEGRAM = "{ \"telegram\": { \"enabled\": true } }";
    private static final String CAP_AGGREGATE = "[ { \"name\": \"CapAggregate\", \"root\": \"Cap\" } ]";
    private static final String VALID = """
        { "concept": "Cap", "imageField": "image", "captionField": "label", "procedure": "Identify",
          "defaults": { "rarity": "COMMON", "label": "$user.username" }, "description": "a cap photo" }""";

    @Test
    void validIntakePassesAndRoundTripsThroughCanonicalJson() throws Exception {
        String json = model(TELEGRAM, VALID, CAP_AGGREGATE);
        assertEquals(List.of(), intakeErrors(json));

        CompiledModel compiled = new ModelCompiler().compile(parse(json));
        CompiledAgentAccessPhotoIntake intake = CompiledModelCanonicalJsonReader
                .fromJson(CompiledModelCanonicalJson.toJson(compiled)).getAgentAccess().getPhotoIntake();
        assertEquals("Cap", intake.getConcept());
        assertEquals("image", intake.getImageField());
        assertEquals("label", intake.getCaptionField());
        assertEquals("Identify", intake.getProcedure());
        assertEquals(Map.of("rarity", "COMMON", "label", "$user.username"), intake.getDefaults());
        assertEquals("a cap photo", intake.getDescription());
    }

    @Test
    void absentIntakeStaysAbsentInCanonicalJson() throws Exception {
        String json = model(TELEGRAM, "null", CAP_AGGREGATE).replaceAll(",\\s*\"photoIntake\": null", "");
        CompiledModel compiled = new ModelCompiler().compile(parse(json));
        assertNull(compiled.getAgentAccess().getPhotoIntake());
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("photoIntake"));
    }

    @Test
    void refusesIntakeWithoutAChatChannel() throws Exception {
        assertTrue(intakeErrors(model("{ \"mcp\": { \"enabled\": true } }", VALID, CAP_AGGREGATE)).stream()
                .anyMatch(e -> e.contains("needs channels.telegram or channels.whatsapp")));
    }

    @Test
    void refusesUnknownConceptAndNonFileImageField() throws Exception {
        assertTrue(intakeErrors(model(TELEGRAM, "{ \"concept\": \"Nope\", \"imageField\": \"image\" }", CAP_AGGREGATE))
                .stream().anyMatch(e -> e.contains(".concept: 'Nope' does not resolve")));
        assertTrue(intakeErrors(model(TELEGRAM, "{ \"concept\": \"Cap\", \"imageField\": \"label\" }", CAP_AGGREGATE))
                .stream().anyMatch(e -> e.contains(".imageField: 'label' must be a file field")));
    }

    @Test
    void refusesNonStringCaptionAndBadDefaults() throws Exception {
        List<String> errors = intakeErrors(model(TELEGRAM, """
            { "concept": "Cap", "imageField": "image", "captionField": "count",
              "defaults": { "nope": "x", "secret": "y" } }""", CAP_AGGREGATE));
        assertTrue(errors.stream().anyMatch(e -> e.contains(".captionField: 'count' must be a string field")));
        assertTrue(errors.stream().anyMatch(e -> e.contains(".defaults: 'nope' is not a field")));
        assertTrue(errors.stream().anyMatch(e -> e.contains(".defaults: 'secret' is sensitive")));
    }

    @Test
    void refusesUnknownProcedureOrOneWithNoAggregateRootedAtTheConcept() throws Exception {
        assertTrue(intakeErrors(model(TELEGRAM,
                "{ \"concept\": \"Cap\", \"imageField\": \"image\", \"procedure\": \"Nope\" }", CAP_AGGREGATE))
                .stream().anyMatch(e -> e.contains(".procedure: 'Nope' does not resolve")));
        assertTrue(intakeErrors(model(TELEGRAM, VALID, "[]"))
                .stream().anyMatch(e -> e.contains("no aggregate has root 'Cap'")));
    }
}
