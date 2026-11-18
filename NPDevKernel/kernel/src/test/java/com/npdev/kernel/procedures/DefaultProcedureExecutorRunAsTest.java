package com.npdev.kernel.procedures;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1: a procedure declared {@code runAs: "system"} runs its steps with the tenant's system principal, never changes the
 * tenant, and tells the gateway/audit who it acted for. A normal procedure is untouched, and the elevation does not
 * leak past the call. (A recording gateway observes the context and elevation each concept call is made with.)
 */
class DefaultProcedureExecutorRunAsTest {

    private static final ExecutionContext RIDER = ExecutionContext.of("tenant-a", "rider1");

    private record Seen(String tenantId, String actorId, String onBehalfOf) { }

    private final List<Seen> seen = new ArrayList<>();

    private DefaultProcedureExecutor executor() {
        ConceptGateway real = new DefaultConceptGateway(new InMemoryConceptStore());
        ConceptGateway recording = (ConceptGateway) Proxy.newProxyInstance(
                ConceptGateway.class.getClassLoader(), new Class<?>[]{ConceptGateway.class}, (proxy, method, args) -> {
                    for (Object arg : args) {
                        if (arg instanceof ExecutionContext context) {
                            seen.add(new Seen(context.tenantId(), context.actorId(), ProcedureElevation.onBehalfOf()));
                        }
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new DefaultProcedureExecutor(recording);
    }

    private static ProcedureStep probe(String name) {
        return ProcedureStep.listConcepts(name, "Thing", "r-" + name);
    }

    @Test
    void elevatedProcedureRunsAsSystemSameTenantAndRecordsTheCaller() {
        ProcedureDefinition elevated = new ProcedureDefinition("Hold", List.of(probe("a")), true);
        ProcedureExecutionResult result = executor().execute(elevated, Map.of(), RIDER);

        assertTrue(result.ok(), result.failureMessage());
        assertEquals("tenant-a", seen.get(0).tenantId());
        assertEquals(ExecutionContext.system("tenant-a").actorId(), seen.get(0).actorId());
        assertEquals("rider1", seen.get(0).onBehalfOf());
        assertNull(ProcedureElevation.onBehalfOf(), "elevation must not leak after the call");
    }

    @Test
    void normalProcedureKeepsTheCallerAndHasNoOnBehalfOf() {
        ProcedureDefinition normal = new ProcedureDefinition("Plain", List.of(probe("a")));
        executor().execute(normal, Map.of(), RIDER);

        assertEquals("rider1", seen.get(0).actorId());
        assertNull(seen.get(0).onBehalfOf());
    }
}
