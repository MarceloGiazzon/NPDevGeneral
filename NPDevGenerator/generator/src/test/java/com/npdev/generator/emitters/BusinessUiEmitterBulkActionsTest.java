package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7.8 (roadmap): bulk selection + bulk actions on the generic concept grid, backed by a batched
 * endpoint that reuses the same governed single-record write primitives (ConceptBinding.update()/
 * delete(), which already run checkCrudPermission / ConceptGateway / audit) rather than a
 * client-side loop or raw SQL. Asserted against the emitted assets, not the templates, matching this
 * package's existing convention (see BusinessUiEmitterEmptyStateXssTest) -- what matters is what
 * every generated app actually ships.
 */
public class BusinessUiEmitterBulkActionsTest {

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bulk-actions-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bulkactions.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Customer",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "displayName", "type": "string", "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["Active", "Inactive"] }
                      ]
                    }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-bulk-actions-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    private static String readAppJs(Path out) throws IOException {
        Path assets = out.resolve("src/main/resources/static/npdev-business-ui");
        try (var files = Files.walk(assets)) {
            Path appJs = files.filter(path -> path.getFileName().toString().endsWith(".js"))
                    .filter(path -> path.getFileName().toString().contains("app"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no app js emitted under " + assets));
            return Files.readString(appJs);
        }
    }

    private static String readCrudController(Path out) throws IOException {
        Path controller = out.resolve(
                "src/main/java/com/npdev/generated/controllers/GeneratedConceptCrudController.java");
        return Files.readString(controller);
    }

    @Test
    void gridShipsAPageScopedSelectAllCheckboxColumn() throws Exception {
        String appJs = readAppJs(emit());
        assertTrue(appJs.contains("function renderTable(concept, panel)"),
                "the base grid render function must still exist");
        assertTrue(appJs.contains("bulk-select-col"),
                "renderTable must emit the leading bulk-select checkbox column");
        assertTrue(appJs.contains("Select all rows on this page"),
                "a header select-all-on-page checkbox must be emitted");
        assertTrue(appJs.contains("function toggleRowSelected(panel, id, selected)"),
                "per-row selection state must be tracked on the panel");
    }

    @Test
    void bulkActionsBarOnlyAppearsThroughItsOwnGate() throws Exception {
        String appJs = readAppJs(emit());
        assertTrue(appJs.contains("function buildBulkActionsBar(concept, panel)"),
                "the bulk actions bar builder must be emitted");
        assertTrue(appJs.contains("if (!ids.length) { return null; }"),
                "the bar must render nothing while no row is selected");
        assertTrue(appJs.contains("Delete selected"), "a bulk delete action must be offered");
        assertTrue(appJs.contains("Set field…"), "a bulk set-field action must be offered");
    }

    @Test
    void bulkActionsGoThroughTheBatchedEndpointNotAClientSideLoop() throws Exception {
        String appJs = readAppJs(emit());
        assertTrue(appJs.contains("async function postConceptBatch(concept, operation, ids, extra)"),
                "one shared helper must post the whole selection in a single request");
        assertTrue(appJs.contains("concept.endpointBase + \"/batch\""),
                "the batch call must hit {endpointBase}/batch, the same base the single-record"
                        + " PUT/DELETE endpoints already use");
        // Never one fetch per selected id -- that would be the rejected client-side-loop design.
        assertTrue(appJs.indexOf("function runBulkDelete(concept, panel)") > 0);
    }

    @Test
    void confirmDialogAndPerRowResultsAreWired() throws Exception {
        String appJs = readAppJs(emit());
        assertTrue(appJs.contains("window.confirm(\"Delete \" + ids.length"),
                "bulk delete must be confirmed before it executes");
        assertTrue(appJs.contains("function showBatchResultsModal(concept, operationLabel, response)"),
                "a results view reporting per-row outcomes must be emitted");
        assertTrue(appJs.contains("row.ok ? \"OK\" : (row.code || \"Error\")"),
                "the results view must report each row's own outcome, not one aggregate boolean");
    }

    @Test
    void batchEndpointReusesTheGovernedSingleRecordPrimitivesNotRawSql() throws Exception {
        String controller = readCrudController(emit());
        assertTrue(controller.contains("@PostMapping(\"/{conceptName}/batch\")"),
                "the generic CRUD controller must expose the batched endpoint");
        assertTrue(controller.contains("binding.delete().test(parsedId)"),
                "batch delete must call the SAME ConceptBinding.delete() reference the single-record"
                        + " DELETE endpoint calls, not a second write path");
        assertTrue(controller.contains("binding.update().apply(parsedId, Collections.singletonMap(field, value))"),
                "batch setField must call the SAME ConceptBinding.update() reference the single-record"
                        + " PUT endpoint calls, not a second write path");
        assertTrue(controller.contains("catch (ResponseStatusException exception)")
                        && controller.contains("\"forbidden\""),
                "a per-row permission denial (checkCrudPermission's 403) must become one failed"
                        + " outcome, not abort the whole batch");
    }

    @Test
    void batchResponseReportsPerRowOutcomesNotASingleBoolean() throws Exception {
        String controller = readCrudController(emit());
        assertTrue(controller.contains("response.put(\"results\", results)"),
                "the response must carry a per-id outcome list");
        assertTrue(controller.contains("row.put(\"ok\", true)") && controller.contains("row.put(\"ok\", false)"),
                "each row outcome must be individually ok/failed");
    }
}
