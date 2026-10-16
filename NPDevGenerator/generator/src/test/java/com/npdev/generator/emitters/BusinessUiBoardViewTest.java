package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UI-3: manifest board block + the board.js pure helpers (node, skipped when node is absent). */
public class BusinessUiBoardViewTest {

    // Mirrors NPDevSamples/pigmentampas Cap (model.json:240-272): enum status + lifecycle with
    // two guarded transitions, plus an enum-only concept grouped via ui.defaultGroup.
    private static final String MODEL = """
            {
              "namespace": "board.demo", "dslVersion": "1.0.0", "version": "1.0",
              "concepts": [
                { "name": "Cap",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "label", "type": "string", "required": true },
                    { "name": "status", "type": "enum", "required": true, "enumValues": ["SUBMITTED", "APPROVED", "REJECTED"] }
                  ],
                  "lifecycle": { "statusField": "status",
                    "states": [ { "value": "SUBMITTED", "label": "Submitted", "initial": true },
                                { "value": "APPROVED", "label": "Approved" },
                                { "value": "REJECTED", "label": "Rejected", "terminal": true } ],
                    "transitions": [ { "from": "SUBMITTED", "to": "APPROVED", "actionLabel": "Approve" },
                                     { "from": "SUBMITTED", "to": "REJECTED", "actionLabel": "Reject" } ] } },
                { "name": "Ticket", "ui": { "defaultGroup": "priority" },
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "title", "type": "string", "required": true },
                    { "name": "priority", "type": "enum", "enumValues": ["LOW", "HIGH"] }
                  ] },
                { "name": "Note",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                              { "name": "body", "type": "string" } ] }
              ]
            }
            """;

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-board-", ".json");
        Files.writeString(modelPath, MODEL);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-board-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    @Test
    void manifestCarriesBoardForLifecycleAndDefaultGroupConceptsOnly() throws Exception {
        Path out = emit();
        String manifest = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
        // Cap: lifecycle-restricted board; Ticket: free board on priority; Note: no board.
        assertTrue(manifest.contains("\"groupBy\" : \"status\""), manifest);
        assertTrue(manifest.contains("\"restricted\" : true"), manifest);
        assertTrue(manifest.contains("\"groupBy\" : \"priority\""), manifest);
        assertTrue(manifest.contains("\"label\" : \"Approve\""), manifest);
        // exactly two concepts list "board" in views
        assertEquals(2, manifest.split("\"board\" ]", -1).length - 1, manifest);
        assertTrue(Files.exists(out.resolve("src/main/resources/static/npdev-business-ui/views/board.js")));
        String index = Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/index.html"));
        assertTrue(index.indexOf("./views/board.js") > index.indexOf("./views/registry.js"));
        assertTrue(index.indexOf("./views/board.js") < index.indexOf("./app.js"));
    }

    @Test
    void boardHelpersGroupAndRestrictMoves() throws Exception {
        Assumptions.assumeTrue(nodeOnPath(), "node not on PATH");
        Path out = emit();
        Path views = out.resolve("src/main/resources/static/npdev-business-ui/views");
        Path script = Files.createTempFile("board-check-", ".js");
        Files.writeString(script, """
                const fs = require("fs"), vm = require("vm"), assert = require("assert");
                globalThis.window = globalThis; globalThis.document = { querySelectorAll: () => [] };
                vm.runInThisContext(fs.readFileSync(process.argv[2] + "/registry.js", "utf8"));
                vm.runInThisContext(fs.readFileSync(process.argv[2] + "/board.js", "utf8"));
                const b = window.NPDevViews.get("board");
                const board = { groupBy: "status", restricted: true,
                  columns: [ { value: "SUBMITTED" }, { value: "APPROVED" }, { value: "REJECTED" } ],
                  transitions: [ { from: "SUBMITTED", to: "APPROVED", label: "Approve" },
                                 { from: "SUBMITTED", to: "REJECTED", label: "Reject" } ] };
                const g = b._groupRecords([ { status: "SUBMITTED" }, { status: "APPROVED" }, { status: null } ], board);
                assert.strictEqual(g.SUBMITTED.length, 1); assert.strictEqual(g.__none__.length, 1);
                assert.deepStrictEqual(b._allowedTargets(board, "SUBMITTED").map(t => t.to), ["APPROVED", "REJECTED"]);
                assert.deepStrictEqual(b._allowedTargets(board, "APPROVED"), []);
                assert.strictEqual(b._allowedTargets(Object.assign({}, board, { restricted: false }), "APPROVED").length, 2);
                console.log("ok");
                """, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder("node", script.toString(), views.toString()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), output);
        assertFalse(output.contains("AssertionError"), output);
    }

    private static boolean nodeOnPath() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
