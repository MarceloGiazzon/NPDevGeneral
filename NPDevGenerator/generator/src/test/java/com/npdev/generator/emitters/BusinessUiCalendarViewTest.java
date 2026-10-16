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

/** UI-6: calendar view offer, range where-operators in the CRUD controller, calendar.js pure helpers. */
public class BusinessUiCalendarViewTest {

    private static final String STATIC = "src/main/resources/static/npdev-business-ui/";

    private static final String MODEL = """
            {
              "namespace": "calendar.demo", "dslVersion": "1.0.0", "version": "1.0",
              "concepts": [
                { "name": "Appointment",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "title", "type": "string", "required": true },
                    { "name": "startsAt", "type": "datetime" },
                    { "name": "dueOn", "type": "date" }
                  ] },
                { "name": "Note",
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                              { "name": "body", "type": "string" } ] }
              ]
            }
            """;

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-calendar-", ".json");
        Files.writeString(modelPath, MODEL);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-calendar-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    @Test
    void calendarIsOfferedOnlyForConceptsWithADateField() throws Exception {
        Path out = emit();
        String manifest = Files.readString(out.resolve(STATIC + "generated-ui-manifest.json"));
        assertEquals(1, manifest.split("\"table\", \"calendar\" ]", -1).length - 1, manifest);
        assertEquals(1, manifest.split("\"views\" : \\[ \"table\" ]", -1).length - 1, manifest);
        assertTrue(Files.exists(out.resolve(STATIC + "views/calendar.js")));
        String index = Files.readString(out.resolve(STATIC + "index.html"));
        assertTrue(index.indexOf("./views/calendar.js") > index.indexOf("./views/registry.js"));
        assertTrue(index.indexOf("./views/calendar.js") < index.indexOf("./app.js"));
    }

    @Test
    void crudControllerAcceptsRangeWhereOperators() throws Exception {
        String controller = Files.readString(emit().resolve(
                "src/main/java/com/npdev/generated/controllers/GeneratedConceptCrudController.java"));
        for (String op : new String[] {"gt", "gte", "lt", "lte"}) {
            assertTrue(controller.contains("case \"" + op + "\" ->"), "missing case for " + op);
        }
        assertTrue(controller.contains("List.of(\"eq\", \"ne\", \"contains\", \"gt\", \"gte\", \"lt\", \"lte\")"));
        assertTrue(controller.contains("Operator.GTE") && controller.contains("Operator.LTE"));
        assertTrue(controller.contains("RANGE_WHERE_OPERATORS.contains(parsed.operator())"),
                "range operators must be exempt from the enum-value guard");
    }

    @Test
    void calendarHelpersBuildTheMonthGridAndRangeClauses() throws Exception {
        Assumptions.assumeTrue(nodeOnPath(), "node not on PATH");
        Path out = emit();
        Path views = out.resolve(STATIC + "views");
        Path script = Files.createTempFile("calendar-check-", ".js");
        Files.writeString(script, """
                const fs = require("fs"), vm = require("vm"), assert = require("assert");
                globalThis.window = globalThis;
                vm.runInThisContext(fs.readFileSync(process.argv[2] + "/registry.js", "utf8"));
                vm.runInThisContext(fs.readFileSync(process.argv[2] + "/calendar.js", "utf8"));
                const c = window.NPDevViews.get("calendar");
                const grid = c._monthGrid(new Date(2026, 9, 15));
                assert.strictEqual(grid.length, 42);
                const iso = d => d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
                assert.strictEqual(iso(grid[0]), "2026-09-28");
                assert.strictEqual(grid[0].getDay(), 1);
                assert.deepStrictEqual(c._rangeClauses({ name: "startsAt", type: "datetime" }, grid),
                  ["startsAt:gte:2026-09-28T00:00:00", "startsAt:lte:2026-11-08T23:59:59"]);
                assert.deepStrictEqual(c._rangeClauses({ name: "dueOn", type: "date" }, grid),
                  ["dueOn:gte:2026-09-28", "dueOn:lte:2026-11-08"]);
                assert.strictEqual(c._dayKey("2026-10-15"), "2026-10-15");
                assert.strictEqual(c._dayKey(null), null);
                assert.strictEqual(c._dayKey("not a date"), null);
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
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
