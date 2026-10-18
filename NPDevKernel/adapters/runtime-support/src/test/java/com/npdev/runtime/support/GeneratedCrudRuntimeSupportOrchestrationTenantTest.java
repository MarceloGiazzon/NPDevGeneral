package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledOrchestration;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationAction;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationTrigger;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.EventStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.RuntimeInvariantEngineFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RUN-2: an orchestration rule that calls a capability always hands the handler a tenant -- the
 * event's own when it carries a real one, the app's home tenant otherwise -- records where it came
 * from, and goes through the configured (policy-enforcing) dispatcher.
 */
class GeneratedCrudRuntimeSupportOrchestrationTenantTest {

    private static final String EVENT_NAME = "OrderPlaced";

    @Test
    void anEventWithoutATenantGetsTheHomeTenant() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        Bus bus = new Bus();
        support(bus, dispatcher).withOrchestrationFallbackTenant("home-tenant");

        bus.publish(envelope(null));

        assertEquals("home-tenant", dispatcher.lastContext.get("tenantId"));
        assertEquals("fallback", dispatcher.lastContext.get("tenantSource"));
    }

    @Test
    void theReservedDefaultSentinelCountsAsNoTenant() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        Bus bus = new Bus();
        support(bus, dispatcher).withOrchestrationFallbackTenant("home-tenant");

        bus.publish(envelope("default"));   // what an anonymous publish stamps

        assertEquals("home-tenant", dispatcher.lastContext.get("tenantId"));
        assertEquals("fallback", dispatcher.lastContext.get("tenantSource"));
    }

    @Test
    void anEventWithARealTenantKeepsIt() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        Bus bus = new Bus();
        support(bus, dispatcher).withOrchestrationFallbackTenant("home-tenant");

        bus.publish(envelope("tenant-b"));

        assertEquals("tenant-b", dispatcher.lastContext.get("tenantId"));
        assertEquals("event", dispatcher.lastContext.get("tenantSource"));
    }

    @Test
    void withNoFallbackConfiguredTheBehaviourIsUnchanged() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        Bus bus = new Bus();
        support(bus, dispatcher);

        bus.publish(envelope(null));

        assertNull(dispatcher.lastContext.get("tenantId"));
        assertNull(dispatcher.lastContext.get("tenantSource"));
    }

    @Test
    void theConfiguredDispatcherIsUsedInsteadOfTheBareOne() {
        RecordingDispatcher bare = new RecordingDispatcher();
        RecordingDispatcher wrapped = new RecordingDispatcher();
        Bus bus = new Bus();
        support(bus, bare).withOrchestrationCapabilityDispatcher(wrapped);

        bus.publish(envelope("tenant-b"));

        assertEquals(1, wrapped.calls);
        assertEquals(0, bare.calls);
        assertTrue(wrapped.lastContext.containsKey("orchestration"));
    }

    private static EventEnvelope envelope(String tenantId) {
        return new EventEnvelope("evt-1", EVENT_NAME, System.currentTimeMillis(), Map.of("amount", 500),
                "corr-1", "cause-1", "flow-1", 0, tenantId, null);
    }

    private static GeneratedCrudRuntimeSupport support(Bus bus, CapabilityDispatcher dispatcher) {
        KernelRunner kernelRunner = new KernelRunner(bus, new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }
        });
        CompiledOrchestration rule = new CompiledOrchestration(
                "Notify", null,
                new CompiledOrchestrationTrigger("event", EVENT_NAME),
                new CompiledOrchestrationAction("callCapability", null, "notifyOps", "send", Map.of()));
        CompiledModel model = new CompiledModel("demo", "1.0.0", "v1", Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(rule));
        RuntimeInvariantEngineFactory noop = (uniqueValueLookup, conflictLookup) -> new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }
        };
        return new GeneratedCrudRuntimeSupport(
                () -> model, kernelRunner, null, dispatcher, null, null,
                new SystemRuntimeClock(), new InMemoryOrchestrationExecutionRegistry(), noop,
                AuditLogStore.noop(), PermissionEvaluator.allowAll(), IdempotencyStore.noop());
    }

    private static final class RecordingDispatcher implements CapabilityDispatcher {
        int calls;
        Map<String, Object> lastContext = Map.of();

        @Override
        public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
            calls++;
            lastContext = new LinkedHashMap<>(contextState);
            return CapabilityResult.success(Map.of());
        }
    }

    /** A real EventBus that is also a (no-op) EventStore, as the orchestration bookkeeping requires. */
    private static final class Bus implements EventBus, EventStore {
        private final Map<String, List<EventHandler>> handlers = new LinkedHashMap<>();

        @Override
        public synchronized void publish(EventEnvelope event) {
            for (EventHandler handler : List.copyOf(handlers.getOrDefault(event.eventName(), List.of()))) {
                handler.onEvent(event);
            }
        }

        @Override
        public synchronized AutoCloseable subscribe(String eventName, EventHandler handler) {
            handlers.computeIfAbsent(eventName, ignored -> new ArrayList<>()).add(handler);
            return () -> handlers.get(eventName).remove(handler);
        }

        @Override
        public void append(EventEnvelope event) {
        }

        @Override
        public List<EventEnvelope> readByCorrelation(String correlationId) {
            return List.of();
        }

        @Override
        public List<EventEnvelope> readByEventName(String eventName) {
            return List.of();
        }
    }
}
