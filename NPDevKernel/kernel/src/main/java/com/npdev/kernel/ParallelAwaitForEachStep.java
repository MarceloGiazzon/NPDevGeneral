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
 * <p><b>Wave 3 (S8_DEFERRED_FIVE_PLAN.md, 2026-08-04): lifted from "exactly one AWAIT_EVENT step,
 * no steps before or after" to an arbitrary loop body containing exactly one reachable {@code
 * AWAIT_EVENT} step plus any number of other (non-{@code FOR_EACH}) steps before/after it.</b> The
 * original restriction existed because a step mutating a non-namespaced {@code state} key would
 * silently clobber across independently-attempted iterations; that hazard is now closed by {@link
 * ParallelLoopIterationScope} (per-iteration namespaced shadow keys in the SAME {@code state} blob
 * -- see {@link FlowStateCodec}'s own extensive javadoc on {@code PARALLEL_LOOP_SCOPE_KEY_PREFIX}
 * for the full I0 design record: why Option A was chosen over B/C, why it survives a restart with
 * iterations parked at DIFFERENT steps, and the I2 decision on what survives past the loop). A
 * nested {@code FOR_EACH} in the body is still refused (I3, vector 9) -- composing two independent
 * scoping schemes was never attempted here and is not safe to assume.
 *
 * <p><b>Storage decision (B0/B1, S6): no new durable storage surface.</b> Every outstanding wait
 * descriptor and every resolved payload lives in {@code state} (the existing {@code FlowInstance}
 * JSON blob) -- Wave 3's per-iteration shadow keys are an extension of this same decision, not a
 * departure from it (see {@link FlowStateCodec}'s {@code PARALLEL_LOOP_*} javadoc).
 *
 * <p><b>Fan-in completion semantics (B3, S6, unchanged by Wave 3):</b>
 * <ul>
 *   <li><b>Join/barrier:</b> ALL N iterations must resolve before the step completes -- the natural
 *   "wait for every reply" reading. No partial/best-effort completion.</li>
 *   <li><b>Fail-fast vs. partial failure:</b> a genuine (non-waiting) failure from ANY iteration's
 *   attempt fails the whole step immediately, matching {@link ForEachStep}'s existing sequential
 *   behavior for a real failure (as opposed to a still-waiting one). No mixed-result reporting is
 *   introduced.</li>
 *   <li><b>Unsatisfiable slot / timeout:</b> parks forever, subject to the SAME resume-eligibility
 *   backoff and eventual {@code STUCK} detection every single-slot {@code AWAIT_EVENT} already has
 *   today (which itself has no timeout concept).</li>
 * </ul>
 *
 * <p><b>Durability (B2/B4, S6):</b> {@code awaitEvent()} marks a found event PROCESSED in the
 * idempotency store as a side effect of merely being called. This class checkpoints ({@code
 * progressRecorder.onStepCompleted}) immediately after EACH iteration's turn closes (fully
 * resolved, genuinely still waiting, or ended via a nested {@code return}) -- N separate
 * close-the-window checkpoints. Per-iteration correlation ids reuse {@link
 * FlowStateCodec#deriveForEachIterationCorrelationId} verbatim (B2: "reuse that convention, do not
 * invent a second") -- required non-blank, never silently derived-to-blank. {@code
 * KernelRunner.matchesCorrelation} now fails closed on a blank correlation (F9,
 * FIRST_IMPRESSION_SPEC.md I7), so a silently-blank derivation strands that iteration waiting
 * forever rather than resolving against a DIFFERENT iteration's event as it would have under the
 * old fail-open behavior -- Wave 3's multi-step body still multiplies the blast radius of that
 * hang across more iterations in flight at once, so the non-blank requirement carries forward
 * unchanged either way.
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
        FlowStepDefinition awaitStep = null;
        int awaitCount = 0;
        for (FlowStepDefinition candidate : loopSteps) {
            if (candidate.getType() == FlowStepDefinition.Type.AWAIT_EVENT) {
                awaitCount++;
                awaitStep = candidate;
            }
            if (candidate.getType() == FlowStepDefinition.Type.FOR_EACH) {
                runner.traceFailedStep(traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                        List.of(), null, stepTraces);
                return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.failed(
                        flow.getName(),
                        List.of(),
                        emittedEvents,
                        "forEach step " + step.getName() + " has parallelAwait=true but its loop body nests"
                                + " another forEach step (" + candidate.getName() + ") -- I3 (Wave 3): a"
                                + " parallel forEach body must not nest another forEach, sequential or"
                                + " parallel -- composing two independent per-iteration scoping schemes is"
                                + " not supported",
                        executionId,
                        Objects.toString(state.get("correlationId"), defaultCorrelationId),
                        executionId
                ));
            }
        }
        if (awaitCount != 1) {
            runner.traceFailedStep(traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                    List.of(), null, stepTraces);
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.failed(
                    flow.getName(),
                    List.of(),
                    emittedEvents,
                    "forEach step " + step.getName() + " has parallelAwait=true but its loop body has "
                            + awaitCount + " AWAIT_EVENT step(s) -- exactly one is required",
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }
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
        // Wave 3: pinned to this forEach step's OWN traceStepIndex (not whatever nested index the
        // loop body's own executeSteps call would otherwise compute), exactly matching B15(A)'s
        // ForEachStep#loopBodyProgressRecorder -- a mid-iteration checkpoint must durably persist
        // state without corrupting the flow's own outer step-index checkpoint.
        KernelRunner.StepProgressRecorder loopBodyProgressRecorder =
                (ignoredNestedIndex, currentState) -> progressRecorder.onStepCompleted(traceStepIndex, currentState);

        ParallelLoopIterationScope.captureBaselineOnce(state, step.getName());

        boolean allResolved = true;
        String firstOutstandingCorrelationId = null;
        for (int i = 0; i < items.size(); i++) {
            String doneKey = FlowStateCodec.parallelLoopIterationDoneKey(step.getName(), i);
            if (Boolean.TRUE.equals(state.get(doneKey))) {
                continue; // already fully completed in a prior pass -- do not re-attempt
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

            ParallelLoopIterationScope.enter(state, step.getName(), i);
            state.put(itemKey, items.get(i));
            state.put("correlationId", iterationCorrelationId);

            List<StepTrace> iterationTraces = new ArrayList<>();
            KernelRunner.StepExecutionOutcome nested = runner.executeSteps(
                    flow,
                    loopSteps,
                    input,
                    state,
                    emittedEvents,
                    traceMeta,
                    iterationTraces,
                    executionId,
                    defaultCorrelationId,
                    0,
                    loopBodyProgressRecorder,
                    effectiveContext
            );
            stepTraces.addAll(iterationTraces);

            if (nested.failedResult() != null) {
                ExecutionResult nestedFailure = nested.failedResult();
                if (nestedFailure.getStatus() == ExecutionStatus.WAITING_EVENT) {
                    ParallelLoopIterationScope.exit(state, step.getName(), i);
                    allResolved = false;
                    if (firstOutstandingCorrelationId == null) {
                        firstOutstandingCorrelationId = iterationCorrelationId;
                    }
                    progressRecorder.onStepCompleted(traceStepIndex, state);
                    continue;
                }
                ParallelLoopIterationScope.exit(state, step.getName(), i);
                runner.traceFailedStep(traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                        nestedFailure.getInvariantViolations(), nestedFailure.getCapabilityError(), stepTraces);
                return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.failed(
                        flow.getName(),
                        nestedFailure.getInvariantViolations(),
                        emittedEvents,
                        "forEach step " + step.getName() + " failed at iteration " + i + ": "
                                + nestedFailure.getError(),
                        executionId,
                        Objects.toString(state.get("correlationId"), defaultCorrelationId),
                        executionId
                ));
            }

            ParallelLoopIterationScope.exit(state, step.getName(), i);
            state.put(doneKey, Boolean.TRUE);
            // Mandatory, not an optimization -- see this class's own javadoc: awaitEvent() already
            // marks a consumed event PROCESSED in the idempotency store, so this iteration's
            // resolution must be durable BEFORE attempting the next iteration.
            progressRecorder.onStepCompleted(traceStepIndex, state);
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

        // All N iterations resolved: fold each iteration's relocated awaitRef payload into one
        // ordered list under the shared awaitRef (I2 -- see FlowStateCodec's PARALLEL_LOOP_*
        // javadoc for why this is the ONE thing that survives a multi-step body, everything else
        // being scoped-and-discarded), then clear every per-iteration marker for this step.
        List<Object> combined = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            combined.add(ParallelLoopIterationScope.readScoped(state, step.getName(), i, awaitRef));
        }
        ParallelLoopIterationScope.clearAll(state, step.getName());
        state.put(awaitRef, combined);
        stepInfo.put("collectionSize", items.size());
        stepInfo.put("awaitedEventName", awaitStep.getAwaitEventName());
        stepInfo.put("parallelAwait", Boolean.TRUE);
        return null;
    }
}
