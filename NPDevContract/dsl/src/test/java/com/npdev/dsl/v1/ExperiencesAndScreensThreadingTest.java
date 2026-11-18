package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P5-T1: {@code experiences[]} and {@code screens[]} survive the whole pipeline -- parser, resolver, validator,
 * compiler, canonical JSON writer and reader -- and a pack may contribute them. Both arrays are opaque JSON in the
 * AST and the compiled model, so the proof is that the exact authored objects come back out the other end.
 */
class ExperiencesAndScreensThreadingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONCEPTS_AND_ROLES = """
              "roles": [ { "name": "Agent", "concepts": { "Ticket": ["read", "create", "update"] } } ],
              "concepts": [
                { "name": "Ticket", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string" },
                  { "name": "status", "type": "string" }
                ] }
              ],
              "flows": [
                { "name": "CloseTicket", "expose": "ui", "steps": [
                  { "name": "answer", "type": "set", "value": "'ok'", "output": "$answer" },
                  { "name": "done", "type": "return", "value": "$answer" } ] }
              ],
            """;

    private static final String MODEL = """
            {
              "namespace": "experiences.threading",
              "dslVersion": "3.0.0",
              "version": "1.0",
            """ + CONCEPTS_AND_ROLES + """
              "experiences": [
                { "name": "zeta", "for": ["Agent"], "home": "tickets", "navigation": [ { "screen": "tickets", "label": "Tickets" } ] },
                { "name": "alpha", "for": ["Agent"], "device": "web", "home": "tickets", "backOffice": true }
              ],
              "screens": [
                { "name": "tickets", "kind": "list", "concept": "Ticket", "columns": ["title", { "value": "status", "format": "badge" }],
                  "live": true },
                { "name": "ticket", "kind": "detail", "concept": "Ticket", "pick": "first", "fields": ["title", "status"],
                  "actions": [ { "label": "Close", "flow": "CloseTicket", "style": "danger", "confirm": "Close it?", "then": "tickets" } ] }
              ]
            }
            """;

    @TempDir
    Path temp;

    @Test
    void experiencesAndScreensSurviveParseValidateCompileAndTheCanonicalRoundTrip() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        assertEquals(2, ast.getExperiences().size());
        assertEquals(2, ast.getScreens().size());
        assertEquals("zeta", ast.getExperiences().get(0).get("name").asText());

        List<String> errors = new SemanticValidator().validate(ast);
        assertEquals(List.of(), errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(ast.getExperiences(), compiled.getExperiences());
        assertEquals(ast.getScreens(), compiled.getScreens());

        String first = CompiledModelCanonicalJson.toJson(compiled);
        JsonNode written = MAPPER.readTree(first);
        // Experiences keep declaration order (first match wins at sign-in); screens are written sorted by name.
        assertEquals(List.of("zeta", "alpha"), names(written.get("experiences")));
        assertEquals(List.of("ticket", "tickets"), names(written.get("screens")));

        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(first);
        assertEquals(compiled.getExperiences(), restored.getExperiences());
        assertEquals(List.of("ticket", "tickets"), names(MAPPER.valueToTree(restored.getScreens())));
        assertEquals(first, CompiledModelCanonicalJson.toJson(restored), "the round trip must be the identity");
    }

    @Test
    void aModelWithoutThemWritesNoSuchKeysSoExistingCanonicalJsonIsUnchanged() throws Exception {
        String bare = """
                {
                  "namespace": "experiences.none",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                """ + CONCEPTS_AND_ROLES + """
                  "capabilities": []
                }
                """;
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(bare));
        assertTrue(ast.getExperiences().isEmpty());
        assertTrue(ast.getScreens().isEmpty());
        assertEquals(List.of(), new SemanticValidator().validate(ast));

        String json = CompiledModelCanonicalJson.toJson(new ModelCompiler().compile(ast));
        JsonNode written = MAPPER.readTree(json);
        assertFalse(written.has("experiences"));
        assertFalse(written.has("screens"));
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);
        assertTrue(restored.getExperiences().isEmpty());
        assertTrue(restored.getScreens().isEmpty());
    }

    @Test
    void aNonArrayIsRefused() {
        for (String key : List.of("experiences", "screens")) {
            JsonNode root;
            try {
                root = MAPPER.readTree("{\"namespace\":\"x.y\",\"dslVersion\":\"3.0.0\",\"version\":\"1\",\"" + key + "\":{}}");
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
            assertThrows(Exception.class, () -> new JsonModelParser().parse(root), key + " as an object must be refused");
        }
    }

    @Test
    void aPackCanContributeThemAndTheirReferencesFollowThePackQualification() throws Exception {
        write("packs/support/pack.json", """
                {
                  "dslVersion": "3.0.0",
                  "pack": "support",
                  "version": "1.0.0",
                  "roles": [ { "name": "Agent", "grants": ["EXECUTE_FLOW"] } ],
                  "concepts": [
                    { "name": "Ticket", "access": { "read": "true", "write": "true" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "title", "type": "string" }
                    ] }
                  ],
                  "flows": [
                    { "name": "CloseTicket", "expose": "ui", "steps": [
                      { "name": "answer", "type": "set", "value": "'ok'", "output": "$answer" },
                      { "name": "done", "type": "return", "value": "$answer" } ] }
                  ],
                  "experiences": [
                    { "name": "desk", "for": ["Agent"], "home": "queue", "navigation": [ { "screen": "queue" } ] }
                  ],
                  "screens": [
                    { "name": "queue", "kind": "list", "concept": "Ticket", "columns": ["title"],
                      "actions": [ { "label": "Close", "flow": "CloseTicket" } ] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "experiences.pack",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/support/pack.json" } ]
                }
                """);

        ResolvedModelSource resolved = new ModelSourceResolver().resolve(model);
        JsonNode experience = resolved.resolvedRoot().get("experiences").get(0);
        JsonNode screen = resolved.resolvedRoot().get("screens").get(0);
        // The names (a URL segment, and what navigation points at) are never pack-qualified ...
        assertEquals("desk", experience.get("name").asText());
        assertEquals("queue", screen.get("name").asText());
        // ... but every reference out of them is.
        assertEquals("support::Agent", experience.get("for").get(0).asText());
        assertEquals("support::Ticket", screen.get("concept").asText());
        assertEquals("support::CloseTicket", screen.get("actions").get(0).get("flow").asText());

        ModelAst ast = new JsonModelParser().parse(resolved);
        assertEquals(List.of(), new SemanticValidator().validate(ast));
        assertEquals(1, new ModelCompiler().compile(ast).getScreens().size());
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode node : array) {
            out.add(node.get("name").asText());
        }
        return out;
    }

    private Path write(String relative, String content) throws IOException {
        Path target = temp.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return target;
    }
}
