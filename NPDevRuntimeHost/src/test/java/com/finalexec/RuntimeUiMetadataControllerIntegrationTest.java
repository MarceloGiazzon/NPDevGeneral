package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.api.RuntimeUiMetadataController;
import com.finalexec.npdev.service.BetaSecurityRoleEvaluator;
import com.finalexec.npdev.service.PermissionAwareUiMetadataService;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.security.PermissionGrant;
import com.npdev.kernel.security.StaticPermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeUiMetadataControllerIntegrationTest {

    private MockMvc mockMvc;
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);

    @BeforeEach
    void setUp() {
        PermissionAwareUiMetadataService service = new PermissionAwareUiMetadataService(
                new RuntimeMetadataService(new ObjectMapper()),
                new ObjectMapper(),
                new StaticPermissionEvaluator(List.of(
                        new PermissionGrant("appointments.create", "dev", "", "admin"),
                        new PermissionGrant("notifications.send", "dev", "", "admin"),
                        new PermissionGrant("notifications.send", "dev", "", "support"),
                        new PermissionGrant("appointments.reminders.schedule", "dev", "", "admin"),
                        new PermissionGrant("appointments.reminders.schedule", "dev", "", "support"),
                        new PermissionGrant("claims.create", "dev", "", "admin")
                )),
                new BetaSecurityRoleEvaluator()
        );
        RuntimeUiMetadataController controller = new RuntimeUiMetadataController(service, runtimeContextService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void supportRolePreviewShowsReadonlyFieldsAndDisabledCreateAction() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "support-agent").withRoles(Set.of("SUPPORT")));

        mockMvc.perform(get("/api/runtime/metadata/ui/preview/Appointment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionAware").value(true))
                .andExpect(jsonPath("$.actor.roleProfile").value("OPERATOR"))
                .andExpect(jsonPath("$.fields[?(@.fieldPath=='checkInTime')].permissionState").value("readonly"))
                .andExpect(jsonPath("$.previewSupport.actionLabels[0].uiState").value("disabled"));
    }

    @Test
    void generalUserActionCatalogExplainsSuppressedClaimAutomation() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("dev", "viewer").withRoles(Set.of("USER")));

        mockMvc.perform(get("/api/runtime/metadata/ui/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hiddenCount").value(1))
                .andExpect(jsonPath("$.suppressedItems[0].name").value("CompleteAppointmentFlow#1"))
                .andExpect(jsonPath("$.suppressedItems[0].denial.message")
                        .value("Insurance claim automation is reserved for privileged runtime roles."));
    }
}
