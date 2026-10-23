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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-241 (B28 residual): {@code GeneratedCrudRuntimeSupport.initializeOrchestrationSubscribers()}
 * used to run once, from the constructor, and never again -- the EventBus subscription set was
 * external state a {@code /model-reload} never touched. After a reload: an ADDED rule never fired
 * (no subscriber existed for its event); a REMOVED rule kept firing (its subscriber was never told
 * to stop, the worst of the two because it needs no unusual model change); an EDITED condition or
 * mapping kept running its pre-reload body. These tests drive {@code
 * reloadOrchestrationSubscribers(CompiledModel)} directly -- the method a {@code
 * ModelHolder.ModelReloadListener} registered in {@code NpdevCapabilityBindingConfig} calls on
 * every swap -- and observe the effect through a recording {@link CapabilityDispatcher}, since a
 * {@code callCapability} action is the cheapest orchestration action to construct in isolation
 * (no {@code EntityManager}/{@code DataSource} required, unlike {@code create}).
 */
class GeneratedCrudRuntimeSupportOrchestrationReloadTest {

    private static final String EVENT_NAME = "OrderPlaced";

    @Test
    void aRuleAddedByAReloadFires() {
        RecordingCapabilityDispatcher dispatcher = new RecordingCapabilityDispatcher();
        KernelRunner kernelRunner = kernelRunner(new CountingEventBus());
        GeneratedCrudRuntimeSupport support = support(
                GeneratedCrudRuntimeSupportOrchestrationReloadTest::modelWithNoRules,
                kernelRunner,
                dispatcher
        );

        kernelRunner.publishEvent(EVENT_NAME, Map.of("amount", 500));
        assertEquals(0, dispatcher.invocationCount(),
                "a rule that does not exist yet must not fire");

        support.reloadOrchestrationSubscribers(modelWithOrchestration(
                orchestration("Notify", null, EVENT_NAME)));

        kernelRunner.publishEvent(EVENT_NAME, Map.of("amount", 500));
        assertEquals(1, dispatcher.invocationCount(),
                "a rule ADDED by a reload must fire on the next matching event, without "
                        + "reconstructing GeneratedCrudRuntimeSupport");
    }

    @Test
    void aRuleRemovedByAReloadStopsFiring() {
        RecordingCapabilityDispatcher dispatcher = new RecordingCapabilityDispatcher();
        KernelRunner kernelRunner = kernelRunner(new CountingEventBus());
        CompiledModel withRule = modelWithOrchestration(orchestration("Notify", null, EVENT_NAME));
        GeneratedCrudRuntimeSupport support = support(() -> withRule, kernelRunner, dispatcher);

        kernelRunner.publishEvent(EVENT_NAME, Map.of("amount", 500));
        assertEquals(1, dispatcher.invocationCount(), "sanity: the rule fires before removal");

        support.reloadOrchestrationSubscribers(modelWithNoRules());

        kernelRunner.publishEvent(EVENT_NAME, Map.of("amount", 500));
        assertEquals(1, dispatcher.invocationCount(),
                "a rule REMOVED by a reload must stop firing -- the app must not keep executing "
                        + "business logic the operator believes they deleted");
    }

    @Test
    void anEditedConditionUsesTheNewThreshold() {
        RecordingCapabilityDispatcher dispatcher = new RecordingCapabilityDispatcher();
        KernelRunner kernelRunner = kernelRunner(new CountingEventBus());
        CompiledModel lowThreshold = modelWithOrchestration(
                orchestration("Notify", "$event.amount > 100", EVENT_NAME));
        GeneratedCrudRuntimeSupport support = support(() -> lowThreshold, kernelRunner, dispatcher);

        support.reloadOrchestrationSubscribers(modelWithOrchestration(
                orchestration("Notify", "$event.amount > 1000", EVENT_NAME)));

        kernelRunner.publishEvent(EVENT_NAME, Map.of("amount", 500));
        assertEquals(0, dispatcher.invocationCount(),
                "amount=500 must NOT fire the NEW threshold (>1000) -- if this fires, the "
                        + "subscriber is still running the pre-reload condition (>100)");
    }

