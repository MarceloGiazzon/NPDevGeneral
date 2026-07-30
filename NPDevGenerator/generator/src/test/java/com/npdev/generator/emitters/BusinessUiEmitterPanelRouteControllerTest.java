package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 6): {@code panel.route} was fully threaded
 * through parsing/compiling/canonical-JSON/the manifest, but a live investigation confirmed
 * nothing ever registered it as a real, browser-navigable URL for an ordinary declared panel --
 * only the separate, opt-in "trusted-source" custom-HTML mechanism got a real
 * {@code @GetMapping}. Typing an ordinary declared panel's own {@code route} into a browser
 * resolved to nothing; the panel was reachable only via its left-nav link (keyed by name).
 * Proves {@code GeneratedBusinessUiRouteController} now emits one real redirect mapping per
 * declared panel route, landing on exactly what that panel's own nav link already goes to.
 */
public class BusinessUiEmitterPanelRouteControllerTest {

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-panel-route-", ".json");
        Files.writeString(modelPath, json);
        return new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
    }

    private static String emitAndReadController(CompiledModel model) throws Exception {
        Path out = Files.createTempDirectory("npdev-panel-route-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return Files.readString(out.resolve(
                "src/main/java/com/npdev/generated/controllers/GeneratedBusinessUiRouteController.java"));
    }

    @Test
    void ordinaryDeclaredPanelRouteRedirectsToItsOwnHashAddressedSection() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "panel.route.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Expense", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "amount", "type": "int" } ] }
                  ],
                  "panels": [
                    {
                      "name": "ExpensesApprovalPanel",
                      "route": "/expenses/approval",
                      "title": "Expenses Approval",
                      "dataSources": [ { "name": "expenses", "concept": "Expense" } ],
                      "layout": { "type": "table", "fields": ["expenses"] }
                    }
                  ]
                }
                """);

        String source = emitAndReadController(model);
        assertTrue(source.contains("@GetMapping(\"/npdev-business-ui\")"), source);
        assertTrue(source.contains("@GetMapping(\"/npdev-business-ui/\")"), source);
        assertTrue(source.contains("@GetMapping(\"/expenses/approval\")"),
                "expected a real @GetMapping for the declared panel's own route; got: " + source);
        assertTrue(source.contains("redirect:/npdev-business-ui/#concept-__panel-ExpensesApprovalPanel__"),
                "expected the route to redirect to the SAME hash section its own nav link opens; got: " + source);
        assertFalse(source.contains("{{"), "no unrendered mustache placeholders");
    }

    @Test
    void aggregateWorkbenchPanelRouteRedirectsToItsOwnServedPageNotTheGenericHashSection() throws Exception {
        CompiledModel model = compile("""
                {
                  "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
                  "concepts": [
                    { "name": "Expedicao", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "cliente", "type": "string" } ] },
                    { "name": "ExpedicaoItem", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "expedicaoId", "type": "uuid" } ] }
                  ],
                  "aggregates": [
                    { "name": "Expedicao", "root": "Expedicao",
                      "collections": [ { "name": "itens", "concept": "ExpedicaoItem", "childField": "expedicaoId", "ownership": "owned" } ] }
                  ],
                  "autoPanels": [ { "aggregate": "Expedicao" } ]
                }
                """);

        String source = emitAndReadController(model);
        // The workbench panel itself (dataVia: aggregate) -- redirects to its dedicated served page,
        // the same target its own nav link (manifest's workbenchUrl) already uses.
        assertTrue(source.contains("@GetMapping(\"/expedicao/{id}\")"),
                "expected a real @GetMapping for the workbench's own route; got: " + source);
        assertTrue(source.contains("redirect:/npdev-workbench/ExpedicaoWorkbench.html"),
                "expected the workbench route to redirect to its OWN served page, not a generic hash section; got: " + source);
        // The sibling root-list Selection panel is an ordinary declared panel (no dataVia) -- hash-addressed.
        assertTrue(source.contains("@GetMapping(\"/expedicao\")"),
                "expected a real @GetMapping for the selection panel's own route; got: " + source);
        assertTrue(source.contains("redirect:/npdev-business-ui/#concept-__panel-ExpedicaoSelection__"),
                "expected the selection panel's route to redirect to its own hash section; got: " + source);
    }

    @Test
    void trustedSourcePanelRouteIsExcludedToAvoidCollidingWithItsOwnGetMapping() throws Exception {
        CompiledPanel trustedPanel = new CompiledPanel(
                "user-admin-panel",
                "/users",
                "Users",
                List.of(),
                null,
                List.of(),
                "role:admin",
                "",
                List.of(),
                Map.of(),
                Map.of("trustedSourceEntrypoint", "panel/user-admin-panel.html"),
                null
        );
        CompiledModel model = new CompiledModel(
                "trusted.source.route.test", "1.0.0", "1.0",
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(trustedPanel));

        Path out = Files.createTempDirectory("npdev-panel-route-trusted-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        String source = Files.readString(out.resolve(
                "src/main/java/com/npdev/generated/controllers/GeneratedBusinessUiRouteController.java"));

        assertFalse(source.contains("@GetMapping(\"/users\")"),
                "a trustedSourceEntrypoint panel must NOT get a second @GetMapping here -- "
                        + "TrustedSourceControllerTemplate already emits its own, and Spring rejects a duplicate "
                        + "mapping for the same path at boot; got: " + source);
    }
}
