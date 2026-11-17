package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.procedures.ProcedureDefinition;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import com.npdev.kernel.procedures.ProcedureExecutor;
import com.npdev.kernel.procedures.ProcedureStep;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FORM P3-T5b: the {@code PROCEDURE_STEP} step-kind case body -- a flow's {@code set} / {@code readConcept} /
 * {@code listConcepts} / {@code runQuery} / {@code deleteConcept} step. It runs the procedure step the procedure
 * executor already implements as a one-step procedure, so a flow and a procedure share ONE implementation of each
 * (reads, the query pushdown, delete, strict {@code $ref} resolution) instead of two that drift.
 *
 * <p>The one-step procedure sees the flow state as its input (so {@code $input.x} and every earlier step's output
 * resolve), and the executor context is the flow's, so row-level access rules and the flow-level {@code runAs}
 * apply unchanged. The result goes back the way {@link FlowStepSupport} does it: into the output key and
 * {@code last}, as plain maps/lists so a parked-and-reloaded flow sees the same shape.
 */
final class ProcedureFlowStep {

    private ProcedureFlowStep() {
    }

    static KernelRunner.StepExecutionOutcome execute(KernelRunner runner, StepExecutionRequest req) {
        FlowStepDefinition step = req.step();
        Map<String, Object> state = req.state();
        ProcedureStep embedded = step.getEmbeddedStep();
        ProcedureExecutor executor = runner.procedureExecutor();
        if (executor == null || embedded == null) {
            return FlowStepSupport.fail(runner, req.flow(), step, req.emittedEvents(), req.traceMeta(), req.stepTraces(),
                    req.executionId(), req.defaultCorrelationId(), req.traceStepIndex(), req.stepStartedAt(),
                    req.stateBefore(), state, req.stepInfo(),
                    executor == null
                            ? "NO_PROCEDURE_RUNNER: no procedure executor configured for step " + step.getName()
                            : "PROCEDURE_STEP_MISSING: step " + step.getName() + " carries no step to run");
        }
        req.stepInfo().put("step", embedded.type().name());
        ProcedureDefinition oneStep = new ProcedureDefinition(
                "flow:" + req.flow().getName() + ":" + step.getName(), List.of(embedded));
        Map<String, Object> view = new LinkedHashMap<>(state);
        ProcedureExecutionResult result = executor.execute(oneStep, view, req.effectiveContext());
        if (!result.ok()) {
            String message = (result.failureCode().isBlank() ? "PROCEDURE_STEP_FAILED" : result.failureCode())
                    + (result.failureMessage().isBlank() ? "" : ": " + result.failureMessage());
            return FlowStepSupport.fail(runner, req.flow(), step, req.emittedEvents(), req.traceMeta(), req.stepTraces(),
                    req.executionId(), req.defaultCorrelationId(), req.traceStepIndex(), req.stepStartedAt(),
                    req.stateBefore(), state, req.stepInfo(), message);
        }
        String outputKey = embedded.outputKey();
        if (outputKey != null && !outputKey.isBlank()) {
            Object output = FlowStepSupport.plainTree(result.state().get(outputKey));
            state.put(outputKey, output);
            state.put("last", output);
        }
        return null;
    }
}
