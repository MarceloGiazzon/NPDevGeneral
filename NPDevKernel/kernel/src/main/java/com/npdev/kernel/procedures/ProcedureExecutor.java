package com.npdev.kernel.procedures;

import com.npdev.kernel.ExecutionContext;

import java.util.Map;

public interface ProcedureExecutor {
    ProcedureExecutionResult execute(
            ProcedureDefinition definition,
            Map<String, Object> input,
            ExecutionContext context
    );
}
