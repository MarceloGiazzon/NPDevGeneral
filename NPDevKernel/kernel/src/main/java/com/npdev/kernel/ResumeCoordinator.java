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

    /**
     * R2.1: the error code every "there is simply nothing to resume ON yet" branch records. Three
     * branches of {@link #resumeAllWaitingExecutions} feed it to {@link #persistResumeBackoff} --
     * the instance carries no wait criteria, the event store holds no candidate event, and the
     * resume re-parked still WAITING_EVENT -- and NONE of them is a failure of anything. They are
     * the ordinary state of a flow parked on an approval that has not happened.
     */
    private static final String QUIET_WAIT_ERROR_CODE = "missing_event";

    /**
     * R2.1 ("kill the ~75-minute STUCK ceiling"): the cap a quiet wait is measured against, i.e.
     * none. {@link FlowInstance#markResumeFailure} already treats a non-positive {@code maxAttempts}
     * as "never exhausted", so the quiet branches keep the exponential delay -- which is genuinely
     * useful, it backs a fruitless poll off to one sweep per {@link #RESUME_MAX_DELAY_MS} -- and
     * give up only the cap.
     * <p>
     * Before R2.1 the quiet branches shared {@link #RESUME_MAX_ATTEMPTS} with real failures, and
     * {@link #resumeDelayMillis}'s ladder (5s, doubling, capped at 300s) sums to 4_515s across 20
     * misses. So ~75 minutes of quiet drove ANY untouched instance to STUCK -- a terminal status
     * from which a later matching event can never revive it -- while docs/FLOWS.md advertises waits
     * that last days or weeks. A real {@code exception:*} out of {@code resumeExecution} still
     * counts against {@link #RESUME_MAX_ATTEMPTS} and still ends in STUCK.
     */
    private static final int RESUME_NO_ATTEMPT_CAP = 0;

    /**
     * R8c (RUN-2): one opaque id per JVM/classloader load, so every claim this process makes
     * against {@link com.npdev.kernel.ports.FlowInstanceStore#claimWaitingEligibleToResume} is
     * attributable to it. Never consulted for authorization -- purely a debugging/observability
     * label recorded alongside the lease, the same role {@code MigrationClaimStore}'s instanceId
     * plays for the (unrelated) schema-migration lock.
     */
    private static final String RESUMER_ID = java.util.UUID.randomUUID().toString();

    /**
     * R8c (RUN-2): how long a batch claim is held before another resumer may re-claim the same
     * rows. {@code ResumeSchedulerRunner}'s default poll interval is 2s ({@code
     * npdev.resume.pollMs}), so 30s is 15x that -- long enough that an ordinary resume attempt
     * (which itself starts a fresh {@code RESUME_BASE_DELAY_MS}-scaled backoff only on FAILURE, not
     * on success) finishes well inside the lease, short enough that a claimant which crashes
     * mid-resume does not leave its batch unresumable for long.
     */
    private static final long RESUME_CLAIM_LEASE_MS = 30_000L;

    static FlowEngine.ResumeOutcome resumeWaitingExecutionsFor(
            KernelRunner runner,
            EventEnvelope envelope,
            String currentExecutionId,
            String lookupCorrelationId
    ) {
        if (envelope == null) {
            return FlowEngine.ResumeOutcome.noMatch();
        }
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
            // R2.1: NO isResumeEligible check here. This is the synchronous event-ARRIVAL path
            // (publishExternalEvent / emitEvent / scheduleEvent), and the invariant the catch block
            // below already states -- "Backoff is only for scheduled scans" -- was being violated
            // one line above the match: an instance sitting in its 5s..300s scheduled-sweep backoff
            // was skipped even when the event that just arrived was exactly the one it waits for.
            // The event survives in the event store, so nothing was lost, but revival was deferred
            // by up to the current backoff for no reason. The eligibility check's other half
            // (status == WAITING_EVENT) is redundant here: matchesWaitingResumeCriteria asserts it,
            // and collectWaitingCandidates only ever returns waiting rows.
            if (!matchesWaitingResumeCriteria(runner, instance, envelope)) {
                continue;
            }
            matchedWaiters++;
            try {
                // REG-56: resume under the flow's OWN trust level, not the publisher's -- see
                // ExecutionContext#resuming for why. Using the publisher's context here either
                // denied a legitimate resume (publisher lacks a capability the flow needs) or
                // over-granted one (publisher happens to be an admin) depending on who published.
                ExecutionResult result = runner.resumeExecution(
                        instance.executionId(),
                        ExecutionContext.resuming(instance.tenantId(), instance.actorId())
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
        List<FlowStateCodec.ParallelAwaitSlot> parallelSlots = FlowStateCodec.resolveOutstandingParallelAwaitSlots(
                waitingInstance.executionId(), waitingInstance.state());
        if (!parallelSlots.isEmpty()) {
            return matchesAnyParallelAwaitSlot(runner, waitingInstance, envelope, parallelSlots);
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
        if (waitCriteria.matchCorrelation() && !KernelRunner.matchesCorrelation(envelope, waitingCorrelationId(waitingInstance))) {
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

    /** B15(B): the parallel-await counterpart of the single-slot matching above -- true the moment
     *  the incoming event satisfies ANY of the instance's currently-outstanding per-iteration
     *  slots (deliberately no {@code stepIndex} check here, unlike the single-slot path: every
     *  slot for one {@code parallelAwait} forEach shares the SAME nested trace index -- see
     *  {@link ParallelAwaitForEachStep}'s own javadoc -- so it carries no discriminating power;
     *  correlation is the only discriminator, exactly as B15(A) already established). */
    private static boolean matchesAnyParallelAwaitSlot(
            KernelRunner runner,
            FlowInstance waitingInstance,
            EventEnvelope envelope,
            List<FlowStateCodec.ParallelAwaitSlot> slots
    ) {
        for (FlowStateCodec.ParallelAwaitSlot slot : slots) {
            KernelRunner.WaitCriteria criteria = slot.criteria();
            if (!criteria.awaitEventName().equals(envelope.eventName())) {
                continue;
            }
            if (criteria.matchCorrelation() && !KernelRunner.matchesCorrelation(envelope, slot.correlationId())) {
                continue;
            }
            if (!KernelRunner.matchesAwaitPayload(
                    criteria.payloadMatchRefs(), envelope, waitingInstance.state(), waitingInstance.state().get("input"))) {
                continue;
            }
            if (isResumeEventAlreadyProcessed(runner, waitingInstance.tenantId(), waitingInstance.executionId(), envelope.eventId())) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** B15(B): {@link #resumeAllWaitingExecutions}'s scheduled-sweep counterpart to {@link
     *  #matchesAnyParallelAwaitSlot} -- no specific incoming event to check against, so this asks
     *  the event store directly whether ANY outstanding slot already has a satisfying event
     *  sitting durably (mirroring {@link #findAwaitedEventForInstance}'s single-slot pre-check). */
    private static boolean hasAnyResolvableParallelAwaitSlot(
            KernelRunner runner,
            FlowInstance waitingInstance,
            List<FlowStateCodec.ParallelAwaitSlot> slots
    ) {
        for (FlowStateCodec.ParallelAwaitSlot slot : slots) {
            Optional<EventEnvelope> found = findAwaitedEvent(
                    runner,
                    slot.criteria(),
                    waitingInstance.executionId(),
                    slot.correlationId(),
                    waitingInstance.tenantId(),
                    waitingInstance.state(),
                    waitingInstance.state().get("input"),
                    false
            );
            if (found.isPresent()) {
                return true;
            }
        }
        return false;
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
                waitingCorrelationId(waitingInstance),
                waitingInstance.tenantId(),
                waitingInstance.state(),
                waitingInstance.state().get("input"),
                markProcessed
        );
    }

    /**
     * B15(A) (Move 16, docs/BOUNDARY_LIFT_ROADMAP.md): {@code FlowInstance.correlationId()} is
     * fixed for the instance's whole lifetime (every {@code markRunning}/{@code markWaiting}
     * transition passes it through unchanged), so it never reflects a per-iteration correlation id
     * a forEach-nested await writes into {@code state.correlationId} (see {@link ForEachStep}'s own
     * javadoc). {@code state} itself, unlike the row-level field, round-trips through persistence
     * in full on every checkpoint, so prefer it here and fall back to the row-level field for the
     * ordinary, non-forEach case where state was never given its own override.
     */
    private static String waitingCorrelationId(FlowInstance waitingInstance) {
        Object stateCorrelationId = waitingInstance.state().get("correlationId");
        return stateCorrelationId != null ? stateCorrelationId.toString() : waitingInstance.correlationId();
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
            return FlowStateCodec.parseWaitCriteria(waitMap, waitingInstance.waitingForEventName(), waitingInstance.currentStepIndex());
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
        boolean quietWait = QUIET_WAIT_ERROR_CODE.equals(errorCode);
        // R2.1: resumeAttemptCount tallies both kinds, so a real failure arriving after a long
        // quiet wait would find the counter already far past RESUME_MAX_ATTEMPTS and go STUCK on
        // its FIRST fault -- strictly worse than the 20 retries that path had before the cap was
        // lifted off quiet waits. Start a fresh streak whenever the kind changes over.
        FlowInstance streakBase = quietWait || !QUIET_WAIT_ERROR_CODE.equals(instance.lastResumeErrorCode())
                ? instance
                : instance.withResumeStreakReset();
        long delayMs = resumeDelayMillis(streakBase.resumeAttemptCount() + 1);
        long nextEligible = nowEpochMs + delayMs;
        return streakBase.markResumeFailure(
                errorCode,
                nowEpochMs,
                nextEligible,
                quietWait ? RESUME_NO_ATTEMPT_CAP : RESUME_MAX_ATTEMPTS
        );
    }

    private static FlowInstance persistResumeBackoff(KernelRunner runner, FlowInstance instance, String errorCode, long nowEpochMs) {
        FlowInstance updated = applyResumeBackoff(instance, errorCode, nowEpochMs);
        runner.flowInstanceStore.update(updated);
        runner.emitOperationalFailureEvent(updated);
        return updated;
    }

    /** R2.5: {@code true} once a timed await's persisted deadline has passed -- {@code false} for
     *  every untimed await (the vast majority) since {@link FlowStateCodec#awaitDeadlineEpochMs}
     *  returns {@code null} when the state blob carries no deadline field at all. */
    private static boolean isAwaitTimeoutExpired(Map<String, Object> state, long now) {
        Long deadlineEpochMs = FlowStateCodec.awaitDeadlineEpochMs(state);
        return deadlineEpochMs != null && now >= deadlineEpochMs;
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
            // R8c (RUN-2): claim, don't just read -- findWaitingEligibleToResume alone let two
            // instances of this scheduled sweep, running against the SAME database, both select and
            // then both resume the identical waiting instance. claimWaitingEligibleToResume's
            // default implementation is this exact call with no claiming (unchanged behaviour for
            // any FlowInstanceStore that hasn't opted in); JdbcFlowInstanceStore is the one
            // implementation that makes the claim real.
            eligible.addAll(runner.flowInstanceStore.claimWaitingEligibleToResume(
                    tenant, now, RESUME_CLAIM_LEASE_MS, RESUMER_ID, batchSize));
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
            List<FlowStateCodec.ParallelAwaitSlot> parallelSlots = FlowStateCodec.resolveOutstandingParallelAwaitSlots(
                    waitingInstance.executionId(), waitingInstance.state());
            boolean hasCandidateEvent;
            if (!parallelSlots.isEmpty()) {
                // B15(B): N outstanding per-iteration slots -- any ONE having a satisfying event
                // available is enough to attempt a resume (resumeExecution re-enters the step and
                // resolves whichever slots it can; still-outstanding ones simply re-park).
                hasCandidateEvent = hasAnyResolvableParallelAwaitSlot(runner, waitingInstance, parallelSlots);
            } else {
                KernelRunner.WaitCriteria waitCriteria = resolveWaitCriteria(waitingInstance);
                if (waitCriteria.awaitEventName() == null || waitCriteria.awaitEventName().isBlank()) {
                    FlowInstance latest = runner.flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                            .orElse(waitingInstance);
                    persistResumeBackoff(runner, latest, QUIET_WAIT_ERROR_CODE, KernelRunner.nowEpochMillis());
                    continue;
                }
                hasCandidateEvent = findAwaitedEventForInstance(runner, waitingInstance, waitCriteria, false).isPresent();
            }
            // R2.5 (durable await timeouts): a timed await with no candidate event yet is still
            // "quiet" in the ordinary sense, but if its deadline has already passed it must be
            // resumed anyway -- resumeExecution re-enters AwaitEventStep.execute, which is where
            // the actual deadline-vs-now comparison and onTimeout branch live (see that class).
            // FlowStateCodec.awaitDeadlineEpochMs reads the SAME _npdev.await state blob the
            // instance was parked with, so this is naturally a no-op (null) for every untimed await
            // and for a parallelAwait forEach (which uses a different, prefixed state key -- see
            // that method's own javadoc), leaving their existing quiet-backoff behavior untouched.
            boolean timeoutExpired = !hasCandidateEvent
                    && isAwaitTimeoutExpired(waitingInstance.state(), now);
            if (!hasCandidateEvent && !timeoutExpired) {
                FlowInstance latest = runner.flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                        .orElse(waitingInstance);
                persistResumeBackoff(runner, latest, QUIET_WAIT_ERROR_CODE, KernelRunner.nowEpochMillis());
                continue;
            }

            try {
                // Resume under the waiting instance's own tenant/actor, not a fresh default-role
                // context. The awaited event is tenant-scoped, so resuming with an anonymous
                // context would look it up under the default tenant and never match a
                // tenant-scoped event, leaving the flow stuck. REG-56: ExecutionContext#of always
                // defaults role to USER, which permanently denies any capability-gated resume
                // regardless of what role the flow was originally submitted under --
                // ExecutionContext#resuming grants the trusted resume-level role instead (see its
                // javadoc), while still keeping the instance's own actor for audit traceability.
                ExecutionResult result = runner.resumeExecution(
                        waitingInstance.executionId(),
                        ExecutionContext.resuming(waitingInstance.tenantId(), waitingInstance.actorId())
                );
                if (result.getStatus() == ExecutionStatus.WAITING_EVENT) {
                    FlowInstance latest = runner.flowInstanceStore.findByExecutionId(waitingInstance.executionId())
                            .orElse(waitingInstance);
                    persistResumeBackoff(runner, latest, QUIET_WAIT_ERROR_CODE, KernelRunner.nowEpochMillis());
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
