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
                  "namespace": "expr.demo", "dslVersion": "3.0.0", "version": "1.0",
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

    /** FORM P3-T2: the browser engine mirrors the server grammar additions (list literal, in / not in, ?:, ??);
     *  the same table is asserted on the Java side in ComputedExpressionGrammarTest. */
    @Test
    void engineEvaluatesTheListMembershipTernaryAndCoalesceGrammar() throws Exception {
        Assumptions.assumeTrue(nodeOnPath(), "node not on PATH");
        Path out = emit();
        Path engine = out.resolve("src/main/resources/static/npdev-expr.js");
        String script = """
                const e = require(process.argv[2]);
                const v = { status: 'ASSIGNED', distanceKm: 7, discount: null, tags: ['a', 'b'], qty: 3 };
                const cases = [
                  ["['A', 'B']", ["A", "B"]], ["[]", []], ["['A', qty]", ["A", 3]],
                  ["status in ['REQUESTED', 'ASSIGNED']", true], ["status in ['REQUESTED']", false],
                  ["status not in ['REQUESTED']", true], ["status not in ['ASSIGNED']", false],
                  ["qty in [1, 3 * 1]", true], ["'a' in tags", true], ["'z' in tags", false], ["status in discount", false],
                  ["status in ['ASSIGNED'] && distanceKm > 9", false], ["!(status in ['REQUESTED'])", true],
                  ["distanceKm > 5 ? 'far' : 'near'", "far"],
                  ["distanceKm > 9 ? 'a' : distanceKm > 6 ? 'b' : 'c'", "b"],
                  ["discount ?? 0", 0], ["qty ?? 0", 3], ["discount ?? false || true ? 'x' : 'y'", "x"]
                ];
                const bad = cases.filter(([x, want]) => JSON.stringify(e.evaluate(x, v)) !== JSON.stringify(want)).map(c => c[0]);
                for (const x of ["[1, 2", "a ? b", "status in", "a ?? "]) {
                  try { e.parse(x); bad.push("should throw: " + x); } catch (err) { }
                }
                console.log(JSON.stringify(bad));
                """;
        // a file, not `node -e`: a multi-line argument with double quotes does not survive Windows command-line quoting
        Path scriptFile = out.resolve("grammar-check.cjs");
        Files.writeString(scriptFile, script);
        assertEquals("[]", run(out, "node", scriptFile.toString(), engine.toString()).trim());
    }

    /** FORM P3-T2: decimal-exact results and strict mode in the browser engine; the Java side is
     *  ComputedExpressionDecimalStrictTest. */
    @Test
    void engineIsDecimalExactAndHasAStrictMode() throws Exception {
        Assumptions.assumeTrue(nodeOnPath(), "node not on PATH");
        Path out = emit();
        Path engine = out.resolve("src/main/resources/static/npdev-expr.js");
        String script = """
                const e = require(process.argv[2]);
                const v = { price: 19.99, qty: 3, nothing: null, text: 'abc' };
                const bad = [];
                const same = (x, want) => { if (e.evaluate(x, v) !== want) bad.push(x + " -> " + e.evaluate(x, v)); };
                same("0.1 + 0.2", 0.3); same("0.3 - 0.1", 0.2); same("price * qty", 59.97);
                same("0.1 + 0.2 == 0.3", true); same("10 / 4", 2.5); same("10 / 0", 0); same("nothing * 2", 0);
                const strict = { strict: true };
                for (const x of ["price * nothing", "nothing + 1", "-nothing", "text * 2", "(price + 1) / (qty - 3)", "5 % 0"]) {
                  try { e.evaluate(x, v, strict); bad.push("strict should throw: " + x); } catch (err) { }
                }
                try { e.evaluate("price * nothing", v, strict); } catch (err) {
                  if (!err.message.includes("null operand `nothing`") || !err.message.includes("`price * nothing`")) { bad.push(err.message); }
                }
                if (e.evaluate("(nothing ?? 0) + 3", v, strict) !== 3) { bad.push("strict coalesce"); }
                // the function table is generated from functions-stdlib.json and every js function has an implementation
                if (e.stdlib.table.length < 30) { bad.push("stdlib table not injected: " + e.stdlib.table.length); }
                if (e.stdlib.missing.length) { bad.push("missing js implementations: " + e.stdlib.missing.join(",")); }
                const fx = { functions: e.stdlib.functions };
                if (e.evaluate("round(price * qty, 1)", v, fx) !== 60) { bad.push("stdlib round"); }
                if (e.evaluate("uppercase('ab')", v, fx) !== "AB") { bad.push("stdlib alias"); }
                console.log(JSON.stringify(bad));
                """;
        Path scriptFile = out.resolve("decimal-strict-check.cjs");
        Files.writeString(scriptFile, script);
        assertEquals("[]", run(out, "node", scriptFile.toString(), engine.toString()).trim());
    }
}
