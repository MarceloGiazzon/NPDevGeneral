package com.npdev.kernel.procedures;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ProcedureExecutionResult(
        boolean ok,
        Map<String, Object> state,
        List<ProcedureStepResult> steps,
        String failureCode,
        String failureMessage
) {
    public ProcedureExecutionResult {
        state = state == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(state));
        steps = steps == null ? List.of() : List.copyOf(steps);
        failureCode = failureCode == null ? "" : failureCode.trim();
        failureMessage = failureMessage == null ? "" : failureMessage.trim();
    }

    public static ProcedureExecutionResult success(Map<String, Object> state, List<ProcedureStepResult> steps) {
        return new ProcedureExecutionResult(true, state, steps, "", "");
    }

    public static ProcedureExecutionResult failure(
            Map<String, Object> state,
            List<ProcedureStepResult> steps,
            String code,
            String message
    ) {
        return new ProcedureExecutionResult(false, state, steps, code, message);
    }
}
