package com.npdev.kernel;

import com.npdev.kernel.events.EventEnvelope;

import java.util.List;
import java.util.Map;

public interface FlowEngine {
    ExecutionResult startFlow(String flowName, Map<String, Object> input, ExecutionContext executionContext);

    ResumeOutcome resumeFlow(String correlationId, EventEnvelope eventEnvelope);

    record ResumeOutcome(
            int matchedWaiters,
            int resumedWaiters,
            List<String> resumedExecutionIds
    ) {
        public ResumeOutcome {
            resumedExecutionIds = resumedExecutionIds == null ? List.of() : List.copyOf(resumedExecutionIds);
        }

        public static ResumeOutcome noMatch() {
            return new ResumeOutcome(0, 0, List.of());
        }
    }
}
