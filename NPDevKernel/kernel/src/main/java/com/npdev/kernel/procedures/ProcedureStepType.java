package com.npdev.kernel.procedures;

public enum ProcedureStepType {
    READ_CONCEPT,
    LIST_CONCEPTS,
    RUN_QUERY,
    SAVE_CONCEPT,
    PATCH_CONCEPT,
    DELETE_CONCEPT,
    CALL_CAPABILITY,
    PUBLISH_EVENT,
    IF,
    FOR_EACH,
    MAP_LIST,
    MAP_VALUE,
    COMPUTE_VALUE,
    /** FORM P3-T5c: a flow {@code set} step -- one expression of the one expression language, evaluated strictly. */
    EVALUATE,
    /** FORM P3-T5c: a flow {@code map} step with {@code select} and no collection -- builds ONE object from literals and refs. */
    BUILD_OBJECT,
    RETURN
}
