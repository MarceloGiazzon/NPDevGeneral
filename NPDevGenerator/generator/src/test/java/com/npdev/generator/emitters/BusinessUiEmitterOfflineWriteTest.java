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

    /**
     * Runs the EMITTED offline cache + write-queue code (the block of shell.js between OFFLINE_PREFIX and
     * TOKEN_STORAGE_KEYS) in Node against a fake localStorage, so the queue's behaviour is proven on the
     * code every generated app actually ships, not only that it parses.
     */
    @Test
    void theEmittedQueueIsPerUserOrderedAndWipedOnSignOut() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH");
        String shell = Files.readString(find(emit(), "shell.js"));
        int from = shell.indexOf("var OFFLINE_PREFIX");
        int to = shell.indexOf("var TOKEN_STORAGE_KEYS");
        assertTrue(from > 0 && to > from, "the offline block must be contiguous and findable");

        String script = """
                var store = {};
                var window = { localStorage: {
                  getItem: function (k) { return Object.prototype.hasOwnProperty.call(store, k) ? store[k] : null; },
                  setItem: function (k, v) { store[k] = String(v); },
                  removeItem: function (k) { delete store[k]; },
                  get length() { return Object.keys(store).length; },
                  key: function (i) { return Object.keys(store)[i]; } } };
                %s
                var out = {};
                out.noScopeAdd = queueAdd({ concept: "Visit", method: "POST", url: "/api/visits", body: {} });
                offlineSetPrincipal({ tenantId: "t1", actorId: "ana" });
                var first = queueAdd({ concept: "Visit", method: "PUT", url: "/api/visits/1", body: { note: "a", version: 3 }, baseVersion: 3, summary: "Update Visit" });
                var second = queueAdd({ concept: "Visit", method: "POST", url: "/api/visits", body: { note: "b" }, summary: "New Visit" });
                queueAdd({ concept: "Other", method: "POST", url: "/api/others", body: {} });
                out.order = queueList("Visit").map(function (i) { return i.summary; });
                out.allCount = queueList().length;
                out.pending = queuePendingCount();
                out.firstVersion = first.baseVersion;
                queueUpdate(first.id, { status: "rejected", message: "stale" });
                out.pendingAfterReject = queuePendingCount();
                out.rejected = queueList("Visit").filter(function (i) { return i.status === "rejected"; }).map(function (i) { return i.message; });
                queueRemove(second.id);
                out.afterRemove = queueList("Visit").length;
                offlineSetPrincipal({ tenantId: "t1", actorId: "bruno" });
                out.otherUserSeesQueue = queueList().length;
                offlineSetPrincipal({ tenantId: "t1", actorId: "ana" });
                queueAdd({ concept: "Visit", method: "POST", url: "/api/visits", body: {}, summary: "again" });
                out.beforeSignOut = queueList().length;
                offlineClearAll();
                offlineSetPrincipal({ tenantId: "t1", actorId: "ana" });
                out.afterSignOut = queueList().length;
                out.leftoverKeys = Object.keys(store).filter(function (k) { return k.indexOf("q:") >= 0; }).length;
                console.log(JSON.stringify(out));
                """.formatted(shell.substring(from, to));
        Path file = Files.createTempFile("npdev-offline-queue-", ".js");
        Files.writeString(file, script);
        Process run = new ProcessBuilder("node", file.toString()).redirectErrorStream(true).start();
        String output = new String(run.getInputStream().readAllBytes()).trim();
        assertTrue(run.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, run.exitValue(), output);

        JsonNode result = new ObjectMapper().readTree(output.substring(output.lastIndexOf((char) 10) + 1));
        assertTrue(result.get("noScopeAdd").isNull(), "nothing is queued before a principal is known");
        assertEquals("Update Visit", result.get("order").get(0).asText());
        assertEquals("New Visit", result.get("order").get(1).asText());
        assertEquals(3, result.get("allCount").asInt(), "the queue is shared across concepts, listed per concept");
        assertEquals(3, result.get("pending").asInt());
        assertEquals(3, result.get("firstVersion").asInt(), "the version the edit started from is kept for the server to check");
        assertEquals(2, result.get("pendingAfterReject").asInt(), "a rejected entry stops counting as pending but is kept");
        assertEquals("stale", result.get("rejected").get(0).asText());
        assertEquals(1, result.get("afterRemove").asInt());
        assertEquals(0, result.get("otherUserSeesQueue").asInt(), "another user on the same device never sees or inherits the queue");
        assertEquals(1, result.get("beforeSignOut").asInt(), "switching principals already wiped the first user's queue");
        assertEquals(0, result.get("afterSignOut").asInt());
        assertEquals(0, result.get("leftoverKeys").asInt(), "sign-out leaves no queue key behind");
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
