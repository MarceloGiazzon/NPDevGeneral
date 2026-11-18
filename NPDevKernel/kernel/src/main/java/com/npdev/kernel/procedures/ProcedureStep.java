package com.npdev.kernel.procedures;

import java.util.Map;

/**
 * FORM P3-T7: one concept-bound step a flow runs through {@link DefaultProcedureExecutor} (the flow engine carries it
 * as a one-step definition). Only the fields those step types read remain; control flow, capability calls, events and
 * persistence writes are the flow engine's own steps and have no representation here.
 *
 * <p>Field use by type: READ_CONCEPT {@code conceptName, idRef, outputKey}; LIST_CONCEPTS {@code conceptName,
 * outputKey}; RUN_QUERY {@code conceptName, operation (the query name), outputKey, queryParams}; DELETE_CONCEPT
 * {@code conceptName, idRef}; PATCH_CONCEPT {@code conceptName, idRef, setValues, createIfMissing, outputKey};
 * MAP_LIST {@code collectionRef, itemKey, setValues (the select), outputKey}; BUILD_OBJECT {@code setValues,
 * outputKey}; EVALUATE {@code valueRef (the expression text), outputKey}.
 */
public record ProcedureStep(
        String name,
        ProcedureStepType type,
        String conceptName,
        String idRef,
        String operation,
        String outputKey,
        String collectionRef,
        String itemKey,
        Object valueRef,
        Map<String, Object> setValues,
        boolean createIfMissing,
        Map<String, Object> queryParams
) {
    /** K2: {@code runQuery} step parameters (name -> literal or {@code $ref}). */
    public ProcedureStep withQueryParams(Map<String, Object> params) {
        return new ProcedureStep(name, type, conceptName, idRef, operation, outputKey, collectionRef, itemKey,
                valueRef, setValues, createIfMissing, params);
    }

    public ProcedureStep {
        name = normalizeRequired(name, "name");
        if (type == null) {
            throw new IllegalArgumentException("type must be non-null");
        }
        conceptName = normalizeOptional(conceptName);
        idRef = normalizeOptional(idRef);
        operation = normalizeOptional(operation);
        outputKey = normalizeOptional(outputKey);
        collectionRef = normalizeOptional(collectionRef);
        itemKey = normalizeOptional(itemKey);
        valueRef = normalizeOptionalValue(valueRef);
        // Map.copyOf rejects null VALUES, but patchConcept.set may clear a field with an explicit null (REG-224, same fix
        // as ProcedureStepAst/CompiledProcedureStep). Consumed by key, so an unmodifiable LinkedHashMap is enough.
        setValues = setValues == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(setValues));
        queryParams = queryParams == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(queryParams));
    }

    public static ProcedureStep readConcept(String name, String conceptName, String idRef, String outputKey) {
        return new ProcedureStep(name, ProcedureStepType.READ_CONCEPT, conceptName, idRef, null, outputKey,
                null, null, null, Map.of(), false, Map.of());
    }

    public static ProcedureStep listConcepts(String name, String conceptName, String outputKey) {
        return new ProcedureStep(name, ProcedureStepType.LIST_CONCEPTS, conceptName, null, null, outputKey,
                null, null, null, Map.of(), false, Map.of());
    }

    public static ProcedureStep runQuery(String name, String queryName, String conceptName, String outputKey) {
        return new ProcedureStep(name, ProcedureStepType.RUN_QUERY, conceptName, null, queryName, outputKey,
                null, null, null, Map.of(), false, Map.of());
    }

    public static ProcedureStep deleteConcept(String name, String conceptName, String idRef) {
        return new ProcedureStep(name, ProcedureStepType.DELETE_CONCEPT, conceptName, idRef, null, null,
                null, null, null, Map.of(), false, Map.of());
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A / Gap 6): a list transform/comprehension --
     * builds a NEW list, one output object per input item. {@code selectValues} is resolved per-item via the
     * literal-vs-{@code $ref} convention {@code patchConcept}'s {@code setValues} established (reusing {@code setValues}
     * itself, since the resolution rule -- not the field name -- is what {@code select} borrows).
     */
    public static ProcedureStep mapList(
            String name,
            String collectionRef,
            String itemKey,
            Map<String, Object> selectValues,
            String outputKey
    ) {
        return new ProcedureStep(name, ProcedureStepType.MAP_LIST, null, null, null, outputKey,
                collectionRef, itemKey, null, selectValues, false, Map.of());
    }

    /** FORM P3-T5c: the flow {@code map} step without a collection: one object whose keys are the select keys (literal-or-ref values). */
    public static ProcedureStep buildObject(String name, Map<String, Object> selectValues, String outputKey) {
        return new ProcedureStep(name, ProcedureStepType.BUILD_OBJECT, null, null, null, outputKey,
                null, null, null, selectValues, false, Map.of());
    }

    /**
     * FORM P3-T5c: the flow {@code set} step. {@code valueRef} holds the TEXT of one expression of the one
     * expression language (see {@code docs/EXPRESSIONS.md}); every {@code $ref} in it resolves strictly.
     */
    public static ProcedureStep evaluate(String name, String expression, String outputKey) {
        return new ProcedureStep(name, ProcedureStepType.EVALUATE, null, null, null, outputKey,
                null, null, expression, Map.of(), false, Map.of());
    }

    public static ProcedureStep patchConcept(
            String name,
            String conceptName,
            String idRef,
            Map<String, Object> setValues,
            String outputKey
    ) {
        return patchConcept(name, conceptName, idRef, setValues, outputKey, false);
    }

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1B): {@code createIfMissing} opts into the create half REG-77 found
     * missing -- on {@code CONCEPT_NOT_FOUND}, build a new record from {@code setValues} with a generated id instead of
     * failing. Defaults to {@code false}, preserving REG-75's deliberate patch-not-upsert semantics.
     */
    public static ProcedureStep patchConcept(
            String name,
            String conceptName,
            String idRef,
            Map<String, Object> setValues,
            String outputKey,
            boolean createIfMissing
    ) {
        return new ProcedureStep(name, ProcedureStepType.PATCH_CONCEPT, conceptName, idRef, null, outputKey,
                null, null, null, setValues, createIfMissing, Map.of());
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    /**
     * REG-86: {@code valueRef} is an {@code Object}, not a {@code String} -- a literal array/object/number/boolean passes
     * through unchanged; only a {@code String} gets the same trim-to-null treatment {@link #normalizeOptional} gives
     * every other ref field.
     */
    private static Object normalizeOptionalValue(Object value) {
        if (value instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isBlank() ? null : trimmed;
        }
        return value;
    }
}
