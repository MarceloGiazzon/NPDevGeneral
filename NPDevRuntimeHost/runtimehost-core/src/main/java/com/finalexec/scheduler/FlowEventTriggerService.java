package com.finalexec.scheduler;

import com.finalexec.config.DefaultTenant;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowTrigger;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.expr.ResolverScope;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.events.EventEnvelope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.npdev.runtime.support.crud.scheduling.EventSubjectSupport.resolveSubjectId;
import static com.npdev.runtime.support.crud.scheduling.EventSubjectSupport.serializePayloadForIdempotency;
import static com.npdev.runtime.support.crud.valuecoercion.ValueCoercionSupport.readPayloadValue;

/**
 * FORM P3-T5c: runs every flow that declares {@code trigger: {kind: event, event: X, condition: ...}}
 * whenever event {@code X} is published -- the flow-side counterpart of an orchestration rule, with
 * the same event-condition grammar ({@code $event}, {@code $event.<path>}, a bare payload field).
 *
 * <p><b>Who runs it.</b> {@link ExecutionContext#system} for the event's tenant (the home tenant when
 * the envelope carries none or the reserved {@code default} sentinel) -- exactly what the cron
 * scheduler and the webhook {@code startFlow} door do, so the flow's own {@code runAs}/{@code expose}
 * rules decide nothing about authorization here: an event is not an HTTP caller.</p>
 *
 * <p><b>Double firing (decision, 2026-11-15).</b> An orchestration rule and a trigger flow that name
 * the same event are two independent subscriptions: both fire, in subscription order. Nothing
 * de-duplicates across the two kinds on purpose -- they are different declarations by the author, and
 * silently suppressing one would be the quiet-corruption failure the platform avoids elsewhere.</p>
 *
 * <p><b>Safety.</b> The in-process event bus is synchronous: a handler that throws would propagate into
 * whoever published the event (a CRUD write). Every failure is therefore caught and logged here. The
 * same synchronicity makes a flow that emits the event that triggers it a recursion, so a per-thread
 * depth counter stops a chain at {@link #MAX_DEPTH}. Exactly-once is enforced by an in-memory claim set
 * with the two keys an orchestration rule uses -- the event id, and the "effective" key (event name +
 * correlation id + subject) that catches the same logical event re-published under a new id. Like the
 * orchestration registry it is per JVM, not cluster-wide.</p>
 *
 * <p><b>Release on failure.</b> A run that did not finish OK (a failed result, a waiting flow, a throw)
 * releases both claims, so a redelivery of the event can try again -- exactly what an orchestration rule
 * does when one of its actions fails. A run that finished OK keeps them, so a duplicate stays suppressed.
 * Side effects of a half-done attempt are not undone; a step that must not repeat on retry declares
 * {@code ifExists: skip} (createConcept) or a {@code dedupeKey} (scheduleEvent).</p>
 *
 * <p><b>Reload.</b> A {@link ModelHolder.ModelReloadListener} drops every subscription and resubscribes
 * from the new model; it runs under the holder's write lock, so it only (un)subscribes and never
 * executes a flow.</p>
 */
@Component
@ConditionalOnProperty(name = "npdev.flowEventTriggers.enabled", havingValue = "true", matchIfMissing = true)
public class FlowEventTriggerService {

    private static final Logger LOG = Logger.getLogger(FlowEventTriggerService.class.getName());

    /** Longest synchronous trigger chain (a flow whose own events trigger flows ...) before it is cut. */
    static final int MAX_DEPTH = 8;
    private static final int MAX_CLAIMS = 10_000;
    private static final String UNSET_TENANT_SENTINEL = "default";
    static final String TRIGGER_KIND_EVENT = "event";

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private final ModelHolder modelHolder;
    private final KernelRunner kernelRunner;
    private final List<AutoCloseable> subscriptions = new ArrayList<>();
    private final Set<String> claims = Collections.synchronizedSet(new LinkedHashSet<>());

    public FlowEventTriggerService(ModelHolder modelHolder, KernelRunner kernelRunner) {
        this.modelHolder = modelHolder;
        this.kernelRunner = kernelRunner;
        if (modelHolder != null) {
            modelHolder.addReloadListener((before, after) -> resubscribe());
        }
    }

    @PostConstruct
    void start() {
        resubscribe();
    }

    @PreDestroy
    void stop() {
        synchronized (subscriptions) {
            closeAllLocked();
        }
    }

    /** Number of live subscriptions; for tests and diagnostics. */
    int subscriptionCount() {
        synchronized (subscriptions) {
            return subscriptions.size();
        }
    }

    private void resubscribe() {
        synchronized (subscriptions) {
            closeAllLocked();
            for (CompiledFlow flow : modelHolder.get().getFlows()) {
                CompiledFlowTrigger trigger = flow.getTrigger();
                if (trigger == null || !TRIGGER_KIND_EVENT.equals(trigger.kind())
                        || trigger.event() == null || trigger.event().isBlank()) {
                    continue;
                }
                String flowName = flow.getName();
                String condition = trigger.condition();
                try {
                    subscriptions.add(kernelRunner.subscribeEvent(
                            trigger.event().trim(), envelope -> fire(flowName, condition, envelope)));
                } catch (Exception failure) {
                    LOG.log(Level.WARNING, "Failed to subscribe trigger flow " + flowName
                            + " to event " + trigger.event(), failure);
                }
            }
        }
    }

