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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6-5: the browser expression engine ({@code npdev-expr.js}) is emitted, the aggregate workbench page loads it
 * before its own script, and the page wires live per-row computed columns, the live derived banner and the
 * F6-4 sub-band region. The emitted scripts must also be syntactically valid JavaScript.
 */
public class BusinessUiEmitterExprEngineTest {

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-expr-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "expr.demo", "dslVersion": "1.0.0", "version": "1.0",
                  "concepts": [
                    { "name": "Ord", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "discount", "type": "integer" } ] },
                    { "name": "Line", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "ordId", "type": "uuid" }, { "name": "qty", "type": "integer" } ] }
                  ],
                  "aggregates": [
                    { "name": "Ord", "root": "Ord", "collections": [
                        { "name": "lines", "concept": "Line", "childField": "ordId", "ownership": "owned" } ] }
                  ],
                  "autoPanels": [ { "aggregate": "Ord", "transaction": {
                      "computed": [ { "col": "double", "expr": "qty * 2", "collection": "lines" } ] } } ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-expr-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    private static boolean nodeOnPath() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String run(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "node timed out");
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    @Test
    void engineIsEmittedAndTheWorkbenchPageLoadsItBeforeItsOwnScript() throws Exception {
        Path out = emit();
        Path engine = out.resolve("src/main/resources/static/npdev-expr.js");
        assertTrue(Files.exists(engine), "static/npdev-expr.js must be emitted");
        assertTrue(Files.readString(engine).contains("npdev.expr"));

        String page = Files.readString(out.resolve("src/main/resources/static/npdev-workbench/OrdWorkbench.html"));
        assertTrue(page.contains("/npdev-expr.js"), "the workbench page must load the engine");
        assertTrue(page.indexOf("/npdev-expr.js") < page.indexOf("function createStore"),
                "the engine must load before the page script that uses it");
        assertTrue(page.contains("computedAt"), "page evaluates live per-row computed columns");
        assertTrue(page.contains("updateLiveComputed"), "page refreshes computed cells and the derived banner on every keystroke");
        assertTrue(page.contains("DERIVED_FUNCTIONS"), "page resolves sum/count/avg/min/max in derived expressions");
        assertTrue(page.contains("renderSubBandRegion"), "page renders the third nesting level");
    }

    @Test
    void emittedScriptsParseAndTheEngineEvaluates() throws Exception {
        Assumptions.assumeTrue(nodeOnPath(), "node not on PATH");
        Path out = emit();
        Path engine = out.resolve("src/main/resources/static/npdev-expr.js");
        run(out, "node", "--check", engine.toString());
        String result = run(out, "node", "-e",
                "const e=require(process.argv[1]); console.log(JSON.stringify([e.evaluate('qty * 2 + $root.d',{qty:3,'$root':{d:1}}), e.evaluate('a == null',{})]))",
                engine.toString());
        assertEquals("[7,true]", result.trim());
    }
}
