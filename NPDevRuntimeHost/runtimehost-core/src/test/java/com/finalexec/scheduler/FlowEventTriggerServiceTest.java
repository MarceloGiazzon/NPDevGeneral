package com.finalexec.scheduler;

import com.finalexec.config.DefaultTenant;
import com.finalexec.config.ModelHolder;
import com.npdev.adapters.flowcompiled.CompiledModelFlowDefinitionProvider;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledFlowTrigger;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.inproc.InProcEventBus;
import com.npdev.kernel.ports.BulkheadStore;
import com.npdev.kernel.ports.CircuitBreakerStateStore;
import com.npdev.kernel.ports.CorrelationOwnershipStore;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.ExecutionTracer;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.ports.IdempotencyStore;
import com.npdev.kernel.ports.JsonCodec;
import com.npdev.kernel.trace.FlowTraceMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FORM P3-T5c: the event-triggered flow subscriber, against a real in-process bus and kernel. */
class FlowEventTriggerServiceTest {

    private static final String EVENT = "ShelfRegistered";

    /** Records every flow the kernel starts: "flow@tenant". */
    private static final class RecordingTracer implements ExecutionTracer {
        final List<String> starts = new CopyOnWriteArrayList<>();
        volatile Runnable onStart = () -> { };

        @Override
        public void onFlowStart(FlowTraceMeta meta, long startedAtEpochMs) {
            starts.add(meta.flowName() + "@" + meta.tenantId());
            onStart.run();
        }
    }

    private static CompiledFlow triggerFlow(String name, String event, String condition) {
        return new CompiledFlow(
                name, "Order", "sync",
                List.of(new CompiledFlowStep("return", "return", null, null, List.of(), null, null, "$input", null)),
                null, null, null, false, null, null,
                new CompiledFlowTrigger("event", event, condition, null), "internal", "system");
    }

    private static CompiledModel model(CompiledFlow... flows) {
        return new CompiledModel("demo", "1.0", Map.of(), List.of(), List.of(), List.of(), List.of(flows));
    }

    private static KernelRunner runner(ModelHolder holder, EventBus bus, RecordingTracer tracer) {
        return new KernelRunner(
                bus,
                (entityName, payload) -> List.of(),
                new CompiledModelFlowDefinitionProvider(holder.get()),
                new RegistryCapabilityDispatcher(new CapabilityRegistry()),
                tracer,
                null,
                FlowInstanceStore.noop(),
                CorrelationOwnershipStore.noop(),
                CircuitBreakerStateStore.noop(),
                BulkheadStore.noop(),
                IdempotencyStore.noop(),
                JsonCodec.noop(),
                (schema, payload) -> List.of());
    }

    private static final java.util.concurrent.atomic.AtomicInteger CORRELATIONS = new java.util.concurrent.atomic.AtomicInteger();

    /** Every event gets its own correlation id, like a separate CRUD request: only {@link #eventIn} shares one. */
    private static EventEnvelope event(String name, Map<String, Object> payload, String tenant) {
        return eventIn("corr-" + CORRELATIONS.incrementAndGet(), name, payload, tenant);
    }

    private static EventEnvelope eventIn(String correlation, String name, Map<String, Object> payload, String tenant) {
        return EventEnvelope.create(name, payload, correlation, "cause", "external", 0, tenant, null);
    }

    private record Harness(ModelHolder holder, InProcEventBus bus, RecordingTracer tracer, KernelRunner runner,
                           FlowEventTriggerService service) { }

    private static Harness harness(CompiledFlow... flows) {
        ModelHolder holder = new ModelHolder(model(flows));
        InProcEventBus bus = new InProcEventBus();
        RecordingTracer tracer = new RecordingTracer();
        KernelRunner runner = runner(holder, bus, tracer);
        FlowEventTriggerService service = new FlowEventTriggerService(holder, runner);
        service.start();
        return new Harness(holder, bus, tracer, runner, service);
    }

    @Test
    void publishedEventRunsTheTriggerFlowAsSystemInTheEventsTenant() {
        Harness h = harness(triggerFlow("OnShelf", EVENT, null));
        h.bus.publish(event(EVENT, Map.of("name", "A"), "tenant-a"));
        assertEquals(List.of("OnShelf@tenant-a"), h.tracer.starts);
        h.service.stop();
    }

    @Test
    void eventWithoutTenantRunsInTheHomeTenant() {
        Harness h = harness(triggerFlow("OnShelf", EVENT, null));
        h.bus.publish(event(EVENT, Map.of(), null));
        h.bus.publish(event(EVENT, Map.of(), "default"));
        assertEquals(List.of("OnShelf@" + DefaultTenant.id(), "OnShelf@" + DefaultTenant.id()), h.tracer.starts);
        h.service.stop();
    }

    @Test
    void otherEventsDoNotTrigger() {
        Harness h = harness(triggerFlow("OnShelf", EVENT, null));
        h.bus.publish(event("SomethingElse", Map.of(), "t"));
        assertTrue(h.tracer.starts.isEmpty());
        h.service.stop();
    }

