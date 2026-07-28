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
 * resumable (nested {@code AWAIT_EVENT} is rejected by {@code SemanticValidator} at compile
 * time for exactly this reason). Instead, durability is at iteration granularity: after each
 * iteration's nested steps all succeed, progress (the next iteration index to run) is folded
 * into {@code state} under a step-scoped key and checkpointed via {@code progressRecorder}
 * <i>without</i> advancing the outer step index -- so a crash mid-loop resumes by re-entering
 * this same {@code forEach} step and skipping iterations already recorded as done, rather than
 * re-running the whole collection from item 0. A capability call inside the loop body still
 * gets its own existing per-call idempotency (via {@code CapabilityExecutionPolicy
 * .idempotencyKeyField()}); an author whose key expression varies per item (e.g. references the
 * loop's {@code itemKey}) gets correct at-most-once behavior even for the iteration that was
 * mid-flight at crash time and gets partially re-run.
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

        try {
            for (int i = startIndex; i < items.size(); i++) {
                state.put(itemKey, items.get(i));
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
                        KernelRunner.NOOP_STEP_PROGRESS_RECORDER,
                        effectiveContext
                );
                stepTraces.addAll(loopIterationTraces);
                if (nested.failedResult() != null) {
                    ExecutionResult nestedFailure = nested.failedResult();
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
                // Checkpoint at the SAME (not +1) step index -- the loop itself hasn't finished,
                // so a crash-and-resume here must re-enter this forEach step, not skip past it.
                state.put(progressKey, i + 1);
                progressRecorder.onStepCompleted(traceStepIndex, state);
            }
        } finally {
            if (hadPreviousItemValue) {
                state.put(itemKey, previousItemValue);
            } else {
                state.remove(itemKey);
            }
            state.remove(progressKey);
        }
        stepInfo.put("collectionSize", items.size());
        return null;
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
