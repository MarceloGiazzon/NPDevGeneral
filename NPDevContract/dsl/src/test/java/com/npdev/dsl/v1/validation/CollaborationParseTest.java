package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledCollaboration;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PROC-5/PROC-6: concept {@code collaboration} -- parse, compile, canonical JSON round trip, check. */
class CollaborationParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static String model(String collaboration) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "demo.collab", "version": "1.0",
              "concepts": [
                { "name": "Mosaic"%s, "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string" } ] },
                { "name": "Plain", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """.formatted(collaboration == null ? "" : ", \"collaboration\": " + collaboration);
    }

    @Test
    void declaredCollaborationCompilesAndSurvivesTheCanonicalRoundTrip() throws Exception {
        String json = model("{ \"comments\": true, \"attachments\": false }");
        assertEquals(List.of(), new SemanticValidator().validate(parse(json)));

        CompiledModel compiled = new ModelCompiler().compile(parse(json));
        CompiledCollaboration direct = compiled.findConcept("Mosaic").map(CompiledConcept::getCollaboration).orElseThrow();
        assertTrue(direct.comments());
        assertFalse(direct.attachments());

        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        CompiledCollaboration back = restored.findConcept("Mosaic").map(CompiledConcept::getCollaboration).orElseThrow();
        assertEquals(direct, back);
        assertNull(restored.findConcept("Plain").map(CompiledConcept::getCollaboration).orElse(null));
    }

    @Test
    void aConceptWithoutTheKeyWritesNoCollaborationNode() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model(null)));
        JsonNode canonical = MAPPER.readTree(CompiledModelCanonicalJson.toJson(compiled));
        for (JsonNode concept : canonical.get("concepts")) {
            assertFalse(concept.has("collaboration"), concept.get("name").asText());
        }
    }

    @Test
    void attachmentsWithoutCommentsIsRefusedWithAFix() throws Exception {
        List<String> errors = new SemanticValidator().validate(parse(model("{ \"attachments\": true }")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("collaboration") && e.contains("suggestedFix")), errors.toString());
    }
}