    @Test
    void conditionGatesTheFlowOnEventFields() {
        Harness h = harness(triggerFlow("OnBigShelf", EVENT, "$event.total > 5"));
        h.bus.publish(event(EVENT, Map.of("total", 3), "t"));
        assertTrue(h.tracer.starts.isEmpty(), "condition false must not start the flow");
        h.bus.publish(event(EVENT, Map.of("total", 9), "t"));
        assertEquals(List.of("OnBigShelf@t"), h.tracer.starts);
        h.service.stop();
    }

    @Test
    void conditionThatDoesNotEvaluateDoesNotStartTheFlowAndDoesNotThrow() {
        Harness h = harness(triggerFlow("Broken", EVENT, "$event.total >>> ("));
        assertDoesNotThrow(() -> h.bus.publish(event(EVENT, Map.of("total", 9), "t")));
        assertTrue(h.tracer.starts.isEmpty());
        h.service.stop();
    }

    @Test
    void sameEventIdIsDeliveredToTheFlowOnce() {
        Harness h = harness(triggerFlow("OnShelf", EVENT, null));
        EventEnvelope once = event(EVENT, Map.of(), "t");
        h.bus.publish(once);
        h.bus.publish(once);
        assertEquals(1, h.tracer.starts.size());
        h.service.stop();
    }

    @Test
    void sameEventRepublishedUnderANewIdInTheSameCorrelationIsAnEffectiveDuplicate() {
        // The orchestration rules' second key: event name + correlation + subject.
        Harness h = harness(triggerFlow("OnShelf", EVENT, null));
        h.bus.publish(eventIn("corr-x", EVENT, Map.of("id", "S1"), "t"));
        h.bus.publish(eventIn("corr-x", EVENT, Map.of("id", "S1"), "t"));
        assertEquals(1, h.tracer.starts.size(), "same correlation + subject, new event id: still one run");
        h.bus.publish(eventIn("corr-x", EVENT, Map.of("id", "S2"), "t"));
        h.bus.publish(eventIn("corr-y", EVENT, Map.of("id", "S1"), "t"));
        assertEquals(3, h.tracer.starts.size(), "another subject, or another correlation, is a new delivery");
        h.service.stop();
    }

    @Test
    void aRunThatDoesNotFinishReleasesItsClaimSoARedeliveryRetries() {
        Harness h = harness(triggerFlow("OnShelf", EVENT, null));
        EventEnvelope once = event(EVENT, Map.of("id", "S1"), "t");
        h.tracer.onStart = () -> { throw new IllegalStateException("boom"); };
        h.bus.publish(once);
        h.tracer.onStart = () -> { };
        h.bus.publish(once);
        assertEquals(2, h.tracer.starts.size(), "the failed first attempt must not block the retry");
        h.bus.publish(once);
        assertEquals(2, h.tracer.starts.size(), "the OK retry keeps its claim: a third delivery is a duplicate");
        h.service.stop();
    }

    @Test
    void reloadResubscribesFromTheNewModel() {
        Harness h = harness(triggerFlow("OnShelf", EVENT, null));
        assertEquals(1, h.service.subscriptionCount());

        h.holder.swap(model(triggerFlow("OnOther", "OtherEvent", null)));
        assertEquals(1, h.service.subscriptionCount());
        h.bus.publish(event(EVENT, Map.of(), "t"));
        assertTrue(h.tracer.starts.isEmpty(), "the removed trigger must be unsubscribed");

        h.holder.swap(model());
        assertEquals(0, h.service.subscriptionCount());
        h.service.stop();
    }

    @Test
    void flowsWithoutAnEventTriggerAreNotSubscribed() {
        CompiledFlowStep step = new CompiledFlowStep("return", "return", null, null, List.of(), null, null, "$input", null);
        CompiledFlow plain = new CompiledFlow("Plain", "Order", "sync", List.of(step));
        CompiledFlow webhook = new CompiledFlow(
                "OnHook", "Order", "sync", List.of(step), null, null, null, false, null, null,
                new CompiledFlowTrigger("webhook", null, null, "stripe"), null, null);
        Harness h = harness(plain, webhook);
        assertEquals(0, h.service.subscriptionCount());
        h.service.stop();
    }

    @Test
    void failureInsideTheFlowNeverReachesThePublisher() {
        // The model names a flow the provider does not know, so execute() fails; the publisher must not notice.
        Harness h = harness(triggerFlow("OnShelf", EVENT, null));
        assertDoesNotThrow(() -> h.service.fire("NoSuchFlow", null, event(EVENT, Map.of(), "t")));
        h.tracer.onStart = () -> { throw new IllegalStateException("boom"); };
        assertDoesNotThrow(() -> h.bus.publish(event(EVENT, Map.of(), "t")));
        h.service.stop();
    }

    @Test
    void aFlowThatReEmitsItsOwnTriggerIsCutAtTheDepthLimit() {
        Harness h = harness(triggerFlow("Loop", EVENT, null));
        h.tracer.onStart = () -> h.bus.publish(event(EVENT, Map.of(), "t"));
        h.bus.publish(event(EVENT, Map.of(), "t"));
        assertEquals(FlowEventTriggerService.MAX_DEPTH, h.tracer.starts.size());
        // and the guard releases: a fresh, unrelated delivery on this thread still works
        h.tracer.onStart = () -> { };
        h.tracer.starts.clear();
        h.bus.publish(event(EVENT, Map.of(), "t"));
        assertEquals(1, h.tracer.starts.size());
        h.service.stop();
    }
}
