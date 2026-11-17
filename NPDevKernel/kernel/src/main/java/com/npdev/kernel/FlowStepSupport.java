package com.npdev.kernel;

import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.trace.FlowTraceMeta;
import com.npdev.kernel.trace.StepTrace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Helpers the flow step bodies that call out to another executor (callFlow, procedure-step) share. */
final class FlowStepSupport {

    private FlowStepSupport() {
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

    static KernelRunner.StepExecutionOutcome fail(
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
