package com.npdev.kernel;

import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.procedures.ProcedureDefinition;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import com.npdev.kernel.procedures.ProcedureExecutor;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): the {@code CALL_PROCEDURE} step-kind case
 * body -- a flow synchronously invoking a named procedure. This closes the asymmetry REG-77 found:
 * procedure-to-procedure calls already existed (DefaultProcedureExecutor's own nested
 * {@code callProcedure} step), flow-to-procedure did not, so a flow like {@code AtivarCrossDocking}
 * could never reach {@code patchConcept}.
 *
 * <p>Mirrors {@link CapabilityCallStep}'s shape -- the closest existing analog, since a capability
 * call is likewise a synchronous external operation that completes or fails within one step, with
 * no {@code awaitEvent}-style suspension. Durability need nothing beyond what every other step
 * already gets: the result is written into {@code state} before returning, and {@code state} is
 * checkpointed by the caller after each completed step, so a resumed flow simply continues past
 * this step's index rather than re-invoking the procedure.
 *
 * <p>Unlike a capability failure, a procedure failure is not run through
 * {@code CapabilityErrorKind} classification (no equivalent taxonomy exists for procedures) --
 * it always surfaces as a plain, retryable {@link ExecutionResult#failed}, the same shape the
 * step-dispatch switch's own "unsupported step type" branch already uses for a comparably generic
 * failure. That is a deliberate, smaller scope than capability-call's rich retry/permanent
 * classification, not an oversight.
 */
final class CallProcedureStep {

    private CallProcedureStep() {
    }

    @SuppressWarnings("unchecked")
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
        ExecutionContext effectiveContext = req.effectiveContext();

        String procedureName = step.getProcedureName();
        stepInfo.put("procedure", procedureName);

        ProcedureExecutor procedureExecutor = runner.procedureExecutor();
        ProcedureDefinition definition = procedureExecutor == null ? null : runner.findProcedureDefinition(procedureName);
        if (procedureExecutor == null || definition == null) {
            String message = procedureExecutor == null
                    ? "NO_PROCEDURE_RUNNER: no procedure executor configured for callProcedure"
                    : "PROCEDURE_NOT_FOUND: procedure not found: " + procedureName;
            return fail(runner, flow, step, emittedEvents, traceMeta, stepTraces, executionId,
                    defaultCorrelationId, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo, message);
        }

        Object resolvedInput = KernelRunner.resolveReference(step.getInputRef(), state, input);
        Map<String, Object> procedureInput;
        if (resolvedInput == null) {
            procedureInput = Map.of();
        } else if (resolvedInput instanceof Map<?, ?> map) {
            procedureInput = (Map<String, Object>) map;
        } else {
            return fail(runner, flow, step, emittedEvents, traceMeta, stepTraces, executionId,
                    defaultCorrelationId, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo,
                    "PROCEDURE_INPUT_INVALID: callProcedure input must resolve to a map, got "
                            + resolvedInput.getClass().getSimpleName());
        }

        ProcedureExecutionResult result = procedureExecutor.execute(definition, procedureInput, effectiveContext);
        if (!result.ok()) {
            String message = (result.failureCode().isBlank() ? "PROCEDURE_FAILED" : result.failureCode())
                    + (result.failureMessage().isBlank() ? "" : ": " + result.failureMessage());
            return fail(runner, flow, step, emittedEvents, traceMeta, stepTraces, executionId,
                    defaultCorrelationId, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo, message);
        }

        // Same convention DefaultProcedureExecutor's own nested callProcedure step already uses:
        // a "return" state key holds the procedure's declared result; fall back to the whole
        // accumulated state only if the procedure never named one.
        Object output = plainTree(result.state().containsKey("return") ? result.state().get("return") : result.state());
        String outputRef = KernelRunner.normalizeRef(step.getOutputRef());
        if (!outputRef.isBlank()) {
            state.put(outputRef, output);
        }
        state.put("last", output);
        return null;
    }

    /**
     * A procedure's return value may hold {@link ConceptRecord}s (a patched/read row). Flow state is
     * persisted as JSON, where such a row is {@code {conceptName,id,tenantId,data,rowVersion}} -- and
     * {@link KernelRunner#resolveReference} only walks Maps, so left as a record {@code $out.data.status}
     * resolved to nothing until the flow had parked once and been reloaded (a branch on it silently took
     * the wrong arm). Hand flow state the same Map shape it will have after a reload, from the first step.
     */
    static Object plainTree(Object value) {
        if (value instanceof ConceptRecord record) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("conceptName", record.conceptName());
            out.put("id", record.id());
            out.put("tenantId", record.tenantId());
            out.put("data", plainTree(record.data()));
            out.put("rowVersion", record.rowVersion());
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), plainTree(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                out.add(plainTree(item));
            }
            return out;
        }
        return value;
    }

    private static KernelRunner.StepExecutionOutcome fail(
            KernelRunner runner,
            FlowDefinition flow,
            FlowStepDefinition step,
            List<EventEnvelope> emittedEvents,
            FlowTraceMeta traceMeta,
            List<StepTrace> stepTraces,
            String executionId,
            String defaultCorrelationId,
            int traceStepIndex,
            long stepStartedAt,
            Map<String, Object> stateBefore,
            Map<String, Object> state,
            Map<String, Object> stepInfo,
            String message
    ) {
        runner.traceFailedStep(
                traceMeta, step, traceStepIndex, stepStartedAt, stateBefore, state, stepInfo, List.of(), null, stepTraces
        );
        return KernelRunner.StepExecutionOutcome.failed(ExecutionResult.failed(
                flow.getName(),
                List.of(),
                emittedEvents,
                message,
                executionId,
                Objects.toString(state.get("correlationId"), defaultCorrelationId),
                executionId
        ));
    }
}
