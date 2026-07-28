package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code WAITING_EVENT} rehydration + correlation-ownership machinery, split out of {@link
 * KernelRunner} verbatim -- no behavior change. This is the durable-resume path: a flow instance
 * parked on {@code AWAIT_EVENT} survives a process restart in {@link
 * com.npdev.kernel.ports.FlowInstanceStore}, and every method here is part of how a later event (or
 * a scheduled sweep via {@link #resumeAllWaitingExecutions}) finds and rehydrates it -- including
 * after the JVM that parked it is gone. {@link KernelRunner} (the dispatcher) calls straight into
 * this sibling class's statics, passing itself as the {@code runner} argument for the collaborators
 * (event store, flow instance store, idempotency store, {@link KernelRunner#resumeExecution}, the
 * shared correlation/tenant/payload-matching predicates) this logic needs. This file deliberately
 * stays a flat sibling in {@code com.npdev.kernel}, not a subpackage, for the same reason {@code
 * FlowStateCodec}/{@code CompensationRunner}/2.B.4's {@code TableRenamePass} documented: the
 * collaborators here are package-private, and Java sub-packages get no special access to them.
 */
final class ResumeCoordinator {

    private ResumeCoordinator() {
    }

    private static final long RESUME_BASE_DELAY_MS = 5_000L;
    private static final long RESUME_MAX_DELAY_MS = 300_000L;
    private static final int RESUME_MAX_ATTEMPTS = 20;
    private static final String FLOW_RESUME_IDEMPOTENCY_CAPABILITY = "__flow_resume";

    static FlowEngine.ResumeOutcome resumeWaitingExecutionsFor(
            KernelRunner runner,
            EventEnvelope envelope,
            String currentExecutionId,
            String lookupCorrelationId,
            ExecutionContext resumeExecutionContext
    ) {
        if (envelope == null) {
            return FlowEngine.ResumeOutcome.noMatch();
        }
        long now = KernelRunner.nowEpochMillis();
        List<FlowInstance> waitingInstances = collectWaitingCandidates(runner, lookupCorrelationId, envelope.eventName());
        if (waitingInstances.isEmpty()) {
            return FlowEngine.ResumeOutcome.noMatch();
        }

        int matchedWaiters = 0;
        int resumedWaiters = 0;
        List<String> resumedExecutionIds = new ArrayList<>();
        for (FlowInstance instance : waitingInstances) {
            if (instance == null) {
                continue;
            }
            if (currentExecutionId != null && currentExecutionId.equals(instance.executionId())) {
                continue;
            }
            if (!instance.isResumeEligible(now)) {
                continue;
            }
            if (!matchesWaitingResumeCriteria(runner, instance, envelope)) {
                continue;
            }
            matchedWaiters++;
            try {
                ExecutionResult result = runner.resumeExecution(
                        instance.executionId(),
                        resumeExecutionContext == null ? ExecutionContext.anonymous() : resumeExecutionContext
                );
                if (result.getStatus() == ExecutionStatus.WAITING_EVENT) {
                    // Event-driven mismatches must be a no-op. Backoff is only for scheduled scans.
                    continue;
                }
                resumedWaiters++;
                resumedExecutionIds.add(instance.executionId());
            } catch (RuntimeException runtimeException) {
                FlowInstance latest = runner.flowInstanceStore.findByExecutionId(instance.executionId()).orElse(instance);
                persistResumeBackoff(
                        runner,
                        latest,
                        "exception:" + runtimeException.getClass().getSimpleName(),
                        KernelRunner.nowEpochMillis()
                );
            }
        }

        if (matchedWaiters == 0 && resumedWaiters == 0) {
            return FlowEngine.ResumeOutcome.noMatch();
        }
        return new FlowEngine.ResumeOutcome(matchedWaiters, resumedWaiters, resumedExecutionIds);
    }

    private static List<FlowInstance> collectWaitingCandidates(KernelRunner runner, String correlationId, String eventName) {
        Map<String, FlowInstance> byExecutionId = new LinkedHashMap<>();
        String normalizedCorrelationId = KernelRunner.normalizeCorrelationId(correlationId);
        if (normalizedCorrelationId != null) {
            for (FlowInstance instance : runner.flowInstanceStore.findWaitingByCorrelation(normalizedCorrelationId)) {
                if (instance != null) {
                    byExecutionId.put(instance.executionId(), instance);
                }
            }
        }
        if (eventName != null && !eventName.isBlank()) {
            for (FlowInstance instance : runner.flowInstanceStore.findWaitingByEvent(eventName)) {
                if (instance != null) {
                    byExecutionId.put(instance.executionId(), instance);
                }
            }
        }
        if (byExecutionId.isEmpty()) {
            return List.of();
        }
        return byExecutionId.values().stream()
                .sorted(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).thenComparing(FlowInstance::executionId))
                .toList();
    }

    private static boolean matchesWaitingResumeCriteria(KernelRunner runner, FlowInstance waitingInstance, EventEnvelope envelope) {
        if (waitingInstance == null || envelope == null) {
            return false;
        }
        if (waitingInstance.status() != FlowInstanceStatus.WAITING_EVENT) {
            return false;
        }
        if (!KernelRunner.sameTenant(envelope.tenantId(), waitingInstance.tenantId())) {
            return false;
        }
        KernelRunner.WaitCriteria waitCriteria = resolveWaitCriteria(waitingInstance);
        if (waitCriteria.awaitEventName() == null || waitCriteria.awaitEventName().isBlank()) {
            return false;
        }
        if (!waitCriteria.awaitEventName().equals(envelope.eventName())) {
            return false;
        }
        if (waitCriteria.stepIndex() >= 0 && waitingInstance.currentStepIndex() != waitCriteria.stepIndex()) {
            return false;
        }
        if (waitCriteria.matchCorrelation() && !KernelRunner.matchesCorrelation(envelope, waitingInstance.correlationId())) {
            return false;
        }
        if (!KernelRunner.matchesAwaitPayload(
                waitCriteria.payloadMatchRefs(),
                envelope,
                waitingInstance.state(),
                waitingInstance.state().get("input")
        )) {
            return false;
        }
        return !isResumeEventAlreadyProcessed(
                runner,
                waitingInstance.tenantId(),
                waitingInstance.executionId(),
                envelope.eventId()
        );
    }

    static Optional<EventEnvelope> findAwaitedEventForInstance(
            KernelRunner runner,
            FlowInstance waitingInstance,
            KernelRunner.WaitCriteria waitCriteria,
            boolean markProcessed
    ) {
        if (waitingInstance == null) {
            return Optional.empty();
        }
        return findAwaitedEvent(
                runner,
                waitCriteria,
                waitingInstance.executionId(),
                waitingInstance.correlationId(),
                waitingInstance.tenantId(),
                waitingInstance.state(),
                waitingInstance.state().get("input"),
                markProcessed
        );
    }

    static Optional<EventEnvelope> findAwaitedEvent(
            KernelRunner runner,
            KernelRunner.WaitCriteria waitCriteria,
            String executionId,
            String correlationId,
            String tenantId,
            Map<String, Object> state,
            Object input,
            boolean markProcessed
    ) {
        if (runner.eventStore == null || waitCriteria == null) {
            return Optional.empty();
        }
        String eventName = waitCriteria.awaitEventName();
        if (eventName == null || eventName.isBlank()) {
            return Optional.empty();
        }
        String effectiveTenantId = KernelRunner.normalizeTenantOrDefault(tenantId);
        List<EventEnvelope> candidates = waitCriteria.matchCorrelation()
                ? runner.eventStore.read(eventName, correlationId, effectiveTenantId)
                : runner.eventStore.readByEventName(eventName, effectiveTenantId);
        Map<String, Object> effectiveState = state == null ? Map.of() : state;
        for (EventEnvelope candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            if (waitCriteria.matchCorrelation() && !KernelRunner.matchesCorrelation(candidate, correlationId)) {
                continue;
            }
            if (!KernelRunner.matchesAwaitPayload(waitCriteria.payloadMatchRefs(), candidate, effectiveState, input)) {
                continue;
            }
            if (isResumeEventAlreadyProcessed(runner, tenantId, executionId, candidate.eventId())) {
                continue;
            }
            if (markProcessed) {
                markResumeEventProcessed(runner, tenantId, executionId, candidate.eventId());
            }
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static boolean isResumeEventAlreadyProcessed(KernelRunner runner, String tenantId, String executionId, String eventId) {
        if (eventId == null || eventId.isBlank() || executionId == null || executionId.isBlank()) {
            return false;
        }
        return runner.idempotencyStore.find(
                KernelRunner.normalizeTenantOrDefault(tenantId),
                FLOW_RESUME_IDEMPOTENCY_CAPABILITY,
                executionId,
                eventId
        ).isPresent();
    }

    private static void markResumeEventProcessed(KernelRunner runner, String tenantId, String executionId, String eventId) {
        if (eventId == null || eventId.isBlank() || executionId == null || executionId.isBlank()) {
            return;
        }
        if (isResumeEventAlreadyProcessed(runner, tenantId, executionId, eventId)) {
            return;
        }
        runner.idempotencyStore.saveSuccess(
                KernelRunner.normalizeTenantOrDefault(tenantId),
                FLOW_RESUME_IDEMPOTENCY_CAPABILITY,
                executionId,
                eventId,
                "{\"status\":\"PROCESSED\"}",
                KernelRunner.nowEpochMillis()
        );
    }

    static KernelRunner.WaitCriteria resolveWaitCriteria(FlowInstance waitingInstance) {
        if (waitingInstance == null) {
            return new KernelRunner.WaitCriteria(null, true, Map.of(), -1, "awaitedEvent");
        }
        Object rawWaitState = waitingInstance.state().get(FlowStateCodec.AWAIT_STATE_KEY);
        if (rawWaitState instanceof Map<?, ?> waitMap) {
            String eventName = Objects.toString(waitMap.get(FlowStateCodec.AWAIT_FIELD_EVENT_NAME), waitingInstance.waitingForEventName());
            boolean matchCorrelation = FlowStateCodec.parseBoolean(waitMap.get(FlowStateCodec.AWAIT_FIELD_MATCH_CORRELATION), true);
            int stepIndex = FlowStateCodec.parseInt(waitMap.get(FlowStateCodec.AWAIT_FIELD_STEP_INDEX), waitingInstance.currentStepIndex());
            String awaitRef = FlowStateCodec.normalizeAwaitRef(Objects.toString(waitMap.get(FlowStateCodec.AWAIT_FIELD_AWAIT_REF), "awaitedEvent"));
            return new KernelRunner.WaitCriteria(
                    eventName,
                    matchCorrelation,
                    FlowStateCodec.parseStringMap(waitMap.get(FlowStateCodec.AWAIT_FIELD_PAYLOAD_MATCH_REFS)),
                    stepIndex,
                    awaitRef
            );
        }
        return new KernelRunner.WaitCriteria(
                waitingInstance.waitingForEventName(),
                true,
                Map.of(),
                waitingInstance.currentStepIndex(),
                "awaitedEvent"
        );
    }

    private static FlowInstance applyResumeBackoff(FlowInstance instance, String errorCode, long nowEpochMs) {
        if (instance == null) {
            throw new IllegalArgumentException("instance must be non-null");
        }
        long delayMs = resumeDelayMillis(instance.resumeAttemptCount() + 1);
        long nextEligible = nowEpochMs + delayMs;
        return instance.markResumeFailure(errorCode, nowEpochMs, nextEligible, RESUME_MAX_ATTEMPTS);
    }

    private static FlowInstance persistResumeBackoff(KernelRunner runner, FlowInstance instance, String errorCode, long nowEpochMs) {
        FlowInstance updated = applyResumeBackoff(instance, errorCode, nowEpochMs);
        runner.flowInstanceStore.update(updated);
        runner.emitOperationalFailureEvent(updated);
        return updated;
    }

    private static long resumeDelayMillis(int nextAttempt) {
        int exponent = Math.max(0, nextAttempt - 1);
        long multiplier;
        if (exponent >= 20) {
            multiplier = 1L << 20;
        } else {
            multiplier = 1L << exponent;
        }
        long computed = RESUME_BASE_DELAY_MS * multiplier;
        if (computed < 0) {
            return RESUME_MAX_DELAY_MS;
        }
        return Math.min(computed, RESUME_MAX_DELAY_MS);
    }

    static int resumeAllWaitingExecutions(KernelRunner runner, int limit) {
        if (runner.eventStore == null) {
            return 0;
        }
        int batchSize = limit <= 0 ? 500 : limit;
        long now = KernelRunner.nowEpochMillis();
        List<FlowInstance> waitingSnapshot = runner.flowInstanceStore.findAllWaiting(batchSize * 4);
        if (waitingSnapshot == null || waitingSnapshot.isEmpty()) {
            return 0;
        }

        Set<String> tenants = new LinkedHashSet<>();
        for (FlowInstance instance : waitingSnapshot) {
            if (instance == null) {
                continue;
            }
            tenants.add(KernelRunner.normalizeTenantOrDefault(instance.tenantId()));
        }
        if (tenants.isEmpty()) {
            return 0;
        }

        List<FlowInstance> eligible = new ArrayList<>();
        for (String tenant : tenants) {
            eligible.addAll(runner.flowInstanceStore.findWaitingEligibleToResume(tenant, now, batchSize));
        }
        eligible = eligible.stream()
                .sorted(Comparator
                        .comparingLong((FlowInstance instance) -> instance.nextEligibleResumeAtEpochMs() == null
                                ? 0L
                                : instance.nextEligibleResumeAtEpochMs())
                        .thenComparing(Comparator.comparingLong(FlowInstance::updatedAtEpochMs).reversed())
                        .thenComparing(FlowInstance::executionId))
                .limit(batchSize)
                .toList();
        if (eligible.isEmpty()) {
            return 0;
        }

        int resumedCount = 0;
        for (FlowInstance waitingInstance : eligible) {
            if (waitingInstance == null || waitingInstance.status() != FlowInstanceStatus.WAITING_EVENT) {
                continue;
            }
            KernelRunner.WaitCriteria waitCriteria = resolveWaitCriteria(waitingInstance);
            if (waitCriteria.awaitEventName() == null || waitCriteria.awaitEventName().isBlank()) {
                FlowInstance latest = runner.flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                        .orElse(waitingInstance);
                persistResumeBackoff(runner, latest, "missing_event", KernelRunner.nowEpochMillis());
                continue;
            }

            if (findAwaitedEventForInstance(runner, waitingInstance, waitCriteria, false).isEmpty()) {
                FlowInstance latest = runner.flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                        .orElse(waitingInstance);
                persistResumeBackoff(runner, latest, "missing_event", KernelRunner.nowEpochMillis());
                continue;
            }

            try {
                // Resume under the waiting instance's own tenant/actor. The awaited event is
                // tenant-scoped, so resuming with an anonymous context would look it up under the
                // default tenant and never match a tenant-scoped event, leaving the flow stuck.
                ExecutionResult result = runner.resumeExecution(
                        waitingInstance.executionId(),
                        ExecutionContext.of(waitingInstance.tenantId(), waitingInstance.actorId())
                );
                if (result.getStatus() == ExecutionStatus.WAITING_EVENT) {
                    FlowInstance latest = runner.flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                            .orElse(waitingInstance);
                    persistResumeBackoff(runner, latest, "missing_event", KernelRunner.nowEpochMillis());
                } else {
                    resumedCount++;
                }
            } catch (RuntimeException runtimeException) {
                FlowInstance latest = runner.flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                        .orElse(waitingInstance);
                persistResumeBackoff(
                        runner,
                        latest,
                        "exception:" + runtimeException.getClass().getSimpleName(),
                        KernelRunner.nowEpochMillis()
                );
            }
        }

        return resumedCount;
    }
}
