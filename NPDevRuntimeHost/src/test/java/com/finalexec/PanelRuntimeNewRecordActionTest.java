package com.finalexec;

import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Found live (2026-09-19, NPDEV_MEGA_ROADMAP.md Session 8/11 browser verification): clicking "New"
 * on an aggregate root's auto-synthesized Selection panel (AutoPanelExpander.newRecordAction,
 * binding conceptMutation / operation create, PANEL-scoped -- unlike PanelRuntimeRowScopedActionTest's
 * row-scoped cases) sends a genuinely empty body ({}). executeConceptMutation generated a fresh id
 * for the record but never put it into the `data` map it validates and saves -- id stayed a
 * constructor-only argument to ConceptWriteRequest, invisible to
 * ConfiguredConceptGatewaySemanticPolicy's required-field check, which only ever looks at `data`.
 * Reproduced live against WmsOffice's MovimentoSelection: "Required concept field is missing:
 * Movimento.id" on every click. This test uses a REAL ConfiguredConceptGatewaySemanticPolicy (not
 * .noop()), same discipline as PanelRuntimeRowScopedActionTest's G4 case, so a fix that satisfies a
 * noop policy but not a real one cannot pass this test by accident.
 */
class PanelRuntimeNewRecordActionTest {
    private final RuntimeMetadataService metadataService = new RuntimeMetadataService(new ObjectMapper());

    @Test
    void panelScopedNewActionCreatesARecordFromAnEmptyBody() {
        var widgetConcept = ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition.of(
                "Widget",
                List.of(
                        new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                "id", true, List.of(), null, null, null),
                        new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                "situacao", false, List.of(), null, null, null)
                ),
                List.of(),
                null
        );
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                new ConfiguredConceptGatewaySemanticPolicy(List.of(widgetConcept)),
                com.npdev.kernel.concepts.ConceptGatewayTraceSink.noop()
        );
        ExecutionContext ctx = ExecutionContext.of("trial", "admin");

        PanelRuntime runtime = new PanelRuntime(
                metadataService,
                null,
                panelScopedNewActionModel(),
                gateway,
                null,
                null
        );

        Map<String, Object> response = runtime.executeAction("WidgetSelection", "new", Map.of(), ctx);

        assertEquals("OK", response.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        String generatedId = String.valueOf(result.get("id"));
        assertFalse(generatedId.isBlank());

        var saved = gateway.read(new com.npdev.kernel.concepts.ConceptReadRequest("Widget", generatedId, null), ctx)
                .orElseThrow();
        assertEquals(generatedId, saved.data().get("id"));
    }

    private static CompiledModel panelScopedNewActionModel() {
        CompiledPanel panel = new CompiledPanel(
                "WidgetSelection",
                "/widgets",
                "Widget",
                List.of(new CompiledPanelDataSource("widgets", "Widget", null, null, Map.of(), null, null, null)),
                new CompiledPanelLayout("table", List.of(), List.of("situacao"), Map.of()),
                List.of(),
                null,
                null,
                List.of(new CompiledPanelAction(
                        "new",
                        "New",
                        "conceptMutation",
                        "Widget",
                        "create",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null
                )),
                Map.of(),
                Map.of(),
                null
        );
        return new CompiledModel(
                "panel.runtime.newaction",
                "1.0.0",
                "1.0.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(panel)
        );
    }
}
