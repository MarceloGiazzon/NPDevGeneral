package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.api.DirectExecutionGatewayController;
import com.finalexec.api.ExecutionMonitorController;
import com.finalexec.api.RuntimeMetadataController;
import com.finalexec.api.RuntimePluginStatusController;
import com.finalexec.api.RuntimeSchedulesController;
import com.finalexec.api.RuntimeUiMetadataController;
import com.finalexec.api.SupportDiagnosticsController;
import com.finalexec.execution.DirectExecutionGateway;
import com.finalexec.npdev.service.BetaSecurityRoleEvaluator;
import com.finalexec.npdev.service.ExecutionMonitorService;
import com.finalexec.npdev.service.PermissionAwareUiMetadataService;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.finalexec.npdev.service.RuntimePluginStatusSummary;
import com.finalexec.npdev.service.SupportDiagnosticsService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.runtime.support.GeneratedCrudRuntimeSupport;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupportedCoreControllerBlackBoxStandaloneTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void directExecutionGatewayAcceptsGovernedExecutionRequests() throws Exception {
        DirectExecutionGateway gateway = Mockito.mock(DirectExecutionGateway.class);
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR")));
        when(gateway.execute(any(), any()))
                .thenReturn(Map.of(
                        "executionLifecycleStatus", "EXECUTED",
                        "tenantId", "dev",
                        "flowName", "TypedHappyPath"
                ));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new DirectExecutionGatewayController(gateway, runtimeContextService)
        ).build();

        mockMvc.perform(post("/api/v1/execute/flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "dev",
                                  "flowName": "TypedHappyPath",
                                  "input": {
                                    "email": "operator@example.test"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionLifecycleStatus").value("EXECUTED"))
                .andExpect(jsonPath("$.tenantId").value("dev"))
                .andExpect(jsonPath("$.flowName").value("TypedHappyPath"));
    }

    @Test
    void directExecutionGatewayRejectsInvalidForbiddenAndUnavailableRequests() throws Exception {
        DirectExecutionGateway gateway = Mockito.mock(DirectExecutionGateway.class);
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "viewer").withRoles(Set.of("USER")));
        when(gateway.execute(any(), any())).thenThrow(new IllegalArgumentException("tenantId is required."));
        when(gateway.executePanelAction(any(), any())).thenThrow(new SecurityException("direct execution blocked"));
        when(gateway.summary()).thenThrow(new IllegalStateException("direct execution evidence unavailable"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new DirectExecutionGatewayController(gateway, runtimeContextService)
        ).build();

        mockMvc.perform(post("/api/v1/execute/flow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "",
                                  "flowName": "TypedHappyPath"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/execute/panel-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenantId": "dev",
                                  "panelName": "AppointmentPanel",
                                  "actionName": "submit"
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/direct-execution-gateway"))
                .andExpect(status().isForbidden());

        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "admin").withRoles(Set.of("ADMIN")));

        mockMvc.perform(get("/api/v1/admin/direct-execution-gateway"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void executionMonitorExposesActiveAndHistorySummaries() throws Exception {
        ExecutionMonitorService service = Mockito.mock(ExecutionMonitorService.class);
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR")));
        when(service.active(any())).thenReturn(Map.of(
                "surfaceName", "Execution Monitor",
                "activeCount", 1,
                "items", List.of(Map.of("executionId", "exec-1"))
        ));
        when(service.history(any())).thenReturn(Map.of(
                "surfaceName", "Execution Monitor",
                "historyCount", 1,
                "items", List.of(Map.of("executionId", "exec-1"))
        ));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new ExecutionMonitorController(service, runtimeContextService)
        ).build();

        mockMvc.perform(get("/api/v1/executions/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(1))
                .andExpect(jsonPath("$.items[0].executionId").value("exec-1"));

        mockMvc.perform(get("/api/v1/executions/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyCount").value(1))
                .andExpect(jsonPath("$.items[0].executionId").value("exec-1"));
    }

    @Test
    void executionMonitorLinksTranslateNotFoundForbiddenAndUnavailableFailures() throws Exception {
        ExecutionMonitorService service = Mockito.mock(ExecutionMonitorService.class);
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR")));
        when(service.links(eq("missing"), any())).thenThrow(new IllegalArgumentException("executionId was not found."));
        when(service.active(any())).thenThrow(new SecurityException("forbidden"));
        when(service.history(any())).thenThrow(new IllegalStateException("execution monitor unavailable"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new ExecutionMonitorController(service, runtimeContextService)
        ).build();

        mockMvc.perform(get("/api/v1/executions/missing/links"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/executions/active"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/executions/history"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void runtimeSchedulesExposeListAndProcessDueOperations() throws Exception {
        GeneratedCrudRuntimeSupport runtimeSupport = Mockito.mock(GeneratedCrudRuntimeSupport.class);
        when(runtimeSupport.listScheduledEvents(50, 5))
                .thenReturn(List.of(Map.of(
                        "id", "schedule-1",
                        "status", "PENDING"
                )));
        when(runtimeSupport.processDueScheduledEvents(Boolean.TRUE, 10))
                .thenReturn(Map.of(
                        "status", "processed",
                        "processed", 1
                ));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RuntimeSchedulesController(runtimeSupport)
        ).build();

        mockMvc.perform(get("/api/runtime/schedules")
                        .param("limit", "50")
                        .param("offset", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("schedule-1"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        mockMvc.perform(post("/api/runtime/schedules/process-due")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("limit", "10")
                        .content("""
                                {
                                  "forceDue": "true"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processed"))
                .andExpect(jsonPath("$.processed").value(1))
                .andExpect(jsonPath("$.requestedForceDue").value(true));
    }

    @Test
    void runtimeSchedulesTranslateValidationAndSecurityFailures() throws Exception {
        GeneratedCrudRuntimeSupport runtimeSupport = Mockito.mock(GeneratedCrudRuntimeSupport.class);
        when(runtimeSupport.processDueScheduledEvents(Boolean.FALSE, 5))
                .thenThrow(new IllegalArgumentException("limit must be positive"));
        when(runtimeSupport.listScheduledEvents(anyInt(), anyInt()))
                .thenThrow(new SecurityException("forbidden"));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RuntimeSchedulesController(runtimeSupport)
        ).build();

        mockMvc.perform(post("/api/runtime/schedules/process-due")
                        .contentType(MediaType.APPLICATION_JSON)
                        .param("forceDue", "false")
                        .param("limit", "5"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/runtime/schedules"))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportDiagnosticsExposeDiagnosticsIssuesTracesAndBlockedStates() throws Exception {
        SupportDiagnosticsService diagnosticsService = Mockito.mock(SupportDiagnosticsService.class);
        BetaSecurityRoleEvaluator roleEvaluator = Mockito.mock(BetaSecurityRoleEvaluator.class);
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        ExecutionContext context = ExecutionContext.of("dev", "support-agent").withRoles(Set.of("SUPPORT"));
        when(runtimeContextService.currentContext(any())).thenReturn(context);
        when(roleEvaluator.hasPrivilegedAccess(context)).thenReturn(true);
        when(diagnosticsService.diagnostics(context)).thenReturn(Map.of("issueCount", 1, "traceCount", 1));
        when(diagnosticsService.issues(context)).thenReturn(Map.of("count", 1, "items", List.of(Map.of("issueReference", "issue-1"))));
        when(diagnosticsService.traces(context)).thenReturn(Map.of("count", 1, "items", List.of(Map.of("traceReference", "trace-1"))));
        when(diagnosticsService.blockedStates(context)).thenReturn(Map.of("count", 1, "items", List.of(Map.of("blockedStateReference", "blocked-1"))));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new SupportDiagnosticsController(diagnosticsService, roleEvaluator, runtimeContextService)
        ).build();

        mockMvc.perform(get("/api/v1/support/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueCount").value(1))
                .andExpect(jsonPath("$.traceCount").value(1));

        mockMvc.perform(get("/api/v1/support/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].issueReference").value("issue-1"));

        mockMvc.perform(get("/api/v1/support/traces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].traceReference").value("trace-1"));

        mockMvc.perform(get("/api/v1/support/blocked-states"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].blockedStateReference").value("blocked-1"));
    }

    @Test
    void supportDiagnosticsEnforcePrivilegedAccessAndTranslateFailures() throws Exception {
        SupportDiagnosticsService diagnosticsService = Mockito.mock(SupportDiagnosticsService.class);
        BetaSecurityRoleEvaluator roleEvaluator = Mockito.mock(BetaSecurityRoleEvaluator.class);
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        ExecutionContext context = ExecutionContext.of("dev", "viewer").withRoles(Set.of("USER"));
        when(runtimeContextService.currentContext(any())).thenReturn(context);
        when(roleEvaluator.hasPrivilegedAccess(context)).thenReturn(false);

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new SupportDiagnosticsController(diagnosticsService, roleEvaluator, runtimeContextService)
        ).build();

        mockMvc.perform(get("/api/v1/support/diagnostics"))
                .andExpect(status().isForbidden());

        when(roleEvaluator.hasPrivilegedAccess(context)).thenReturn(true);
        when(diagnosticsService.diagnostics(context)).thenThrow(new IllegalStateException("support evidence unavailable"));

        mockMvc.perform(get("/api/v1/support/diagnostics"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void runtimeMetadataControllerRejectsUnsupportedCatalogAndNonAdminAccess() throws Exception {
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        RuntimeMetadataController controller = new RuntimeMetadataController(
                new RuntimeMetadataService(new ObjectMapper()),
                runtimeContextService
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "viewer").withRoles(Set.of("USER")));

        mockMvc.perform(get("/api/admin/runtime/metadata"))
                .andExpect(status().isForbidden());

        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "admin").withRoles(Set.of("ADMIN")));

        mockMvc.perform(get("/api/admin/runtime/metadata/catalogs/unknown"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void runtimePluginStatusReturnsSummaryAndFailsClosedForAdminBoundaries() throws Exception {
        RuntimePluginStatusSummary summary = Mockito.mock(RuntimePluginStatusSummary.class);
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "admin").withRoles(Set.of("ADMIN")));
        when(summary.toSummary()).thenReturn(Map.of(
                "deploymentProfile", "default",
                "selectedPackageIds", List.of("notification-inproc-package")
        ));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RuntimePluginStatusController(summary, runtimeContextService)
        ).build();

        mockMvc.perform(get("/api/admin/runtime/plugin-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentProfile").value("default"))
                .andExpect(jsonPath("$.selectedPackageIds[0]").value("notification-inproc-package"));

        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "viewer").withRoles(Set.of("USER")));

        mockMvc.perform(get("/api/admin/runtime/plugin-status"))
                .andExpect(status().isForbidden());

        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "admin").withRoles(Set.of("ADMIN")));
        when(summary.toSummary()).thenThrow(new IllegalStateException("plugin status inventory unavailable"));

        mockMvc.perform(get("/api/admin/runtime/plugin-status"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void runtimeUiMetadataReturnsServiceUnavailableWhenPanelRuntimeIsMissing() throws Exception {
        PermissionAwareUiMetadataService permissionAwareUiMetadataService = Mockito.mock(PermissionAwareUiMetadataService.class);
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "operator").withRoles(Set.of("OPERATOR")));

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new RuntimeUiMetadataController(permissionAwareUiMetadataService, runtimeContextService)
        ).build();

        mockMvc.perform(get("/api/runtime/metadata/ui/panels/AppointmentPanel"))
                .andExpect(status().isServiceUnavailable());
    }
}
