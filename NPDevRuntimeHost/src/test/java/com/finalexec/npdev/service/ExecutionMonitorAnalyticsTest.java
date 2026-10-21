package com.finalexec.npdev.service;

import com.npdev.generated.runtime.service.KernelFacade;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.StepTrace;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FLOW-1: tenant and permission posture of the analytics read, and its paging/validation. */
class ExecutionMonitorAnalyticsTest {

    private final KernelFacade facade = Mockito.mock(KernelFacade.class);
    private final ExecutionMonitorService service =
            new ExecutionMonitorService(facade, Mockito.mock(PublicationChainReferenceResolver.class));

    private static FlowTrace trace(String id, String tenant) {
        StepTrace step = new StepTrace(0, "s", "CAPABILITY_CALL", 1000, 1010, StepOutcome.OK, Map.of(), List.of(), null);
        return new FlowTrace(new FlowTraceMeta(id, "c-" + id, "F", tenant, "a", Map.of()), 1000, 1010, StepOutcome.OK, List.of(step));
    }

    private static ExecutionContext tenantUser(String tenant) {
        return ExecutionContext.of(tenant, "user").withRoles(Set.of("USER"));
    }

    private static ExecutionContext superUser() {
        return ExecutionContext.of("__system__", "root").withRoles(Set.of("SUPERUSER"));
    }

    @Test
    void ownTenantReadsThroughTheTenantForcedSearch() {
        ExecutionContext viewer = tenantUser("tenant-a");
        when(facade.searchTraces(any(), eq(viewer))).thenReturn(List.of(trace("e1", "tenant-a")));

        Map<String, Object> out = service.analytics("F", null, null, null, viewer);

        assertEquals("tenant-a", out.get("tenant"));
        assertEquals(1, out.get("executionCount"));
        verify(facade, never()).searchTracesForTenant(any(), any(), any());
        // Naming your own tenant explicitly is not a cross-tenant read.
        service.analytics(null, null, null, "tenant-a", viewer);
        verify(facade, never()).searchTracesForTenant(any(), any(), any());
    }

    @Test
    void anotherTenantIsForbiddenForANonSuperUserAndNeverReadsTraces() {
        ExecutionContext viewer = tenantUser("tenant-b");
        assertThrows(SecurityException.class, () -> service.analytics(null, null, null, "tenant-a", viewer));
        ExecutionContext admin = ExecutionContext.of("tenant-b", "adm").withRoles(Set.of("ADMIN"));
        assertThrows(SecurityException.class, () -> service.analytics(null, null, null, "tenant-a", admin),
                "ADMIN is not SUPERUSER -- the same gate attention uses");
        verify(facade, never()).searchTraces(any(), any());
        verify(facade, never()).searchTracesForTenant(any(), any(), any());
    }

    @Test
    void superUserNamingATenantUsesTheExplicitTenantSearch() {
        ExecutionContext root = superUser();
        when(facade.searchTracesForTenant(any(), eq("tenant-a"), eq(root))).thenReturn(List.of(trace("e1", "tenant-a")));

        Map<String, Object> out = service.analytics(null, null, null, "tenant-a", root);

        assertEquals("tenant-a", out.get("tenant"));
        assertEquals(1, out.get("executionCount"));
        verify(facade, never()).searchTraces(any(), any());
    }

    @Test
    void passesTheFlowAndTimeWindowToTheStoreAndRejectsBadInput() {
        ExecutionContext viewer = tenantUser("tenant-a");
        when(facade.searchTraces(any(), any())).thenReturn(List.of());

        service.analytics("F", "1000", "2026-01-01T00:00:00Z", null, viewer);

        ArgumentCaptor<TraceQuery> query = ArgumentCaptor.forClass(TraceQuery.class);
        verify(facade).searchTraces(query.capture(), eq(viewer));
        assertEquals("F", query.getValue().flowName());
        assertEquals(1000L, query.getValue().fromEpochMs());
        assertEquals(1767225600000L, query.getValue().toEpochMs());

        assertThrows(IllegalArgumentException.class, () -> service.analytics(null, "yesterday", null, null, viewer));
        assertThrows(IllegalArgumentException.class, () -> service.analytics(null, "2000", "1000", null, viewer));
    }

    @Test
    void pagesThroughEveryTraceAndReportsTruncationAtTheScanLimit() {
        ExecutionContext viewer = tenantUser("tenant-a");
        List<FlowTrace> fullPage = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            fullPage.add(trace("e" + i, "tenant-a"));
        }
        when(facade.searchTraces(any(), any())).thenReturn(fullPage, fullPage, List.of(trace("last", "tenant-a")));

        Map<String, Object> out = service.analytics(null, null, null, null, viewer);

        assertEquals(2001, out.get("executionCount"));
        assertEquals(false, out.get("truncated"));

        when(facade.searchTraces(any(), any())).thenReturn(fullPage);
        Map<String, Object> capped = service.analytics(null, null, null, null, viewer);
        assertEquals(ExecutionMonitorService.ANALYTICS_MAX_TRACES, capped.get("executionCount"));
        assertTrue((Boolean) capped.get("truncated"));
    }
}
