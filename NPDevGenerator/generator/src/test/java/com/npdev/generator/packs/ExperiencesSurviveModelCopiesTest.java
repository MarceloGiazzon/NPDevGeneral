package com.npdev.generator.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import com.npdev.kernel.abi.KernelAbi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FORM P5-T2: four places rebuild a {@link CompiledModel} through the constructor chain
 * ({@code BuiltinPackComposer.merge}, both {@code PackExtensionComposer} compose methods and
 * {@code GeneratorFacade.excludeLinkedSealedPackConcepts}); each silently dropped every catalog added after the
 * constructor they called. This pins that {@code experiences[]} and {@code screens[]} (and the other late catalogs)
 * survive all of them, and that the generated app still receives {@code experiences.json} on the facade path.
 */
class ExperiencesSurviveModelCopiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
            {
              "namespace": "experiences.copies",
              "dslVersion": "3.0.0",
              "version": "1.0",
              "roles": [ { "name": "Agent", "concepts": { "Ticket": ["read", "create", "update"] } } ],
              "concepts": [
                { "name": "Ticket", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string" } ] }
              ],
              "flows": [
                { "name": "CloseTicket", "expose": "ui", "steps": [
                  { "name": "answer", "type": "set", "value": "'ok'", "output": "$answer" },
                  { "name": "done", "type": "return", "value": "$answer" } ] }
              ],
              "experiences": [
                { "name": "agent", "for": ["Agent"], "home": "tickets", "navigation": [ { "screen": "tickets", "label": "Tickets" } ] }
              ],
              "screens": [
                { "name": "tickets", "kind": "list", "concept": "Ticket", "columns": ["title"],
                  "actions": [ { "label": "Close", "flow": "CloseTicket" } ] }
              ]
            }
            """;

    @TempDir
    Path tempDir;

    private CompiledModel compile() throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(MODEL)));
    }

    private static void assertKept(CompiledModel original, CompiledModel copy, String where) {
        assertEquals(1, copy.getExperiences().size(), where + " dropped experiences[]");
        assertEquals(original.getExperiences(), copy.getExperiences(), where);
        assertEquals(1, copy.getScreens().size(), where + " dropped screens[]");
        assertEquals(original.getScreens(), copy.getScreens(), where);
        assertEquals(original.getWebhooks(), copy.getWebhooks(), where);
        assertEquals(original.getSequences(), copy.getSequences(), where);
        assertEquals(original.getSeeds(), copy.getSeeds(), where);
        assertEquals(original.getAgentAccess(), copy.getAgentAccess(), where);
    }

    @Test
    void builtinPackMergeKeepsExperiencesAndScreens() throws Exception {
        CompiledModel app = compile();
        assertEquals(1, app.getExperiences().size());
        assertKept(app, new BuiltinPackComposer().merge(app, List.of()), "BuiltinPackComposer.merge");
    }

    @Test
    void packExtensionComposeKeepsExperiencesAndScreens() throws Exception {
        CompiledModel app = compile();
        PackExtensionComposer composer = new PackExtensionComposer();
        assertKept(app, composer.composeExtensions(app, Map.of(), List.of()), "composeExtensions");
        assertKept(app, composer.composeExtensionsWithOrdering(app, Map.<String, JsonNode>of(), List.of(), null).model(),
                "composeExtensionsWithOrdering");
    }

    @Test
    void facadeGenerationWithALinkedSealedPackStillWritesExperiencesJson() throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, MODEL);
        CompiledModel app = compile();
        CompiledModel merged = new BuiltinPackComposer().merge(app, List.of());

        PackAbiManifest manifest = new PackAbiManifest("identity", "1.0.0", 1, KernelAbi.CURRENT_ABI_VERSION);
        Path out = tempDir.resolve("out");
        Path migrations = tempDir.resolve("migrations");
        Files.createDirectories(migrations);
        new GeneratorFacade(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .generate(merged, out, migrations, modelPath, List.of(new LinkedSealedPack("identity", manifest)));

        Path file = out.resolve("src/main/resources/npdev/experiences.json");
        assertTrue(Files.exists(file), "the facade path must emit experiences.json for a model that declares experiences");
        JsonNode resolved = MAPPER.readTree(Files.readString(file));
        assertEquals("agent", resolved.path("experiences").path(0).path("name").asText());
        assertEquals("/api/concepts/tickets", resolved.path("screens").path(0).path("dataUrl").asText());
        assertEquals("/api/x/flows/CloseTicket/execute",
                resolved.path("screens").path(0).path("actions").path(0).path("startUrl").asText());
        assertFalse(Files.readString(out.resolve("src/main/resources/npdev/compiled-model.json")).isBlank());
        assertTrue(Files.readString(out.resolve("src/main/resources/npdev/compiled-model.json")).contains("\"experiences\""),
                "compiled-model.json carries the experiences too");
    }
}
