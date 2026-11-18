package com.npdev.kernel.procedures;

/**
 * FORM P3-T7: the concept-bound step types a flow runs through {@link DefaultProcedureExecutor}. Everything else a
 * flow does (capability calls, events, branching, loops, return, persistence writes) is the flow engine's own step.
 */
public enum ProcedureStepType {
    READ_CONCEPT,
    LIST_CONCEPTS,
    RUN_QUERY,
    PATCH_CONCEPT,
    DELETE_CONCEPT,
    MAP_LIST,
    /** FORM P3-T5c: a flow {@code set} step -- one expression of the one expression language, evaluated strictly. */
    EVALUATE,
    /** FORM P3-T5c: a flow {@code map} step with {@code select} and no collection -- builds ONE object from literals and refs. */
    BUILD_OBJECT
}