    @Test
    void reloadDoesNotDoubleFire() {
        RecordingCapabilityDispatcher dispatcher = new RecordingCapabilityDispatcher();
        CountingEventBus eventBus = new CountingEventBus();
        KernelRunner kernelRunner = kernelRunner(eventBus);
        CompiledModel model = modelWithOrchestration(orchestration("Notify", null, EVENT_NAME));
        GeneratedCrudRuntimeSupport support = support(() -> model, kernelRunner, dispatcher);

        assertEquals(1, eventBus.subscriberCount(EVENT_NAME),
                "construction must register exactly one subscriber");

        support.reloadOrchestrationSubscribers(model);
        support.reloadOrchestrationSubscribers(model);

        assertEquals(1, eventBus.subscriberCount(EVENT_NAME),
                "two reloads against an unchanged model must still leave exactly one live "
                        + "subscriber -- a reload that does not truly CLOSE its prior subscription "
                        + "(the AutoCloseable KernelRunner.subscribeEvent returns) would double- and "
                        + "triple-fire every future event");

        kernelRunner.publishEvent(EVENT_NAME, Map.of("amount", 500));
        assertEquals(1, dispatcher.invocationCount());
    }

    /**
     * REG-286: a rule with one action this runtime cannot build (a create with no EntityManager) used
     * to be dropped whole with no log, error or signal -- indistinguishable from a rule waiting for
     * its event. It is still not registered (running only its buildable actions would be a different
     * rule), but the refusal is reported at SEVERE naming the rule and the missing piece.
     */
    @Test
    void aRuleWithAnUnbuildableActionIsReportedNotSilentlyDropped() {
        RecordingCapabilityDispatcher dispatcher = new RecordingCapabilityDispatcher();
        KernelRunner kernelRunner = kernelRunner(new CountingEventBus());
        CompiledModel model = modelWithOrchestration(new CompiledOrchestration(
                "NotifyAndRecord",
                null,
                new CompiledOrchestrationTrigger("event", EVENT_NAME),
                List.of(
                        new CompiledOrchestrationAction("callCapability", null, "notifyOps", "send", Map.of()),
                        new CompiledOrchestrationAction("create", "AuditEntry", Map.of("note", "$event.amount")))));

        List<LogRecord> severe = captureSevere(() -> support(() -> model, kernelRunner, dispatcher));

        assertEquals(1, severe.size(), "the unbuildable rule must be reported exactly once");
        String message = severe.get(0).getMessage();
        assertTrue(message.contains("NotifyAndRecord") && message.contains("EntityManager"), message);
        kernelRunner.publishEvent(EVENT_NAME, Map.of("amount", 500));
        assertEquals(0, dispatcher.invocationCount(), "a refused rule runs none of its actions");
    }

    /**
     * REG-286: a condition that does not decide to a boolean used to fall back to a legacy ==/!=
     * substring matcher whose guess ("true" text is truthy) fired the rule. It is now reported and the
     * rule does not run.
     */
    @Test
    void anUndecidableConditionIsReportedNotGuessed() {
        RecordingCapabilityDispatcher dispatcher = new RecordingCapabilityDispatcher();
        KernelRunner kernelRunner = kernelRunner(new CountingEventBus());
        CompiledModel model = modelWithOrchestration(orchestration("Notify", "$event.approved", EVENT_NAME));
        support(() -> model, kernelRunner, dispatcher);

        List<LogRecord> severe = captureSevere(() -> kernelRunner.publishEvent(EVENT_NAME, Map.of("approved", "true")));

        assertEquals(0, dispatcher.invocationCount(), "a string \"true\" is not a boolean; the rule must not be guessed into running");
        assertEquals(1, severe.size());
        assertTrue(severe.get(0).getMessage().contains("Notify"), severe.get(0).getMessage());
    }

