package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.EventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1: a procedure declared {@code runAs: "system"} runs its steps (and the procedures it calls) with the tenant's
 * system principal, never changes the tenant, and tells adapters/audit who it acted for. A normal procedure is
 * untouched, and the elevation does not leak past the call.
 */
class DefaultProcedureExecutorRunAsTest {

    private static final ExecutionContext RIDER = ExecutionContext.of("tenant-a", "rider1");
    private static final EventBus NOOP = event -> { };

    private final List<Map<String, Object>> seen = new ArrayList<>();

    private DefaultProcedureExecutor executor(Map<String, ProcedureDefinition> registry) {
        return new DefaultProcedureExecutor(
                new DefaultConceptGateway(new InMemoryConceptStore()),
                (call, state) -> {
                    seen.add(new LinkedHashMap<>(state));
                    return CapabilityResult.success(Map.of("ok", true));
                },
                NOOP,
                registry);
    }

    private static ProcedureStep probe(String name) {
        return ProcedureStep.callCapability(name, "probe", "ProbeCapability", "probe", "ping", List.of(), "r-" + name);
    }

    @Test
    void elevatedProcedureRunsAsSystemSameTenantAndRecordsTheCaller() {
        ProcedureDefinition elevated = new ProcedureDefinition("Hold", List.of(probe("a")), true);
        ProcedureExecutionResult result = executor(Map.of()).execute(elevated, Map.of(), RIDER);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals("tenant-a", seen.get(0).get("tenantId"));
        assertEquals(ExecutionContext.system("tenant-a").actorId(), seen.get(0).get("actorId"));
        assertEquals("rider1", seen.get(0).get("onBehalfOf"));
        assertNull(ProcedureElevation.onBehalfOf(), "elevation must not leak after the call");
    }

    @Test
    void normalProcedureKeepsTheCallerAndHasNoOnBehalfOf() {
        ProcedureDefinition normal = new ProcedureDefinition("Plain", List.of(probe("a")));
        executor(Map.of()).execute(normal, Map.of(), RIDER);

        assertEquals("rider1", seen.get(0).get("actorId"));
        assertNull(seen.get(0).get("onBehalfOf"));
    }
}
