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
 * {@code ui.selectionTools}: the open / copy / download tools are ON by default, so only a concept that opts out
 * ({@code false}) is flagged in the UI manifest, and the EMITTED bulk-actions.js / csv-io.js offer the tools to every
 * other concept.
 */
class BusinessUiEmitterSelectionToolsTest {

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-selection-tools-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "selectiontools.demo", "dslVersion": "1.0.0", "version": "1.0",
                  "concepts": [
                    { "name": "Visit", "ui": { "selectionTools": false }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "note", "type": "string" } ] },
                    { "name": "Archive", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "title", "type": "string" } ] }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-selection-tools-out-");
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
    void onlyTheConceptThatOptsOutIsFlaggedInTheManifest() throws Exception {
        JsonNode manifest = new ObjectMapper().readTree(find(emit(), "generated-ui-manifest.json").toFile());
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
        assertTrue(visit.has("selectionTools") && !visit.get("selectionTools").asBoolean(true),
                "the concept that opted out carries selectionTools: false");
        assertFalse(archive.has("selectionTools"), "a concept that never declared it gets no key at all (tools stay on)");
    }

    /** Runs the shipped modules against a fake DOM: the bar, the copied text and the merged file. */
    @Test
    void theEmittedBarOffersTheToolsOnlyWhereDeclaredAndCopiesAndDownloadsTheTickedRows() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not on PATH");
        Path out = emit();
        String csvIo = Files.readString(find(out, "csv-io.js"));
        String bulk = Files.readString(find(out, "bulk-actions.js"));

        String script = """
                var copied = null, downloaded = null, opened = null, notes = [], rendered = 0;
                var window = { isSecureContext: true };
                var navigator = { clipboard: { writeText: function (t) { copied = t; return Promise.resolve(); } } };
                var document = { createElement: function (tag) {
                  return { tag: tag, children: [], className: "", textContent: "", style: {},
                    appendChild: function (c) { this.children.push(c); return c; }, setAttribute: function () {} };
                } };
                var NPDevViews = { m: {}, registerModule: function (n, f) { this.m[n] = f; },
                  module: function (n, h) { return this.m[n](h); } };
                __CSV__
                __BULK__
                var csv = NPDevViews.module("csvIo", {});
                var bulk = NPDevViews.module("bulkActions", {
                  buildCsv: csv.buildCsv, csvFilenameFor: csv.csvFilenameFor, neutralizeCsvFormula: csv.neutralizeCsvFormula,
                  downloadCsv: function (text, name) { downloaded = { text: text, name: name }; },
                  button: function (label, cls, fn) { return { label: label, fn: fn }; },
                  notify: function (m, e) { notes.push([m, e]); },
                  openDetailsMany: function (c, recs) { opened = recs.map(function (r) { return r.id; }); },
                  render: function () { rendered += 1; }, setStatus: function () {},
                  visibleFields: function (c) { return c.fields; } });
                function labels(bar) { return bar.children.filter(function (c) { return c.label; }).map(function (c) { return c.label; }); }
                function click(bar, label) { return bar.children.filter(function (c) { return c.label === label; })[0].fn(); }
                var fields = [{ name: "id", label: "Id" }, { name: "note", label: "Note" }];
                var on = { idField: "id", conceptName: "Archive", displayName: "Archive", fields: fields };
                var off = { idField: "id", conceptName: "Visit", displayName: "Visit", fields: fields, selectionTools: false };
                var records = [{ id: "1", note: "a,b" }, { id: "2", note: "=cmd" }, { id: "3", note: "line\\nbreak\\ttab" }];
                (async function () {
                  var out = {};
                  var panel = { records: records, selectedIds: ["1", "3"] };
                  out.onLabels = labels(bulk.buildBulkActionsBar(on, panel));
                  out.offLabels = labels(bulk.buildBulkActionsBar(off, { records: records, selectedIds: ["1"] }));
                  out.emptyBar = bulk.buildBulkActionsBar(on, { records: records, selectedIds: [] });
                  var bar = bulk.buildBulkActionsBar(on, panel);
                  await click(bar, "Copy selected");
                  out.copied = copied;
                  click(bar, "Download selected");
                  out.downloaded = downloaded;
                  click(bar, "Open selected");
                  out.opened = opened;
                  click(bar, "Select all");
                  out.afterSelectAll = panel.selectedIds.slice();
                  out.allSelectedLabels = labels(bulk.buildBulkActionsBar(on, panel));
                  click(bar, "Deselect all");
                  out.afterDeselectAll = panel.selectedIds.slice();
                  var formula = { records: records, selectedIds: ["2"] };
                  await click(bulk.buildBulkActionsBar(on, formula), "Copy selected");
                  out.formulaCopied = copied;
                  console.log(JSON.stringify(out));
                })();
                """.replace("__CSV__", csvIo).replace("__BULK__", bulk);
        Path file = Files.createTempFile("npdev-selection-tools-", ".js");
        Files.writeString(file, script);
        Process run = new ProcessBuilder("node", file.toString()).redirectErrorStream(true).start();
        String output = new String(run.getInputStream().readAllBytes()).trim();
        assertTrue(run.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, run.exitValue(), output);

        JsonNode result = new ObjectMapper().readTree(output.substring(output.lastIndexOf((char) 10) + 1));
        String onLabels = result.get("onLabels").toString();
        for (String tool : new String[] { "Open selected", "Copy selected", "Download selected" }) {
            assertTrue(onLabels.contains(tool), tool + " is offered by default (no declaration): " + onLabels);
            assertFalse(result.get("offLabels").toString().contains(tool),
                    tool + " is NOT offered on a concept that opted out");
        }
        assertTrue(result.get("offLabels").toString().contains("Delete selected"),
                "the existing bulk actions are unaffected by the flag");
        assertTrue(result.get("emptyBar").isNull(), "no bar while nothing is ticked");

        assertEquals("Id\tNote\r\n1\ta,b\r\n3\t\"line\nbreak\ttab\"", result.get("copied").asText(),
                "copy is tab-separated with a header row, ticked rows only, in on-screen order");
        assertEquals("Archive-selected.csv", result.get("downloaded").get("name").asText());
        assertEquals("Id,Note\r\n1,\"a,b\"\r\n3,\"line\nbreak\ttab\"\r\n", result.get("downloaded").get("text").asText(),
                "download merges every ticked row into one CSV");
        assertEquals("[\"1\",\"3\"]", result.get("opened").toString(), "open selected gets exactly the ticked rows");

        assertEquals(3, result.get("afterSelectAll").size(), "select all ticks every row on the page");
        assertFalse(result.get("allSelectedLabels").toString().contains("Select all"),
                "Select all disappears once everything is ticked");
        assertEquals(0, result.get("afterDeselectAll").size(), "deselect all clears the selection");
        assertTrue(result.get("formulaCopied").asText().endsWith("2\t'=cmd"),
                "a formula-lead cell is neutralised on copy: " + result.get("formulaCopied").asText());
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
