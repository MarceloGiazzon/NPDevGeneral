package com.finalexec;

import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledActionMetadata;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WMS-15 falsifiable guard: a binding:"flow" panel action inherits its client-side confirmation
 * gate from the INVOKED FLOW's own declared {@code action.confirmationText} -- read back out by
 * {@code PanelRuntime.panelActions()}'s {@code flowConfirmationText} helper and surfaced on the
 * live panel action item so the generated UI's {@code window.confirm(...)} gate (business-ui-app
 * .mustache) has something to read. Closed the ledger's own "flow.action.confirmationText was
 * parsed/compiled/round-tripped for years but nothing ever consumed it" gap; this test is what
 * makes a regression (the field silently stops being read, or the flow lookup breaks) fail loudly
 * instead of only being caught by clicking through a real generated app.
 */
class PanelRuntimeFlowConfirmationTextTest {
    private final RuntimeMetadataService metadataService = new RuntimeMetadataService(new ObjectMapper());

    @Test
    void flowBoundActionSurfacesItsInvokedFlowsConfirmationText() {
        PanelRuntime runtime = new PanelRuntime(metadataService, null, confirmationTextPanelModel(), null, null, null);

        Map<String, Object> panel = runtime.loadPanel("ExpedicaoDemandaPanel", Map.of(), ExecutionContext.anonymous());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) panel.get("actions");
        Map<String, Object> confirmar = actions.stream()
                .filter(a -> "confirmarSaida".equals(a.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals("Confirm exit for the listed items?", confirmar.get("confirmationText"));
    }

    @Test
    void flowBoundActionWithNoDeclaredConfirmationTextCarriesNoneAtAll() {
        PanelRuntime runtime = new PanelRuntime(metadataService, null, confirmationTextPanelModel(), null, null, null);

        Map<String, Object> panel = runtime.loadPanel("ExpedicaoDemandaPanel", Map.of(), ExecutionContext.anonymous());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) panel.get("actions");
        Map<String, Object> silent = actions.stream()
                .filter(a -> "silentAction".equals(a.get("name")))
                .findFirst()
                .orElseThrow();

        assertFalse(silent.containsKey("confirmationText"),
                "no confirmationText key at all when the invoked flow declares none -- the generated"
                        + " UI's window.confirm(...) gate must stay off, not fire with 'undefined'");
        assertNull(silent.get("confirmationText"));
    }

    private static CompiledModel confirmationTextPanelModel() {
        CompiledPanel panel = new CompiledPanel(
                "ExpedicaoDemandaPanel",
                "/expedicao-demanda",
                "Expedicao Demanda",
                List.of(new CompiledPanelDataSource("demandas", "ExpedicaoDemanda", null, null, Map.of(), null, null, null)),
                new CompiledPanelLayout("table", List.of(), List.of("situacao"), Map.of()),
                List.of(),
                null,
                null,
                List.of(
                        new CompiledPanelAction(
                                "confirmarSaida",
                                "Confirmar Saida",
                                "flow",
                                null,
                                null,
                                null,
                                "ConfirmarSaidaExpedicao",
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
                        ),
                        new CompiledPanelAction(
                                "silentAction",
                                "Silent Action",
                                "flow",
                                null,
                                null,
                                null,
                                "SilentFlow",
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
                        )
                ),
                Map.of(),
                Map.of(),
                null
        );
        CompiledFlow confirmedFlow = new CompiledFlow(
                "ConfirmarSaidaExpedicao",
                "ExpedicaoDemanda",
                "update",
                List.of(),
                null,
                null,
                new CompiledActionMetadata(
                        "Confirmar Saida",
                        "Confirm exit for the listed items?",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );
        CompiledFlow silentFlow = new CompiledFlow(
                "SilentFlow",
                "ExpedicaoDemanda",
                "update",
                List.of()
        );
        return new CompiledModel(
                "panel.runtime.confirmationtext",
                "1.0.0",
                "1.0.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(confirmedFlow, silentFlow),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(panel)
        );
    }
}
