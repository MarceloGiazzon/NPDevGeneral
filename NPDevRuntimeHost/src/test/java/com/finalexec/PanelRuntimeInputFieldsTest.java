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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * G3 (docs/MOVE2_PANEL_ACTIONS_PLAN.md): before this, a panel-scoped (non-row) action had no
 * declared input fields at all -- {@code executeDeclaredPanelAction} always invoked with an empty
 * body, so a create-style action like "Ativar" (crossdocking.html's 5-field form) had nothing to
 * bind to. This was RED: {@code loadPanel}'s actions catalog never carried field names for a caller
 * (generated UI or an AI-authored screen) to know what to collect. {@code inputFields} closes it.
 */
class PanelRuntimeInputFieldsTest {
    private final RuntimeMetadataService metadataService = new RuntimeMetadataService(new ObjectMapper());

    @Test
    void loadPanelActionsCatalogCarriesDeclaredInputFields() {
        PanelRuntime runtime = new PanelRuntime(metadataService, null, inputFieldsPanelModel(), null, null, null);

        Map<String, Object> panel = runtime.loadPanel("CrossDockingConsolePanel", Map.of(), ExecutionContext.anonymous());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) panel.get("actions");
        Map<String, Object> ativar = actions.stream()
                .filter(a -> "ativar".equals(a.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals(
                List.of("recebimentoId", "expedicaoId", "produtoId", "quantidade", "dataAtivacao"),
                ativar.get("inputFields")
        );
    }

    private static CompiledModel inputFieldsPanelModel() {
        CompiledPanel panel = new CompiledPanel(
                "CrossDockingConsolePanel",
                "/crossdocking",
                "Cross-docking",
                List.of(new CompiledPanelDataSource("crossDockings", "CrossDocking", null, null, Map.of(), null, null, null)),
                new CompiledPanelLayout("table", List.of(), List.of("situacao"), Map.of()),
                List.of(),
                null,
                null,
                List.of(new CompiledPanelAction(
                        "ativar",
                        "Ativar Cross-Docking",
                        "flow",
                        null,
                        null,
                        null,
                        "AtivarCrossDocking",
                        null,
                        null,
                        List.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        null,
                        List.of("recebimentoId", "expedicaoId", "produtoId", "quantidade", "dataAtivacao"),
                        null,
                        null,
                        null
                )),
                Map.of(),
                Map.of(),
                null
        );
        return new CompiledModel(
                "panel.runtime.inputfields",
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
