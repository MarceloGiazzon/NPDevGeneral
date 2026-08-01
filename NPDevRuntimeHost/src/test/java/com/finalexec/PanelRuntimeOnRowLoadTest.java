package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.ProcedureRunner;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 6 Move C (docs/MOVE6_TYPED_SURFACE_PLAN.md §4): {@code panelDataSource.onRowLoad} enriches
 * the rows a data source produced. The batch guarantee -- same order, same count, same id at every
 * index -- is enforced by {@code PanelRuntime.applyRowLoad} IN CODE, not review comments: a
 * violation is a hard failure, never a silent truncation/reorder (an onRowLoad that quietly became
 * N+1 or dropped a row would discredit the whole mechanism).
 *
 * <p>Uses a stubbed {@link ProcedureRunner} that returns a fixed result regardless of input, so
 * these tests target {@code applyRowLoad}'s own enforcement logic directly, independent of the
 * real DSL step engine (already covered by every other procedure-step test in this codebase).
 */
class PanelRuntimeOnRowLoadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "wms.onrowload", "version": "1.0",
          "concepts": [
            { "name": "Widget", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "label", "type": "string" } ] }
          ],
          "panels": [
            { "name": "WidgetsPanel", "route": "/widgets",
              "dataSources": [ { "name": "widgets", "concept": "Widget", "onRowLoad": "EnrichWidgetsProcedure" } ] }
          ]
        }
        """;

    private static final class StubProcedureRunner extends ProcedureRunner {
        private final ProcedureExecutionResult stubbed;

        StubProcedureRunner(ProcedureExecutionResult stubbed) {
            super((CompiledModel) null, null, null, null);
            this.stubbed = stubbed;
        }

        @Override
        public ProcedureExecutionResult execute(String procedureName, Map<String, Object> input, ExecutionContext context) {
            return stubbed;
        }
    }

    private static CompiledModel model() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        return new ModelCompiler().compile(ast);
    }

    private static PanelRuntime runtimeWith(ProcedureExecutionResult stubbedResult) throws Exception {
        DefaultConceptGateway gateway = new DefaultConceptGateway(new InMemoryConceptStore());
        ExecutionContext ctx = ExecutionContext.of("trial", "tester");
        gateway.save(new ConceptWriteRequest("Widget", "w1", "trial", Map.of("id", "w1", "label", "Widget One")), ctx);
        gateway.save(new ConceptWriteRequest("Widget", "w2", "trial", Map.of("id", "w2", "label", "Widget Two")), ctx);
        return new PanelRuntime(
                new RuntimeMetadataService(MAPPER), null, model(), gateway,
                new StubProcedureRunner(stubbedResult));
    }

    private static Map<String, Object> row(String tenantId, String concept, String id, Map<String, Object> data, Object... extra) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", tenantId);
        map.put("concept", concept);
        map.put("id", id);
        map.put("data", data);
        for (int i = 0; i < extra.length; i += 2) {
            map.put((String) extra[i], extra[i + 1]);
        }
        return map;
    }

    @Test
    void enrichesRowsPreservingOrderAndIdentity() throws Exception {
        List<Map<String, Object>> enriched = List.of(
                row("trial", "Widget", "w1", Map.of("id", "w1", "label", "Widget One"), "flagged", true),
                row("trial", "Widget", "w2", Map.of("id", "w2", "label", "Widget Two"), "flagged", false)
        );
        PanelRuntime runtime = runtimeWith(ProcedureExecutionResult.success(Map.of("rows", enriched), List.of()));

        Map<String, Object> loaded = runtime.loadPanel("WidgetsPanel", Map.of(), ExecutionContext.of("trial", "tester"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) loaded.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> widgets = (List<Map<String, Object>>) data.get("widgets");

        assertEquals(2, widgets.size());
        assertEquals("w1", widgets.get(0).get("id"));
        assertEquals(Boolean.TRUE, widgets.get(0).get("flagged"), "onRowLoad's enrichment must survive into the response");
        assertEquals("w2", widgets.get(1).get("id"));
        assertEquals(Boolean.FALSE, widgets.get(1).get("flagged"));
    }

    @Test
    void countMismatchIsAHardFailure() throws Exception {
        List<Map<String, Object>> tooFew = List.of(row("trial", "Widget", "w1", Map.of()));
        PanelRuntime runtime = runtimeWith(ProcedureExecutionResult.success(Map.of("rows", tooFew), List.of()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.loadPanel("WidgetsPanel", Map.of(), ExecutionContext.of("trial", "tester")));
        assertTrue(ex.getMessage().contains("row-identity guarantee"), ex.getMessage());
        assertTrue(ex.getMessage().contains("expected 2"), ex.getMessage());
    }

    @Test
    void idMismatchAtAnIndexIsAHardFailure() throws Exception {
        List<Map<String, Object>> reordered = List.of(
                row("trial", "Widget", "w2", Map.of()),
                row("trial", "Widget", "w1", Map.of())
        );
        PanelRuntime runtime = runtimeWith(ProcedureExecutionResult.success(Map.of("rows", reordered), List.of()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.loadPanel("WidgetsPanel", Map.of(), ExecutionContext.of("trial", "tester")));
        assertTrue(ex.getMessage().contains("row-identity guarantee"), ex.getMessage());
        assertTrue(ex.getMessage().contains("row[0]"), ex.getMessage());
    }

    @Test
    void missingRowsKeyIsAHardFailure() throws Exception {
        PanelRuntime runtime = runtimeWith(ProcedureExecutionResult.success(Map.of("rows", "not-a-list"), List.of()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.loadPanel("WidgetsPanel", Map.of(), ExecutionContext.of("trial", "tester")));
        assertTrue(ex.getMessage().contains("must return a \"rows\" list"), ex.getMessage());
    }

    @Test
    void procedureFailureIsAHardFailure() throws Exception {
        PanelRuntime runtime = runtimeWith(
                ProcedureExecutionResult.failure(Map.of(), List.of(), "ENRICH_FAILED", "boom"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> runtime.loadPanel("WidgetsPanel", Map.of(), ExecutionContext.of("trial", "tester")));
        assertTrue(ex.getMessage().contains("ENRICH_FAILED"), ex.getMessage());
    }
}
