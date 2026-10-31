package com.npdev.dsl.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Concept {@code ui.selectionTools} is parsed, compiled, survives the canonical JSON, and is concept-level only. */
class SelectionToolsFlagTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String conceptUi, String fieldUi) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "selection.tools", "version": "1.0",
              "concepts": [
                { "name": "Visit", %s "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "note", "type": "string" %s }
                ] },
                { "name": "Plain", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true }
                ] }
              ]
            }
            """.formatted(conceptUi, fieldUi);
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    @Test
    void theFlagIsCompiledOnTheDeclaringConceptOnlyAndSurvivesTheCanonicalJson() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse("\"ui\": { \"selectionTools\": true },", ""));

        assertEquals(Boolean.TRUE, compiled.findConcept("Visit").orElseThrow().getUi().getSelectionTools());
        assertTrue(compiled.findConcept("Plain").orElseThrow().getUi() == null
                || compiled.findConcept("Plain").orElseThrow().getUi().getSelectionTools() == null);

        String json = CompiledModelCanonicalJson.toJson(compiled);
        assertTrue(json.contains("\"selectionTools\""), "the declared flag is written");
        CompiledModel reread = CompiledModelCanonicalJsonReader.fromJson(json);
        assertEquals(Boolean.TRUE, reread.findConcept("Visit").orElseThrow().getUi().getSelectionTools());
        assertEquals(json, CompiledModelCanonicalJson.toJson(reread), "round trip is byte-stable");
    }

    @Test
    void aFlagDeclaredOffIsKeptAsFalseNotDropped() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse("\"ui\": { \"selectionTools\": false },", ""));

        assertEquals(Boolean.FALSE, compiled.findConcept("Visit").orElseThrow().getUi().getSelectionTools());
    }

    @Test
    void aModelThatNeverDeclaresTheFlagGetsNoKeyInItsCanonicalJson() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse("\"ui\": { \"label\": \"Visits\" },", ""));

        assertNull(compiled.findConcept("Visit").orElseThrow().getUi().getSelectionTools());
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("selectionTools"),
                "existing canonical JSON stays byte-identical");
    }

    @Test
    void theFlagOnAFieldIsRefusedBecauseSelectionToolsIsConceptLevel() throws Exception {
        List<String> errors = new SemanticValidator().validate(parse("", ", \"ui\": { \"selectionTools\": true }"));

        assertTrue(errors.stream().anyMatch(e -> e.contains("field note") && e.contains("concept-level")), errors.toString());
    }
}