    private void closeAllLocked() {
        for (AutoCloseable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception failure) {
                LOG.log(Level.WARNING, "Failed to close a trigger-flow subscription", failure);
            }
        }
        subscriptions.clear();
    }

    /** Package-visible so tests can drive one delivery without a bus. Never throws. */
    void fire(String flowName, String condition, EventEnvelope envelope) {
        if (envelope == null) {
            return;
        }
        int depth = DEPTH.get();
        if (depth >= MAX_DEPTH) {
            LOG.warning("Trigger flow " + flowName + " not started for event " + envelope.eventName()
                    + ": trigger chain deeper than " + MAX_DEPTH + " (a flow that re-emits its own trigger?)");
            return;
        }
        DEPTH.set(depth + 1);
        try {
            Map<String, Object> payload = envelope.payload() == null ? Map.of() : envelope.payload();
            if (!conditionHolds(flowName, condition, payload)) {
                return;
            }
            List<String> claimedKeys = claim(flowName, envelope, payload);
            if (claimedKeys == null) {
                LOG.fine(() -> "Trigger flow " + flowName + " skipped duplicate delivery of event "
                        + envelope.eventId());
                return;
            }
            boolean finishedOk = false;
            try {
                String tenant = tenantFor(envelope);
                ExecutionResult result = kernelRunner.execute(
                        flowName, new LinkedHashMap<>(payload), ExecutionContext.system(tenant));
                finishedOk = result != null && result.getStatus() == ExecutionStatus.OK;
                if (!finishedOk) {
                    LOG.warning("Trigger flow " + flowName + " (tenant " + tenant + ", event "
                            + envelope.eventName() + ") finished "
                            + (result == null ? "null_result" : result.getStatus() + " " + result.getError())
                            + " -- claim released, a redelivery may retry");
                }
            } finally {
                if (!finishedOk) {
                    release(claimedKeys);
                }
            }
        } catch (Throwable failure) {
            // The bus is synchronous: nothing may escape into the publisher of the event.
            LOG.log(Level.WARNING, "Trigger flow " + flowName + " threw for event " + envelope.eventName(), failure);
        } finally {
            DEPTH.set(depth);
        }
    }

    private boolean conditionHolds(String flowName, String condition, Map<String, Object> payload) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String trimmed = condition.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        try {
            return ComputedExpression.evaluateBoolean(
                    trimmed, new ResolverScope(token -> resolveEventName(token, payload)));
        } catch (RuntimeException failure) {
            // A condition that does not decide to a boolean must not run the flow: guessing is worse.
            LOG.log(Level.WARNING, "Trigger flow " + flowName + " condition '" + trimmed
                    + "' could not be evaluated -- flow not started", failure);
            return false;
        }
    }

    /**
     * The claim keys for one delivery: {@code event:<flow>:<eventId>} and, when the envelope carries a
     * correlation id, {@code effective:<flow>:<eventName>:<correlation>:<subject>} -- the same two an
     * orchestration rule builds. Returns the acquired keys, or {@code null} when either one was already
     * held (a duplicate delivery); a partial acquisition is rolled back.
     */
    private List<String> claim(String flowName, EventEnvelope envelope, Map<String, Object> payload) {
        List<String> keys = new ArrayList<>();
        String eventId = envelope.eventId();
        if (eventId != null && !eventId.isBlank()) {
            keys.add("event:" + flowName + ":" + eventId.trim());
        }
        String correlationId = envelope.correlationId();
        if (correlationId != null && !correlationId.isBlank()) {
            String subject = resolveSubjectId(payload);
            if (subject == null || subject.isBlank()) {
                subject = serializePayloadForIdempotency(payload);
            }
            keys.add("effective:" + flowName + ":" + envelope.eventName() + ":"
                    + correlationId.trim() + ":" + subject.trim());
        }
        List<String> acquired = new ArrayList<>();
        synchronized (claims) {
            for (String key : keys) {
                if (!claims.add(key)) {
                    claims.removeAll(acquired);
                    return null;
                }
                acquired.add(key);
            }
            while (claims.size() > MAX_CLAIMS) {
                var oldest = claims.iterator();
                oldest.next();
                oldest.remove();
            }
        }
        return acquired;
    }

    private void release(List<String> keys) {
        synchronized (claims) {
            claims.removeAll(keys);
        }
    }

    private static String tenantFor(EventEnvelope envelope) {
        String tenant = envelope.tenantId();
        if (tenant == null || tenant.isBlank() || UNSET_TENANT_SENTINEL.equals(tenant)) {
            return DefaultTenant.id();
        }
        return tenant.trim();
    }

    private static Object resolveEventName(String token, Map<String, Object> payload) {
        if ("$event".equals(token)) {
            return payload;
        }
        if (token.startsWith("$event.")) {
            Object current = payload;
            for (String segment : token.substring("$event.".length()).split("\\.")) {
                if (segment.isBlank() || !(current instanceof Map<?, ?> map)) {
                    return null;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                current = readPayloadValue(typed, segment);
                if (current == null) {
                    return null;
                }
            }
            return current;
        }
        return readPayloadValue(payload, token);
    }
}
