package com.npdev.kernel.procedures;

public record ProcedureExecutionLimits(
        int maxSteps,
        int maxRecursionDepth,
        int maxLoopIterations
) {
    public static final int DEFAULT_MAX_STEPS = 1_000;
    public static final int DEFAULT_MAX_RECURSION_DEPTH = 16;
    public static final int DEFAULT_MAX_LOOP_ITERATIONS = 10_000;

    public ProcedureExecutionLimits {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        if (maxRecursionDepth <= 0) {
            throw new IllegalArgumentException("maxRecursionDepth must be positive");
        }
        if (maxLoopIterations <= 0) {
            throw new IllegalArgumentException("maxLoopIterations must be positive");
        }
    }

    public static ProcedureExecutionLimits defaults() {
        return new ProcedureExecutionLimits(
                DEFAULT_MAX_STEPS,
                DEFAULT_MAX_RECURSION_DEPTH,
                DEFAULT_MAX_LOOP_ITERATIONS
        );
    }
}
