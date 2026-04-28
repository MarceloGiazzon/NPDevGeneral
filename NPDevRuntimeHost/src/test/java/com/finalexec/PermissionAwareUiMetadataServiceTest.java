package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.BetaSecurityRoleEvaluator;
import com.finalexec.npdev.service.PermissionAwareUiMetadataService;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.security.PermissionGrant;
import com.npdev.kernel.security.StaticPermissionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionAwareUiMetadataServiceTest {

    private final PermissionAwareUiMetadataService service = new PermissionAwareUiMetadataService(
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

    @Test
    void supportRoleGetsDisabledCreateActionAndReadonlyLifecycleFields() {
        ExecutionContext context = ExecutionContext.of("dev", "support-agent").withRoles(Set.of("SUPPORT"));

        Map<String, Object> actions = service.actions("Appointment", null, context);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actionItems = (List<Map<String, Object>>) actions.get("items");
        Map<String, Object> createAppointment = actionItems.stream()
                .filter(item -> "CreateAppointment".equals(item.get("name")))
                .findFirst()
                .orElseThrow();

        assertEquals("disabled", createAppointment.get("uiState"));
        assertEquals(Boolean.FALSE, createAppointment.get("available"));
        assertEquals("Creating appointments requires scheduling privileges.",
                ((Map<?, ?>) createAppointment.get("denial")).get("message"));

        Map<String, Object> fields = service.fields("Appointment", null, context);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldItems = (List<Map<String, Object>>) fields.get("items");
        Map<String, Object> checkInTime = fieldItems.stream()
                .filter(item -> "checkInTime".equals(item.get("fieldPath")))
                .findFirst()
                .orElseThrow();

        assertEquals("readonly", checkInTime.get("permissionState"));
        assertEquals(Boolean.FALSE, checkInTime.get("editable"));
    }

    @Test
    void generalUserGetsHiddenFieldsAndSuppressedClaimAutomationAction() {
        ExecutionContext context = ExecutionContext.of("dev", "viewer").withRoles(Set.of("USER"));

        Map<String, Object> fields = service.fields("Appointment", null, context);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> visibleFields = (List<Map<String, Object>>) fields.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hiddenFields = (List<Map<String, Object>>) fields.get("suppressedItems");

        assertFalse(visibleFields.stream().anyMatch(item -> "checkInTime".equals(item.get("fieldPath"))));
        assertTrue(hiddenFields.stream().anyMatch(item -> "checkInTime".equals(item.get("fieldPath"))));

        Map<String, Object> actions = service.actions(null, null, context);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hiddenActions = (List<Map<String, Object>>) actions.get("suppressedItems");

        assertTrue(hiddenActions.stream().anyMatch(item -> "CompleteAppointmentFlow#1".equals(item.get("name"))));
        assertEquals(1, ((Number) actions.get("hiddenCount")).intValue());
    }
}
