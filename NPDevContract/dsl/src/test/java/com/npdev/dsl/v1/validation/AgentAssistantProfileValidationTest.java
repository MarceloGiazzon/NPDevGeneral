package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledAgentAccessAssistant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AIP-3 (3.4a): {@code agentAccess.assistant.profile} / {@code effort} -- validation, compile, round trip. */
class AgentAssistantProfileValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String assistant) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree("""
            {
              "dslVersion": "1.0.0", "namespace": "demo.assistant", "version": "1.0",
              "concepts": [ { "name": "Cap", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "agentAccess": {
                "assistant": %s,
                "channels": { "telegram": { "enabled": true } },
                "expose": [ { "concept": "Cap", "operations": ["list"] } ]
              }
            }
            """.formatted(assistant)));
    }

    private static List<String> assistantErrors(String assistant) throws Exception {
        return new SemanticValidator().validate(parse(assistant)).stream()
                .filter(e -> e.contains("agentAccess.assistant")).toList();
    }

    @Test
    void aProfileCompilesToItsProviderModelAndEffortAndSurvivesTheRoundTrip() throws Exception {
        ModelAst ast = parse("{ \"name\": \"Bot\", \"profile\": \"claude\", \"effort\": \"medium\" }");
        assertEquals(List.of(), assistantErrors("{ \"name\": \"Bot\", \"profile\": \"claude\", \"effort\": \"medium\" }"));

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledAgentAccessAssistant assistant = CompiledModelCanonicalJsonReader.fromJson(
                CompiledModelCanonicalJson.toJson(compiled)).getAgentAccess().getAssistant();
        assertEquals(List.of("claude", "anthropic", "claude-opus-5-5", "medium"),
                List.of(assistant.getProfile(), assistant.getVendor(), assistant.getModel(), assistant.getEffort()));
        assertEquals("Bot", assistant.getName());
    }

    @Test
    void withoutItsOwnEffortTheProfilesEffortApplies() throws Exception {
        CompiledAgentAccessAssistant assistant = new ModelCompiler().compile(parse("{ \"profile\": \"claude\" }"))
                .getAgentAccess().getAssistant();
        assertEquals("high", assistant.getEffort());
    }

    @Test
    void noProfileLeavesTheVendorToTheRuntimeDefaults() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse("{ \"name\": \"Bot\" }"));
        CompiledAgentAccessAssistant assistant = compiled.getAgentAccess().getAssistant();
        assertNull(assistant.getVendor());
        assertNull(assistant.getEffort());
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("\"profile\""),
                "a model without a profile keeps its canonical JSON");
    }

    @Test
    void anUnknownProfileIsRejectedNamingTheKnownOnes() throws Exception {
        List<String> errors = assistantErrors("{ \"profile\": \"turbo\" }");
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("profile 'turbo' is not in the NPDev AI catalogue")
                && errors.get(0).contains("fast"), errors.toString());
    }
}
