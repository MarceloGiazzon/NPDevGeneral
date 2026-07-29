package com.finalexec;

import com.finalexec.npdev.service.BetaSecurityRoleEvaluator;
import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.PermissionAwareUiMetadataService;
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
import com.npdev.kernel.ports.PermissionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G1 (docs/MOVE1_PANEL_GAPS.md, REG-70): {@code panel.action.binding: "flow"} was schema-valid,
 * compiler-accepted, and unimplemented at runtime -- {@link PanelRuntime#executeAction} fell
 * through to {@code PANEL_ACTION_BINDING_UNSUPPORTED} for every flow-bound action. This was RED
 * (confirmed live against WmsOffice's {@code CrossDockingConsolePanel.ativar} and the two
 * already-shipping panels named in REG-70) before the {@code "flow"} branch was added.
 */
class PanelRuntimeFlowActionTest {
    private final RuntimeMetadataService metadataService = new RuntimeMetadataService(new ObjectMapper());

    @Test
    void executesFlowBoundActionThroughKernelFacadeAndReturnsExecutionReference() {
        KernelFacade kernelFacade = mock(KernelFacade.class);
        ExecutionResult okResult = ExecutionResult.ok(
                "AtivarCrossDocking",
                Map.of("id", "xd-1", "situacao", "Ativo"),
                List.of(),
                "exec-1",
                "corr-1",
                "trace-1"
        );
        when(kernelFacade.executeFlow(eq("AtivarCrossDocking"), anyMap(), any())).thenReturn(okResult);

        PanelRuntime runtime = new PanelRuntime(
                metadataService,
                new PermissionAwareUiMetadataService(
                        metadataService,
                        new ObjectMapper(),
                        PermissionEvaluator.allowAll(),
                        new BetaSecurityRoleEvaluator()
                ),
                flowActionPanelModel(),
                null,
                null,
                null,
                null,
                kernelFacade
        );

        Map<String, Object> input = Map.of("recebimentoId", "r-1", "expedicaoId", "e-1", "produtoId", "p-1");
        Map<String, Object> response = runtime.executeAction(
                "CrossDockingConsolePanel",
                "ativar",
                input,
                ExecutionContext.of("trial", "admin")
        );

        assertEquals("OK", response.get("status"));
        assertEquals("exec-1", response.get("executionId"));
        assertEquals("corr-1", response.get("correlationId"));
        verify(kernelFacade).executeFlow(eq("AtivarCrossDocking"), eq(input), any());
    }

    @Test
    void reportsUnsupportedWhenKernelFacadeIsUnavailable() {
        PanelRuntime runtime = new PanelRuntime(
                metadataService,
                null,
                flowActionPanelModel(),
                null,
                null,
                null
        );

        Map<String, Object> response = runtime.executeAction(
                "CrossDockingConsolePanel",
                "ativar",
                Map.of(),
                ExecutionContext.anonymous()
        );

        assertEquals("UNSUPPORTED", response.get("status"));
    }

    private static CompiledModel flowActionPanelModel() {
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
                        List.of()
                )),
                Map.of(),
                Map.of(),
                null
        );
        return new CompiledModel(
                "panel.runtime.flow",
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