    private static List<LogRecord> captureSevere(Runnable action) {
        Logger logger = Logger.getLogger(GeneratedCrudRuntimeSupport.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.SEVERE) {
                    records.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    private static GeneratedCrudRuntimeSupport support(
            Supplier<CompiledModel> modelSupplier,
            KernelRunner kernelRunner,
            CapabilityDispatcher capabilityDispatcher
    ) {
        return new GeneratedCrudRuntimeSupport(
                modelSupplier,
                kernelRunner,
                null,
                capabilityDispatcher,
                null,
                null,
                new SystemRuntimeClock(),
                new InMemoryOrchestrationExecutionRegistry(),
                noopRuntimeInvariantEngineFactory(),
                AuditLogStore.noop(),
                PermissionEvaluator.allowAll(),
                IdempotencyStore.noop()
        );
    }

    private static CompiledModel modelWithNoRules() {
        return new CompiledModel("demo", "1.0.0", "v1", Map.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());
    }

    private static CompiledModel modelWithOrchestration(CompiledOrchestration orchestration) {
        return new CompiledModel("demo", "1.0.0", "v1", Map.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(orchestration));
    }

    private static CompiledOrchestration orchestration(String name, String condition, String eventName) {
        return new CompiledOrchestration(
                name,
                condition,
                new CompiledOrchestrationTrigger("event", eventName),
                new CompiledOrchestrationAction("callCapability", null, "notifyOps", "send", Map.of())
        );
    }

    private static RuntimeInvariantEngineFactory noopRuntimeInvariantEngineFactory() {
        return (uniqueValueLookup, conflictLookup) -> new InvariantEngine() {
            @Override
            public List<String> evaluate(String entityName, Object payload) {
                return List.of();
            }
        };
    }

    private static KernelRunner kernelRunner(EventBus eventBus) {
        return new KernelRunner(
                eventBus,
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }
        );
    }

    private static final class RecordingCapabilityDispatcher implements CapabilityDispatcher {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
            invocations.incrementAndGet();
            return CapabilityResult.success(Map.of());
        }

        int invocationCount() {
            return invocations.get();
        }
    }

    /**
     * A minimal, real (non-no-op) EventBus that also exposes {@link #subscriberCount(String)} --
     * needed only by {@link #reloadDoesNotDoubleFire()}, which must observe the subscription set
     * itself. Every other test here observes behavior through {@link RecordingCapabilityDispatcher}
     * instead, deliberately: {@code GeneratedCrudRuntimeSupport}'s own per-event idempotency guard
     * (keyed by orchestration name + event id, not by subscriber identity) already collapses a
     * duplicate delivery of the SAME published event down to one capability invocation, so it
     * cannot distinguish "one subscriber" from "several stale subscribers all firing on the same
     * event" -- only counting live subscribers across reloads can.
     *
     * <p>Also implements {@link EventStore}: {@code handleEventOrchestration}'s own bookkeeping
     * (an {@code OrchestrationStarted}/{@code OrchestrationCompleted} event around every firing)
     * goes through {@code KernelRunner.publishExternalEvent}, which throws {@code
     * IllegalStateException} outright when {@code eventStore == null} -- {@code KernelRunner}'s
     * 2-arg constructor sets it from {@code eventBus instanceof EventStore}, so any orchestration
     * rule that actually fires needs an EventBus that is also a (possibly no-op) EventStore.
     */
    private static final class CountingEventBus implements EventBus, EventStore {
        private final Map<String, List<EventHandler>> handlersByTopic = new LinkedHashMap<>();

        @Override
        public synchronized void publish(EventEnvelope event) {
            for (EventHandler handler : List.copyOf(handlersByTopic.getOrDefault(event.eventName(), List.of()))) {
                handler.onEvent(event);
            }
        }

        @Override
        public synchronized AutoCloseable subscribe(String eventName, EventHandler handler) {
            handlersByTopic.computeIfAbsent(eventName, ignored -> new ArrayList<>()).add(handler);
            return () -> removeHandler(eventName, handler);
        }

        private synchronized void removeHandler(String eventName, EventHandler handler) {
            List<EventHandler> handlers = handlersByTopic.get(eventName);
            if (handlers != null) {
                handlers.remove(handler);
            }
        }

        synchronized int subscriberCount(String eventName) {
            return handlersByTopic.getOrDefault(eventName, List.of()).size();
        }

        @Override
        public void append(EventEnvelope event) {
            // Bookkeeping events (OrchestrationStarted/Completed/...) are irrelevant to these
            // tests -- only the orchestration ACTION's effect (RecordingCapabilityDispatcher) and
            // the live subscriber count are asserted on.
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
