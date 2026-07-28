package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.InvariantEngine;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T2.B.5 (pure mechanical extraction, see docs/DSL2_AND_DECOMPOSITION_PLAN.md 2.B.5): the
 * {@code INVARIANT_CHECK} step-kind case body, split out of {@link KernelRunner#executeSteps}'s
 * switch verbatim -- no behavior change. {@code execute} returns {@code null} to mean "continue the
 * flow" (the enclosing switch case falls through to the shared success-tracing bookkeeping at the
 * bottom of {@code executeSteps}) or a non-null {@link KernelRunner.StepExecutionOutcome} to mean
 * "return this immediately from executeSteps" -- the same convention {@code executeForEachStep}
 * already used before this split, now applied uniformly across every step kind. Stays a flat
 * sibling of {@link KernelRunner} in {@code com.npdev.kernel}, not a subpackage, for the same
 * reason the rest of this split's files do: the collaborators (invariantEngine, traceFailedStep,
 * resolveReference, resolveInvariantConceptName, enrichInvariantViolations) are package-private.
 */
final class InvariantCheckStep {

    private InvariantCheckStep() {
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
        long stepStartedAt = req.stepStartedAt();
        Map<String, Object> stateBefore = req.stateBefore();
        Map<String, Object> stepInfo = req.stepInfo();

        Object payload = KernelRunner.resolveReference(step.getInputRef(), state, input);
        String conceptName = KernelRunner.resolveInvariantConceptName(step, flow);
        List<String> invariantRefs = step.getInvariants();
        stepInfo.put("checkpoint", step.getCheckpoint() == null ? null : step.getCheckpoint().name());
        stepInfo.put("requestedInvariantRefs", invariantRefs == null ? List.of() : List.copyOf(invariantRefs));
        if (invariantRefs == null || invariantRefs.isEmpty()) {
            InvariantEngine.Violation missingRefsViolation = new InvariantEngine.Violation(
                    "MODEL_INVALID",
                    "Invariant step must declare explicit invariant refs or be compiled with scope expansion",
                    "<none>",
                    conceptName,
                    flow.getName(),
                    step.getName(),
                    traceStepIndex,
                    Map.of("stepType", "INVARIANT_CHECK")
            );
            runner.traceFailedStep(
                    traceMeta,
                    step,
                    traceStepIndex,
                    stepStartedAt,
                    stateBefore,
                    state,
                    stepInfo,
                    List.of(missingRefsViolation),
                    null,
                    stepTraces
            );
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.invariantFailed(
                    flow.getName(),
                    List.of(missingRefsViolation),
                    emittedEvents,
                    "Invariant checkpoint failed at step: " + step.getName(),
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }

        InvariantEngine.InvariantEvaluationResult evalResult = runner.invariantEngine.evaluate(
                new InvariantEngine.InvariantEvaluationRequest(
                        conceptName,
                        payload,
                        invariantRefs,
                        new InvariantEngine.EvaluationMetadata(
                                flow.getName(),
                                step.getName(),
                                traceStepIndex,
                                Objects.requireNonNull(step.getCheckpoint(), "Invariant checkpoint is required"),
                                Objects.toString(state.get("correlationId"), defaultCorrelationId)
                        ),
                        state
                )
        );
        List<InvariantEngine.Violation> violations = KernelRunner.enrichInvariantViolations(
                evalResult.violations(),
                conceptName,
                flow.getName(),
                step.getName(),
                traceStepIndex,
                invariantRefs
        );
        if (!violations.isEmpty()) {
            runner.traceFailedStep(
                    traceMeta,
                    step,
                    traceStepIndex,
                    stepStartedAt,
                    stateBefore,
                    state,
                    stepInfo,
                    violations,
                    null,
                    stepTraces
            );
            return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.invariantFailed(
                    flow.getName(),
                    violations,
                    emittedEvents,
                    "Invariant checkpoint failed at step: " + step.getName(),
                    executionId,
                    Objects.toString(state.get("correlationId"), defaultCorrelationId),
                    executionId
            ));
        }
        return null;
    }
}
