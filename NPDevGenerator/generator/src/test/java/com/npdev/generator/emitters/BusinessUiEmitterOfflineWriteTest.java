package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * MOB-2: a concept that declares {@code ui.offlineWrite} is flagged in the UI manifest (and only that
 * one), the queue and its replay ship in the generated shell and app, and the scripts still parse.
 */
class BusinessUiEmitterOfflineWriteTest {

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-offline-write-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "offlinewrite.demo", "dslVersion": "1.0.0", "version": "1.0",
                  "concepts": [
                    { "name": "Visit", "ui": { "offlineWrite": true }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "note", "type": "string" } ] },
                    { "name": "Archive", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "title", "type": "string" } ] }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-offline-write-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    private static Path find(Path root, String fileName) throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().equals(fileName)).findFirst().orElseThrow();
        }
    }

    @Test
    void onlyTheDeclaringConceptIsFlaggedInTheManifest() throws Exception {
        Path out = emit();
        JsonNode manifest = new ObjectMapper().readTree(find(out, "generated-ui-manifest.json").toFile());
        JsonNode visit = null;
        JsonNode archive = null;
        for (JsonNode concept : manifest.get("concepts")) {
            if ("Visit".equals(concept.get("conceptName").asText())) {
                visit = concept;
            }
            if ("Archive".equals(concept.get("conceptName").asText())) {
                archive = concept;
            }
        }
        assertTrue(visit.path("offlineWrite").asBoolean(false), "the declaring concept carries offlineWrite: true");
        assertFalse(archive.has("offlineWrite"), "a concept that never declared it gets no key at all");
    }

    @Test
    void theQueueReplayAndPendingBannerShipAndTheScriptsStillParse() throws Exception {
        Path out = emit();
        String shell = Files.readString(find(out, "shell.js"));
        assertTrue(shell.contains("queueAdd") && shell.contains("queuePendingCount") && shell.contains("queueRemove"),
                "the shell exposes the per-user write queue");
        assertTrue(shell.contains("OFFLINE_PREFIX + \"q:\""),
                "the queue lives under the offline prefix, so sign-out clears it with the read cache");
        assertTrue(shell.contains("not synced yet") || shell.contains("have not synced yet"),
                "signing out with unsynced changes asks first");

        String app = CarvedUiModules.appJsWithModules(out);
        assertTrue(app.contains("function syncPendingWrites") && app.contains("function queueOfflineWrite"));
        assertTrue(app.contains("concept.offlineWrite"), "queueing is gated on the concept's own flag");
        assertTrue(app.contains("status: \"rejected\""), "a server refusal is kept as needs-attention, never dropped");

        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH");
        for (Path script : new Path[] {find(out, "shell.js")}) {
            Process check = new ProcessBuilder("node", "--check", script.toString()).redirectErrorStream(true).start();
            String output = new String(check.getInputStream().readAllBytes());
            assertTrue(check.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, check.exitValue(), output);
        }
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
