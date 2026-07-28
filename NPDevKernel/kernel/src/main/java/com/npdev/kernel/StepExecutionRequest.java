package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;

/**
 * T2.B.5 (see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the per-step-invocation "step context" --
 * every value {@link KernelRunner#executeSteps}'s loop body has in scope for the current step,
 * bundled once per iteration and handed to whichever {@code *Step.execute(KernelRunner, ...)}
 * static matches {@code step.getType()}. This is the parameter-object half of the split's design
 * (the other half is the {@code KernelRunner runner} argument each step-kind class takes for the
 * collaborators it needs -- capability dispatch, event store, invariant engine, tracing, etc. --
 * exactly the shape {@code GeneratedCrudRuntimeSupport}/T2.B.3 used for its own instance-bound
 * methods). Immutable and rebuilt fresh on every step iteration; carrying no behavior of its own.
 */
record StepExecutionRequest(
        FlowDefinition flow,
        FlowStepDefinition step,
        Object input,
        Map<String, Object> state,
        List<EventEnvelope> emittedEvents,
        FlowTraceMeta traceMeta,
        List<StepTrace> stepTraces,
        String executionId,
        String defaultCorrelationId,
        int stepIndexOffset,
        KernelRunner.StepProgressRecorder progressRecorder,
        ExecutionContext effectiveContext,
        int traceStepIndex,
        long stepStartedAt,
        Map<String, Object> stateBefore,
        Map<String, Object> stepInfo
) {
}
