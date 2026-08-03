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
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code FOR_EACH} step-kind case body (formerly {@code KernelRunner#executeForEachStep}), split
 * out verbatim -- no behavior change. See {@link InvariantCheckStep} for the shared {@code execute}
 * return convention (identical to the {@code null}-means-"loop completed, fall through" contract
 * {@code executeForEachStep} already used pre-split). Stays a flat sibling of {@link KernelRunner}
 * in {@code com.npdev.kernel}, not a subpackage, for the same reason the rest of this split's files
 * do: the collaborators (executeSteps, traceFailedStep) are package-private.
 *
 * <p>LIFT-LOOP-P2: durable {@code forEach} execution. The loop occupies exactly one flat
 * step-trace position (like any other atomic step -- MAP, CAPABILITY_CALL, ...); it does
 * <b>not</b> extend the flat step-index space the way {@code BRANCH}'s nested then/else steps
 * do, and deliberately doesn't try to make each nested step within an iteration individually
 * resumable. Instead, durability is at iteration granularity: after each iteration's nested
 * steps all succeed, progress (the next iteration index to run) is folded into {@code state}
 * under a step-scoped key and checkpointed via {@code progressRecorder} <i>without</i> advancing
 * the outer step index -- so a crash mid-loop resumes by re-entering this same {@code forEach}
 * step and skipping iterations already recorded as done, rather than re-running the whole
 * collection from item 0. A capability call inside the loop body still gets its own existing
 * per-call idempotency (via {@code CapabilityExecutionPolicy.idempotencyKeyField()}); an author
 * whose key expression varies per item (e.g. references the loop's {@code itemKey}) gets correct
 * at-most-once behavior even for the iteration that was mid-flight at crash time and gets
 * partially re-run.
 *
 * <p>B15(A) (Move 16, docs/BOUNDARY_LIFT_ROADMAP.md): a SEQUENTIAL {@code AWAIT_EVENT} nested in
 * the loop body -- at most one outstanding await at a time -- is now durably resumable. Three
 * things make this safe (a bare, non-loop await already relies on the second and third; only the
 * first is new here): (1) a deterministic, non-blank, per-iteration correlation id (see {@link
 * FlowStateCodec#deriveForEachIterationCorrelationId}) is written to {@code state.correlationId}
 * before each iteration's body runs, so {@code KernelRunner.awaitEvent}'s existing
 * {@code state.get("correlationId")} read discriminates iteration i's own reply from any other
 * iteration's -- {@code FlowInstance.correlationId()} itself is fixed for the instance's whole
 * lifetime and is NEVER what this per-iteration value threads through as (confirmed by tracing
 * every call site of {@code KernelRunner.matchesCorrelation}); (2) a satisfaction marker (see
 * {@link FlowStateCodec#FOR_EACH_AWAIT_SATISFIED_KEY_PREFIX}) plus (3) an early, mid-iteration
 * checkpoint the instant the await resolves (the {@code loopBodyProgressRecorder} below, pinned
 * to this forEach step's own {@code traceStepIndex} so it durably persists {@code state} without
 * corrupting the flow's own outer step-index checkpoint) together close the crash window between
 * "event consumed" and "outer iteration progress advanced" -- without both, a crash in that window
 * loses the in-memory-only marker, and re-entry would call {@code awaitEvent()} again only to find
 * the one satisfying event already marked processed by the (separate) idempotency store, parking
 * the flow WAITING forever on an event that will never arrive again. Both only activate for a loop
 * body that actually contains an await ({@link #findFirstAwaitStepName}) -- a loop with no await
 * gets the exact prior behavior (NOOP progress recorder, no correlationId mutation).
 */
final class ForEachStep {

    private ForEachStep() {
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
        int traceStepIndex = req.traceStepIndex();
        KernelRunner.StepProgressRecorder progressRecorder = req.progressRecorder();
        ExecutionContext effectiveContext = req.effectiveContext();
        long stepStartedAt = req.stepStartedAt();
        Map<String, Object> stateBefore = req.stateBefore();
        Map<String, Object> stepInfo = req.stepInfo();

        Object collectionValue = KernelRunner.resolveReference(step.getCollectionRef(), state, input);
        Iterable<?> iterable = toIterable(collectionValue);
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

        String progressKey = "__forEachProgress." + step.getName();
        int startIndex = state.get(progressKey) instanceof Number n ? n.intValue() : 0;
        String itemKey = step.getItemKey();
        boolean hadPreviousItemValue = state.containsKey(itemKey);
        Object previousItemValue = state.get(itemKey);

        String awaitStepName = findFirstAwaitStepName(step.getLoopSteps());
        boolean loopHasAwait = awaitStepName != null;
        boolean hadPreviousCorrelationId = state.containsKey("correlationId");
        Object previousCorrelationIdValue = state.get("correlationId");
        String satisfiedKey = loopHasAwait ? FlowStateCodec.FOR_EACH_AWAIT_SATISFIED_KEY_PREFIX + awaitStepName : null;
        // B15(A): pinned to this forEach step's OWN traceStepIndex (not whatever nested index the
        // loop body's own executeSteps call would otherwise compute) so a mid-iteration checkpoint
        // durably persists state the instant the nested await resolves, without corrupting the
        // flow's own outer step-index checkpoint -- see this class's own javadoc for why this needs
        // to be earlier than the existing post-iteration checkpoint below.
        KernelRunner.StepProgressRecorder loopBodyProgressRecorder = loopHasAwait
                ? (ignoredNestedIndex, currentState) -> progressRecorder.onStepCompleted(traceStepIndex, currentState)
                : KernelRunner.NOOP_STEP_PROGRESS_RECORDER;
        // B15(A): true only for the still-waiting return below -- the finally block's cleanup
        // must NOT run in that case (see its own comment for why: the loop has not finished, it is
        // durably parked mid-iteration, and progressKey/correlationId/satisfiedKey all need to
        // survive into the persisted WAITING checkpoint so a later resume re-enters the SAME
        // iteration instead of restarting the whole collection from item 0).
        boolean waitingForEvent = false;

        try {
            for (int i = startIndex; i < items.size(); i++) {
                state.put(itemKey, items.get(i));
                if (loopHasAwait) {
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
                    state.put("correlationId", iterationCorrelationId);
                }
                List<StepTrace> loopIterationTraces = new ArrayList<>();
                KernelRunner.StepExecutionOutcome nested = runner.executeSteps(
                        flow,
                        step.getLoopSteps(),
                        input,
                        state,
                        emittedEvents,
                        traceMeta,
                        loopIterationTraces,
                        executionId,
                        defaultCorrelationId,
                        0,
                        loopBodyProgressRecorder,
                        effectiveContext
                );
                stepTraces.addAll(loopIterationTraces);
                if (nested.failedResult() != null) {
                    ExecutionResult nestedFailure = nested.failedResult();
                    runner.traceFailedStep(traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                            nestedFailure.getInvariantViolations(), nestedFailure.getCapabilityError(), stepTraces);
                    if (nestedFailure.getStatus() == ExecutionStatus.WAITING_EVENT) {
                        // B15(A): a genuinely still-waiting nested await must propagate AS a
                        // WAITING_EVENT result -- re-wrapping it as a generic FAILED (the sibling
                        // branch below, for real invariant/capability failures) would silently
                        // discard its own awaitedEventName/awaitedCorrelationId/awaitedStepIndex,
                        // turning a normal "not yet satisfied" checkpoint into a terminal failure.
                        // This exact branch was unreachable before this Move (the validator
                        // rejected any await nested in a loop body outright), so nothing already
                        // depended on the generic-wrap behavior for this specific status.
                        waitingForEvent = true;
                        return KernelRunner.StepExecutionOutcome.failed(nestedFailure);
                    }
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
                if (loopHasAwait) {
                    state.remove(satisfiedKey);
                }
                // Checkpoint at the SAME (not +1) step index -- the loop itself hasn't finished,
                // so a crash-and-resume here must re-enter this forEach step, not skip past it.
                state.put(progressKey, i + 1);
                progressRecorder.onStepCompleted(traceStepIndex, state);
            }
        } finally {
            if (!waitingForEvent) {
                if (hadPreviousItemValue) {
                    state.put(itemKey, previousItemValue);
                } else {
                    state.remove(itemKey);
                }
                if (loopHasAwait) {
                    if (hadPreviousCorrelationId) {
                        state.put("correlationId", previousCorrelationIdValue);
                    } else {
                        state.remove("correlationId");
                    }
                    state.remove(satisfiedKey);
                }
                state.remove(progressKey);
            }
        }
        stepInfo.put("collectionSize", items.size());
        return null;
    }

    /** B15(A): the loop body's own await, if any -- see this class's own javadoc. Only the first
     * one found is used (this lift targets exactly one outstanding await per iteration; a loop
     * body with more than one reachable await is a design this Move does not attempt). */
    private static String findFirstAwaitStepName(List<FlowStepDefinition> steps) {
        for (FlowStepDefinition step : steps) {
            if (step.getType() == FlowStepDefinition.Type.AWAIT_EVENT) {
                return step.getName();
            }
            String nested = firstNonNull(
                    findFirstAwaitStepName(step.getThenSteps()),
                    findFirstAwaitStepName(step.getElseSteps()),
                    findFirstAwaitStepName(step.getLoopSteps())
            );
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static String firstNonNull(String a, String b, String c) {
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return c;
    }

    private static Iterable<?> toIterable(Object value) {
        if (value instanceof Iterable<?> iterable) {
            return iterable;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                items.add(java.lang.reflect.Array.get(value, index));
            }
            return items;
        }
        return null;
    }
}
