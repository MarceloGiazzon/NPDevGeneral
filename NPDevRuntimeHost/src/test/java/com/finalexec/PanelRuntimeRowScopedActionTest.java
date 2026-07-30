package com.finalexec;

import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;
import com.npdev.generated.runtime.service.KernelFacade;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G2 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): a declared Panel action rendered once at the panel header
 * had no way to target a specific record's id, so Concluir/Cancelar-per-row (crossdocking.html's
 * shape) could not be expressed. This was RED: {@code executeAction} always invoked with the
 * caller's raw body, with no row re-read. {@code scope: "row"} + {@code dataSource} now re-reads
 * the target row fresh and layers the caller's body on top, exactly like the hand-written screen's
 * {@code {...xd, situacao: 'Concluido'}}.
 */
class PanelRuntimeRowScopedActionTest {
    private final RuntimeMetadataService metadataService = new RuntimeMetadataService(new ObjectMapper());

    @Test
    void rowScopedActionMergesFreshRowDataWithCallerOverrides() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop()
        );
        ExecutionContext ctx = ExecutionContext.of("trial", "admin");
        gateway.save(new ConceptWriteRequest(
                "Widget", "widget-1", null, Map.of("qty", 9, "situacao", "Ativo")
        ), ctx);

        KernelFacade kernelFacade = mock(KernelFacade.class);
        when(kernelFacade.executeFlow(anyString(), anyMap(), any()))
                .thenReturn(ExecutionResult.ok("AdvanceWidget", Map.of("id", "widget-1"), List.of(), "exec-2", "corr-2", null));

        PanelRuntime runtime = new PanelRuntime(
                metadataService,
                null,
                rowScopedPanelModel(),
                gateway,
                null,
                null,
                null,
                kernelFacade
        );

        Map<String, Object> response = runtime.executeAction(
                "WidgetPanel",
                "advance",
                Map.of("id", "widget-1", "situacao", "Concluido"),
                ctx
        );

        assertEquals("OK", response.get("status"));
        org.mockito.ArgumentCaptor<Map> inputCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(kernelFacade).executeFlow(anyString(), inputCaptor.capture(), any());
        Map<?, ?> capturedInput = inputCaptor.getValue();
        assertEquals("widget-1", capturedInput.get("id"));
        assertEquals(9, capturedInput.get("qty"));
        assertEquals("Concluido", capturedInput.get("situacao"));
    }

    @Test
    void rowScopedActionRejectsMissingId() {
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop()
        );
        KernelFacade kernelFacade = mock(KernelFacade.class);
        PanelRuntime runtime = new PanelRuntime(
                metadataService,
                null,
                rowScopedPanelModel(),
                gateway,
                null,
                null,
                null,
                kernelFacade
        );

        assertThrows(IllegalArgumentException.class, () -> runtime.executeAction(
                "WidgetPanel", "advance", Map.of(), ExecutionContext.of("trial", "admin")
        ));
    }

    /**
     * G4 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): found live while authoring InventarioHistoricoPanel /
     * ConferenciaFiscal*Panel's Cancelar/Confirmar actions. A real row button only ever sends
     * {@code {id}}; before this fix, {@code executeConceptMutation} treated a "data"-less body as
     * the WHOLE record to save, so a scope="row" conceptMutation action would blank every other
     * required field to null (reproduced live: "Required concept field is missing:
     * InventarioArquivo.entidadeId"), and even after that fix, a SECOND bug surfaced (reproduced
     * live: "Required concept field is missing: InventarioArquivo.id") because the fallback branch
     * stripped "id" out of the data map while {@link ConfiguredConceptGatewaySemanticPolicy}
     * validates "id" as a required field of the data map itself. This test uses a REAL
     * {@code ConfiguredConceptGatewaySemanticPolicy} (not {@code .noop()}) specifically because the
     * first version of this test used the noop policy and passed despite both bugs still being
     * present -- a RED-proof that doesn't match production shape proves nothing
     * (feedback_red_proof_must_match_production_shape).
     */
    @Test
    void rowScopedConceptMutationMergesFreshRowDataInsteadOfBlankingFields() {
        var widgetConcept = com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition.of(
                "Widget",
                List.of(
                        new com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                "id", true, List.of(), null, null, null),
                        new com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                "qty", true, List.of(), null, null, null),
                        new com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                                "situacao", true, List.of(), null, null, null)
                ),
                List.of(),
                null
        );
        DefaultConceptGateway gateway = new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                new com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy(List.of(widgetConcept)),
                com.npdev.kernel.concepts.ConceptGatewayTraceSink.noop()
        );
        ExecutionContext ctx = ExecutionContext.of("trial", "admin");
        gateway.save(new ConceptWriteRequest(
                "Widget", "widget-2", null, Map.of("id", "widget-2", "qty", 4, "situacao", "Ativo")
        ), ctx);

        PanelRuntime runtime = new PanelRuntime(
                metadataService,
                null,
                rowScopedConceptMutationPanelModel(),
                gateway,
                null,
                null
        );

        Map<String, Object> response = runtime.executeAction(
                "WidgetPanel", "cancel", Map.of("id", "widget-2", "situacao", "Cancelado"), ctx
        );

        assertEquals("OK", response.get("status"));
        var saved = gateway.read(new com.npdev.kernel.concepts.ConceptReadRequest("Widget", "widget-2", null), ctx)
                .orElseThrow();
        assertEquals(4, saved.data().get("qty"));
        assertEquals("Cancelado", saved.data().get("situacao"));
    }

    private static CompiledModel rowScopedConceptMutationPanelModel() {
        CompiledPanel panel = new CompiledPanel(
                "WidgetPanel",
                "/widgets",
                "Widgets",
                List.of(new CompiledPanelDataSource("widgets", "Widget", null, null, Map.of(), null, null, null)),
                new CompiledPanelLayout("table", List.of(), List.of("situacao"), Map.of()),
                List.of(),
                null,
                null,
                List.of(new CompiledPanelAction(
                        "cancel",
                        "Cancel",
                        "conceptMutation",
                        "Widget",
                        "update",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        Map.of(),
                        Map.of(),
                        "row",
                        "widgets",
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
                "panel.runtime.rowscope.conceptmutation",
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

    private static CompiledModel rowScopedPanelModel() {
        CompiledPanel panel = new CompiledPanel(
                "WidgetPanel",
                "/widgets",
                "Widgets",
                List.of(new CompiledPanelDataSource("widgets", "Widget", null, null, Map.of(), null, null, null)),
                new CompiledPanelLayout("table", List.of(), List.of("situacao"), Map.of()),
                List.of(),
                null,
                null,
                List.of(new CompiledPanelAction(
                        "advance",
                        "Advance",
                        "flow",
                        null,
                        null,
                        null,
                        "AdvanceWidget",
                        null,
                        null,
                        List.of(),
                        Map.of(),
                        Map.of(),
                        "row",
                        "widgets",
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
                "panel.runtime.rowscope",
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
