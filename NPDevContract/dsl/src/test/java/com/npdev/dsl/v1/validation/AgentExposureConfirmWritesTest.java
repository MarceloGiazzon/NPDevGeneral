package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledAgentAccessExposure;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code agentAccess.expose[].confirmWrites}: a chat channel asks the person to confirm before a create, update, delete
 * or flow run, unless the author switched that off for one exposure. The default is "ask"; a value written as false must
 * survive parse, compile and the canonical-JSON round trip, or an app would silently start (or stop) asking.
 */
class AgentExposureConfirmWritesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "3.0.0", "namespace": "demo.confirm", "version": "1.0",
          "concepts": [
            { "name": "Note", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "text", "type": "string", "required": true } ] },
            { "name": "Tag", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "label", "type": "string", "required": true } ] }
          ],
          "agentAccess": {
            "channels": { "mcp": { "enabled": true } },
            "expose": [
              { "concept": "Note", "operations": ["list", "get", "create"], "confirmWrites": false },
              { "concept": "Tag", "operations": ["list", "get", "create"] }
            ]
          }
        }
        """;

    private static ModelAst parse() throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(MODEL));
    }

    @Test
    void theModelIsValid() throws Exception {
        assertEquals(List.of(), new SemanticValidator().validate(parse()));
    }

    @Test
    void anExposureThatSwitchesConfirmationOffKeepsItAndTheOtherOneStillAsks() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse());
        List<CompiledAgentAccessExposure> expose = CompiledModelCanonicalJsonReader
                .fromJson(CompiledModelCanonicalJson.toJson(compiled)).getAgentAccess().getExpose();
        CompiledAgentAccessExposure note = expose.stream().filter(e -> "Note".equals(e.getConcept())).findFirst().orElseThrow();
        CompiledAgentAccessExposure tag = expose.stream().filter(e -> "Tag".equals(e.getConcept())).findFirst().orElseThrow();
        assertFalse(note.getConfirmWrites(), "confirmWrites: false must survive the round trip");
        assertTrue(tag.getConfirmWrites(), "an exposure that says nothing asks for confirmation (the default)");
    }
}
