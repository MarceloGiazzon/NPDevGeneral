package com.npdev.generator.emitters;

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
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PROC-3: the "My tasks" page ships verbatim with every app (model-free, deterministic); only the
 * manifest's {@code collaboration.humanTasks} flag -- which makes the shell show "Work > My tasks" --
 * depends on the model.
 */
public class BusinessUiEmitterCollabAssetsTest {

    private static final String WITHOUT_TASK = """
            {
              "namespace": "collab.demo", "dslVersion": "1.0.0", "version": "1.0",
              "concepts": [
                { "name": "Req", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "owner", "type": "string" } ] }
              ],
              "events": [ { "name": "Decided", "payload": [] } ],
              "flows": [
                { "name": "ApproveReq", "concept": "Req", "steps": [
                  { "name": "approve", "type": "awaitEvent", "awaitEvent": "Decided", "awaitRef": "d",
                    "match": { "correlation": true }%s },
                  { "name": "done", "type": "return", "value": "$input" } ] }
              ]
            }
            """;

    private static final String TASK = """
            , "task": { "title": "Approve", "assignee": { "user": "$input.owner" },
                        "outcomes": [ { "value": "OK", "label": "Ok" } ] }
            """;

    private static Path emit(String modelJson) throws Exception {
        Path modelPath = Files.createTempFile("npdev-collab-assets-", ".json");
        Files.writeString(modelPath, modelJson);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-collab-assets-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    private static JsonNode manifest(Path out) throws Exception {
        return new ObjectMapper().readTree(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json").toFile());
    }

    @Test
    void collabAssetsAreEmittedVerbatimForAnAppWithoutTasksAndTheManifestHasNoCollaborationKey() throws Exception {
        Path out = emit(WITHOUT_TASK.formatted(""));
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        for (String asset : BusinessUiEmitter.COLLAB_ASSETS) {
            Path emitted = out.resolve("src/main/resources/static/npdev-business-ui/collab/" + asset);
            assertTrue(Files.isRegularFile(emitted), asset + " must be emitted");
            assertEquals(templates.raw("static-assets/collab/" + asset), Files.readString(emitted),
                    asset + " must be copied verbatim");
        }
        assertFalse(manifest(out).has("collaboration"), "no task declared -> no collaboration key at all");
    }

    @Test
    void manifestCarriesTheHumanTasksFlagWhenAFlowDeclaresATask() throws Exception {
        Path out = emit(WITHOUT_TASK.formatted(TASK));
        JsonNode collaboration = manifest(out).get("collaboration");
        assertNotNull(collaboration, "a declared human task must surface in the manifest");
        assertTrue(collaboration.get("humanTasks").asBoolean());
    }

    @Test
    void shellShipsTheWorkSectionAndNoMustacheTagSurvives() throws Exception {
        Path out = emit(WITHOUT_TASK.formatted(TASK));
        String shellJs = Files.readString(out.resolve("src/main/resources/static/shell.js"));
        assertTrue(shellJs.contains("function renderWorkSection("));
        assertTrue(shellJs.contains("renderWorkSection(el.sidebar, state.manifest);"));
        assertTrue(shellJs.contains("/npdev-business-ui/collab/tasks.html"));
        assertFalse(shellJs.contains("{{"), "no unrendered Mustache tag in the emitted shell.js");
        String shellCss = Files.readString(out.resolve("src/main/resources/static/shell.css"));
        assertTrue(shellCss.contains(".npdev-shell-nav-badge"));
        assertFalse(shellCss.contains("{{"));
    }

    private static final String NOTIFY_MODEL = """
            {
              "namespace": "collab.notify", "dslVersion": "1.0.0", "version": "1.0",
              "concepts": [
                { "name": "Req", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "capabilities": [ { "name": "Notify", "type": "NotifyCapability", "operations": [ "send" ] } ],
              "bindings": [ { "capability": "Notify", "adapter": "notify" } ]
            }
            """;

    @Test
    void notificationsFlagAppearsOnlyWhenTheNotifyCapabilityIsBound() throws Exception {
        Path without = emit(WITHOUT_TASK.formatted(""));
        assertFalse(manifest(without).has("collaboration"), "no notify binding -> no collaboration key");

        Path withNotify = emit(NOTIFY_MODEL);
        JsonNode collaboration = manifest(withNotify).get("collaboration");
        assertNotNull(collaboration, "a notify binding must surface in the manifest");
        assertTrue(collaboration.get("notifications").asBoolean());
        assertFalse(collaboration.has("humanTasks"), "humanTasks stays absent without a task");
        assertTrue(Files.isRegularFile(withNotify.resolve("src/main/resources/static/npdev-business-ui/collab/bell.js")));
    }

    @Test
    void shellLoadsTheBellScriptAndBellGoesThroughTheShellApi() throws Exception {
        Path out = emit(NOTIFY_MODEL);
        String shellJs = Files.readString(out.resolve("src/main/resources/static/shell.js"));
        assertTrue(shellJs.contains("function loadCollabScript("));
        assertTrue(shellJs.contains("loadCollabScript(manifest);"));
        assertTrue(shellJs.contains("/npdev-business-ui/collab/bell.js"));
        assertFalse(shellJs.contains("{{"));
        String bellJs = new TemplateEngine("npdev-templates/").raw("static-assets/collab/bell.js");
        assertTrue(bellJs.contains("shell.api.get(\"/api/notifications/unread-count\")"));
        assertFalse(java.util.regex.Pattern.compile("\\bfetch\\(\\s*[\"'`a-zA-Z]").matcher(bellJs).find(), "raw fetch() would miss the auth and CSRF headers");
    }

    @Test
    void tasksPageGoesThroughTheShellApiNotRawFetch() throws Exception {
        String tasksJs = new TemplateEngine("npdev-templates/").raw("static-assets/collab/tasks.js");
        assertTrue(tasksJs.contains("shell.api.get(\"/api/tasks\")"));
        assertFalse(java.util.regex.Pattern.compile("\\bfetch\\(\\s*[\"'`a-zA-Z]").matcher(tasksJs).find(), "raw fetch() would miss the auth and CSRF headers");
    }
}
