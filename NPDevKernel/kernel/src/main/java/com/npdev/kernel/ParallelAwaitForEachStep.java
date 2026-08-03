package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.procedures.ProcedureExecutionLimits;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * B15(B) (S6, docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)): N loop iterations each independently
 * awaiting their own event, genuinely outstanding at the same time -- the parallel counterpart to
 * {@link ForEachStep}'s sequential (one-at-a-time) {@code AWAIT_EVENT} handling from B15(A).
 * Dispatched from {@link ForEachStep#execute} when {@code step.isParallelAwait()}.
 *
 * <p><b>Deliberately scoped to a single-step loop body: exactly one {@code AWAIT_EVENT} step, no
 * steps before or after it.</b> This is not an arbitrary restriction -- it is what makes the
 * design tractable and provably correct without a second durable-state hazard class. B15(A)'s
 * sequential model re-runs an iteration's ENTIRE step list from scratch on every (re-)attempt
 * (nested {@code executeSteps} always starts at index 0), relying on non-await steps being safe to
 * re-execute. That is fine when only ONE iteration is ever in flight. For N iterations attempted
 * independently in one pass, a step before/after the await that mutates a NON-namespaced
 * {@code state} key would silently clobber across iterations (iteration i+1's pre-await step
 * overwriting a key iteration i already committed to, before iteration i's own await resolves and
 * the loop completes). Scoping to await-only sidesteps that hazard entirely: the only per-iteration
 * mutation is the resolved payload, and it is namespaced by iteration index (see {@link
 * FlowStateCodec#parallelAwaitPayloadKey}). A future lift that wants pre/post-await steps needs its
 * own design pass for that specific hazard -- not a mechanical extension of this one.
 *
 * <p><b>Storage decision (B0/B1): no new durable storage surface.</b> Every outstanding wait
 * descriptor and every resolved payload lives in {@code state} (the existing {@code FlowInstance}
 * JSON blob), namespaced per iteration -- see {@link FlowStateCodec}'s
 * {@code PARALLEL_AWAIT_STATE_KEY_PREFIX}/{@code PARALLEL_AWAIT_RESOLVED_KEY_PREFIX} javadoc for
 * the full reasoning (the roadmap's originally-costed "new {@code flow_instance_wait} table"
 * option turned out to buy nothing at the discovery layer: all N iterations of one parallel
 * forEach share the same declared await event name, so {@code FlowInstanceStore.findWaitingByEvent}
 * already finds the candidate instance via its existing indexed column). This is why B1 needed no
 * schema/adapter change and no migration: nothing new exists below the state-blob layer for an
 * existing in-flight {@code WAITING_EVENT} row to need converting.
 *
 * <p><b>Fan-in completion semantics (B3, decided and recorded here):</b>
 * <ul>
 *   <li><b>Join/barrier:</b> ALL N iterations must resolve before the step completes -- the natural
 *   "wait for every reply" reading. No partial/best-effort completion.</li>
 *   <li><b>Fail-fast vs. partial failure:</b> a genuine (non-waiting) failure from ANY iteration's
 *   await attempt fails the whole step immediately, matching {@link ForEachStep}'s existing
 *   sequential behavior for a real failure (as opposed to a still-waiting one). No mixed-result
 *   reporting is introduced.</li>
 *   <li><b>Unsatisfiable slot / timeout:</b> parks forever, subject to the SAME resume-eligibility
 *   backoff and eventual {@code STUCK} detection every single-slot {@code AWAIT_EVENT} already has
 *   today (which itself has no timeout concept). No new per-slot timeout subsystem is introduced by
 *   this lift -- that is an orthogonal, harder question (per-slot timeouts need their own design,
 *   e.g. a scheduled event) deliberately left for a future increment, exactly as the roadmap's own
 *   B15(B) write-up flagged it as open and unscoped.</li>
 * </ul>
 *
 * <p><b>Durability (B2/B4):</b> {@code awaitEvent()} marks a found event PROCESSED in the
 * idempotency store as a side effect of merely being called (see {@code KernelRunner#awaitEvent} →
 * {@code ResumeCoordinator#findAwaitedEvent(..., markProcessed=true)}). That means the instant
 * iteration i's event is consumed, a crash before its resolution is durably persisted would leave
 * it stuck WAITING forever (the event is already marked processed, so a re-query on resume finds
 * nothing). This class checkpoints ({@code progressRecorder.onStepCompleted}) immediately after
 * EACH iteration resolves, before attempting the next one -- N separate close-the-window
 * checkpoints, the same discipline B15(A)'s single mid-iteration checkpoint uses, just applied once
 * per resolved slot instead of once per step. Per-iteration correlation ids reuse {@link
 * FlowStateCodec#deriveForEachIterationCorrelationId} verbatim (B2: "reuse that convention, do not
 * invent a second") -- required non-blank, never silently derived-to-blank, since {@code
 * KernelRunner.matchesCorrelation} treats a blank correlation as "matches anything" and B15(B)
 * multiplies that exposure by N.
 */
final class ParallelAwaitForEachStep {

    private ParallelAwaitForEachStep() {
    }

    static KernelRunner.StepExecutionOutcome execute(KernelRunner runner, StepExecutionRequest req) {
        FlowDefinition flow = req.flow();
        FlowStepDefinition step = req.step();
        Object input = req.input();
        Map<String, Object> state = req.state();
        List<EventEnvelope> emittedEvents = req.emittedEvents();
        FlowTraceMeta traceMeta = req.traceMeta();
        List<StepTrace> stepTraces = req.stepTraces();
        String executionId = req.executionId();
        String defaultCorrelationId = req.defaultCorrelationId();
        ExecutionContext effectiveContext = req.effectiveContext();
        int traceStepIndex = req.traceStepIndex();
        KernelRunner.StepProgressRecorder progressRecorder = req.progressRecorder();
        long stepStartedAt = req.stepStartedAt();
        Map<String, Object> stateBefore = req.stateBefore();
        Map<String, Object> stepInfo = req.stepInfo();

        List<FlowStepDefinition> loopSteps = step.getLoopSteps();
        if (loopSteps.size() != 1 || loopSteps.get(0).getType() != FlowStepDefinition.Type.AWAIT_EVENT) {
            runner.traceFailedStep(traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                    List.of(), null, stepTraces);
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.failed(
                    flow.getName(),
                    List.of(),
                    emittedEvents,
                    "forEach step " + step.getName() + " has parallelAwait=true but its loop body is not "
                            + "exactly one AWAIT_EVENT step -- parallel mode does not support steps before "
                            + "or after the await",
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }
        FlowStepDefinition awaitStep = loopSteps.get(0);
        String awaitStepName = awaitStep.getName();
        String awaitRef = FlowStateCodec.normalizeAwaitRef(awaitStep.getAwaitRef());

        Object collectionValue = KernelRunner.resolveReference(step.getCollectionRef(), state, input);
        Iterable<?> iterable = ForEachStep.toIterable(collectionValue);
        if (iterable == null) {
            runner.traceFailedStep(traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                    List.of(), null, stepTraces);
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.failed(
                    flow.getName(),
                    List.of(),
                    emittedEvents,
                    "forEach step " + step.getName() + " requires an iterable collection at "
                            + step.getCollectionRef(),
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }
        List<Object> items = new ArrayList<>();
        for (Object item : iterable) {
            items.add(item);
        }
        int cap = step.getMaxLoopIterations() != null
                ? step.getMaxLoopIterations()
                : ProcedureExecutionLimits.DEFAULT_MAX_LOOP_ITERATIONS;
        if (items.size() > cap) {
            runner.traceFailedStep(traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                    List.of(), null, stepTraces);
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.failed(
                    flow.getName(),
                    List.of(),
                    emittedEvents,
                    "forEach step " + step.getName() + " exceeded maxLoopIterations=" + cap,
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }

        String itemKey = step.getItemKey();
        boolean hadPreviousItemValue = state.containsKey(itemKey);
        Object previousItemValue = state.get(itemKey);
        boolean hadPreviousCorrelationId = state.containsKey("correlationId");
        Object previousCorrelationIdValue = state.get("correlationId");

        boolean allResolved = true;
        String firstOutstandingCorrelationId = null;
        try {
            for (int i = 0; i < items.size(); i++) {
                String resolvedKey = FlowStateCodec.parallelAwaitResolvedKey(step.getName(), i);
                if (Boolean.TRUE.equals(state.get(resolvedKey))) {
                    continue; // already resolved in a prior attempt/resume -- do not re-attempt
                }
                String iterationCorrelationId = FlowStateCodec.deriveForEachIterationCorrelationId(
                        executionId, awaitStepName, i);
                if (iterationCorrelationId == null) {
                    runner.traceFailedStep(traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                            List.of(), null, stepTraces);
                    return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.failed(
                            flow.getName(),
                            List.of(),
                            emittedEvents,
                            "AWAIT_CORRELATION_UNRESOLVABLE: forEach step " + step.getName()
                                    + " could not derive a per-iteration await correlation id at iteration " + i,
                            executionId,
                            Objects.toString(state.get("correlationId"), defaultCorrelationId),
                            executionId
                    ));
                }
                state.put(itemKey, items.get(i));
                state.put("correlationId", iterationCorrelationId);

                EventEnvelope awaited = runner.awaitEvent(
                        awaitStep, state, defaultCorrelationId, input, effectiveContext.tenantId(), executionId);
                if (awaited == null) {
                    state.put(
                            FlowStateCodec.parallelAwaitStateKey(step.getName(), i),
                            FlowStateCodec.buildAwaitState(awaitStep, 0, awaitRef)
                    );
                    allResolved = false;
                    if (firstOutstandingCorrelationId == null) {
                        firstOutstandingCorrelationId = iterationCorrelationId;
                    }
                    continue;
                }

                state.put(FlowStateCodec.parallelAwaitPayloadKey(awaitRef, i), awaited.payload());
                state.put(resolvedKey, Boolean.TRUE);
                state.remove(FlowStateCodec.parallelAwaitStateKey(step.getName(), i));
                // Mandatory, not an optimization -- see this class's own javadoc: awaitEvent() above
                // already marked the consumed event PROCESSED in the idempotency store, so this
                // iteration's resolution must be durable BEFORE attempting the next iteration.
                progressRecorder.onStepCompleted(traceStepIndex, state);
            }
        } finally {
            if (hadPreviousItemValue) {
                state.put(itemKey, previousItemValue);
            } else {
                state.remove(itemKey);
            }
            if (hadPreviousCorrelationId) {
                state.put("correlationId", previousCorrelationIdValue);
            } else {
                state.remove("correlationId");
            }
        }

        if (!allResolved) {
            runner.traceFailedStep(traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                    List.of(), null, stepTraces);
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.waitingEvent(
                    flow.getName(),
                    emittedEvents,
                    step.getName(),
                    traceStepIndex,
                    awaitStep.getAwaitEventName(),
                    firstOutstandingCorrelationId,
                    "Awaited event(s) not found for parallel forEach step: " + step.getName()
                            + " eventName=" + awaitStep.getAwaitEventName(),
                    executionId,
                    firstOutstandingCorrelationId,
                    executionId
            ));
        }

        // All N iterations resolved: fold the per-iteration payloads into one ordered list under
        // the shared awaitRef (author convenience for what runs after the loop), and clear every
        // per-iteration marker -- nothing about this step should remain visible in state once done.
        List<Object> combined = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            combined.add(state.remove(FlowStateCodec.parallelAwaitPayloadKey(awaitRef, i)));
            state.remove(FlowStateCodec.parallelAwaitResolvedKey(step.getName(), i));
        }
        state.put(awaitRef, combined);
        stepInfo.put("collectionSize", items.size());
        stepInfo.put("awaitedEventName", awaitStep.getAwaitEventName());
        stepInfo.put("parallelAwait", Boolean.TRUE);
        return null;
    }
}
