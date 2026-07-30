package com.npdev.kernel.procedures;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record ProcedureStep(
        String name,
        ProcedureStepType type,
        String conceptName,
        String idRef,
        String dataRef,
        String capability,
        String capabilityType,
        String adapterId,
        String operation,
        List<String> argRefs,
        String outputKey,
        String eventName,
        String payloadRef,
        String conditionRef,
        String collectionRef,
        String itemKey,
        String valueRef,
        String procedureName,
        String returnRef,
        List<ProcedureStep> thenSteps,
        List<ProcedureStep> elseSteps,
        List<ProcedureStep> steps,
        Map<String, Object> setValues
) {
    public ProcedureStep {
        name = normalizeRequired(name, "name");
        if (type == null) {
            throw new IllegalArgumentException("type must be non-null");
        }
        conceptName = normalizeOptional(conceptName);
        idRef = normalizeOptional(idRef);
        dataRef = normalizeOptional(dataRef);
        capability = normalizeOptional(capability);
        capabilityType = normalizeOptional(capabilityType);
        adapterId = normalizeOptional(adapterId);
        operation = normalizeOptional(operation);
        argRefs = argRefs == null ? List.of() : List.copyOf(argRefs);
        outputKey = normalizeOptional(outputKey);
        eventName = normalizeOptional(eventName);
        payloadRef = normalizeOptional(payloadRef);
        conditionRef = normalizeOptional(conditionRef);
        collectionRef = normalizeOptional(collectionRef);
        itemKey = normalizeOptional(itemKey);
        valueRef = normalizeOptional(valueRef);
        procedureName = normalizeOptional(procedureName);
        returnRef = normalizeOptional(returnRef);
        thenSteps = thenSteps == null ? List.of() : List.copyOf(thenSteps);
        elseSteps = elseSteps == null ? List.of() : List.copyOf(elseSteps);
        steps = steps == null ? List.of() : List.copyOf(steps);
        setValues = setValues == null ? Map.of() : Map.copyOf(setValues);
    }

    public ProcedureStep(
            String name,
            ProcedureStepType type,
            String conceptName,
            String idRef,
            String dataRef,
            String capability,
            String capabilityType,
            String adapterId,
            String operation,
            List<String> argRefs,
            String outputKey,
            String eventName,
            String payloadRef
    ) {
        this(
                name,
                type,
                conceptName,
                idRef,
                dataRef,
                capability,
                capabilityType,
                adapterId,
                operation,
                argRefs,
                outputKey,
                eventName,
                payloadRef,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                Map.of()
        );
    }

    public static ProcedureStep saveConcept(
            String name,
            String conceptName,
            String idRef,
            String dataRef,
            String outputKey
    ) {
        return new ProcedureStep(name, ProcedureStepType.SAVE_CONCEPT, conceptName, idRef, dataRef,
                null, null, null, null, List.of(), outputKey, null, null);
    }

    public static ProcedureStep readConcept(
            String name,
            String conceptName,
            String idRef,
            String outputKey
    ) {
        return new ProcedureStep(name, ProcedureStepType.READ_CONCEPT, conceptName, idRef, null,
                null, null, null, null, List.of(), outputKey, null, null);
    }

    public static ProcedureStep listConcepts(String name, String conceptName, String outputKey) {
        return new ProcedureStep(name, ProcedureStepType.LIST_CONCEPTS, conceptName, null, null,
                null, null, null, null, List.of(), outputKey, null, null);
    }

    public static ProcedureStep runQuery(String name, String queryName, String conceptName, String outputKey) {
        return new ProcedureStep(name, ProcedureStepType.RUN_QUERY, conceptName, null, null,
                null, null, null, queryName, List.of(), outputKey, null, null);
    }

    public static ProcedureStep deleteConcept(String name, String conceptName, String idRef) {
        return new ProcedureStep(name, ProcedureStepType.DELETE_CONCEPT, conceptName, idRef, null,
                null, null, null, null, List.of(), null, null, null);
    }

    public static ProcedureStep callCapability(
            String name,
            String capability,
            String capabilityType,
            String adapterId,
            String operation,
            List<String> argRefs,
            String outputKey
    ) {
        return new ProcedureStep(name, ProcedureStepType.CALL_CAPABILITY, null, null, null,
                capability, capabilityType, adapterId, operation, argRefs, outputKey, null, null);
    }

    public static ProcedureStep publishEvent(String name, String eventName, String payloadRef) {
        return new ProcedureStep(name, ProcedureStepType.PUBLISH_EVENT, null, null, null,
                null, null, null, null, List.of(), null, eventName, payloadRef);
    }

    public static ProcedureStep callProcedure(
            String name,
            String procedureName,
            String payloadRef,
            String outputKey
    ) {
        return new ProcedureStep(name, ProcedureStepType.CALL_PROCEDURE, null, null, null,
                null, null, null, null, List.of(), outputKey, null, payloadRef,
                null, null, null, null, procedureName, null, List.of(), List.of(), List.of(), Map.of());
    }

    public static ProcedureStep ifThenElse(
            String name,
            String conditionRef,
            List<ProcedureStep> thenSteps,
            List<ProcedureStep> elseSteps
    ) {
        return new ProcedureStep(name, ProcedureStepType.IF, null, null, null,
                null, null, null, null, List.of(), null, null, null,
                conditionRef, null, null, null, null, null, thenSteps, elseSteps, List.of(), Map.of());
    }

    public static ProcedureStep forEach(
            String name,
            String collectionRef,
            String itemKey,
            List<ProcedureStep> steps
    ) {
        return new ProcedureStep(name, ProcedureStepType.FOR_EACH, null, null, null,
                null, null, null, null, List.of(), null, null, null,
                null, collectionRef, itemKey, null, null, null, List.of(), List.of(), steps, Map.of());
    }

    public static ProcedureStep mapValue(String name, String valueRef, String outputKey) {
        return new ProcedureStep(name, ProcedureStepType.MAP_VALUE, null, null, null,
                null, null, null, null, List.of(), outputKey, null, null,
                null, null, null, valueRef, null, null, List.of(), List.of(), List.of(), Map.of());
    }

    public static ProcedureStep returnValue(String name, String returnRef) {
        return new ProcedureStep(name, ProcedureStepType.RETURN, null, null, null,
                null, null, null, null, List.of(), null, null, null,
                null, null, null, null, null, returnRef, List.of(), List.of(), List.of(), Map.of());
    }

    public static ProcedureStep patchConcept(
            String name,
            String conceptName,
            String idRef,
            Map<String, Object> setValues,
            String outputKey
    ) {
        return new ProcedureStep(name, ProcedureStepType.PATCH_CONCEPT, conceptName, idRef, null,
                null, null, null, null, List.of(), outputKey, null, null,
                null, null, null, null, null, null, List.of(), List.of(), List.of(), setValues);
    }

    public static ProcedureStepType parseType(String rawType) {
        String normalized = normalizeType(rawType);
        return switch (normalized) {
            case "readconcept" -> ProcedureStepType.READ_CONCEPT;
            case "listconcepts" -> ProcedureStepType.LIST_CONCEPTS;
            case "runquery", "conceptquery" -> ProcedureStepType.RUN_QUERY;
            case "saveconcept", "conceptcreate", "conceptupdate", "conceptmutation" -> ProcedureStepType.SAVE_CONCEPT;
            case "patchconcept" -> ProcedureStepType.PATCH_CONCEPT;
            case "deleteconcept", "conceptdelete" -> ProcedureStepType.DELETE_CONCEPT;
            case "callcapability", "capabilitycall" -> ProcedureStepType.CALL_CAPABILITY;
            case "publishevent", "eventpublish" -> ProcedureStepType.PUBLISH_EVENT;
            case "callprocedure", "procedurecall" -> ProcedureStepType.CALL_PROCEDURE;
            case "if", "condition" -> ProcedureStepType.IF;
            case "foreach", "loop" -> ProcedureStepType.FOR_EACH;
            case "mapvalue", "assign" -> ProcedureStepType.MAP_VALUE;
            case "return" -> ProcedureStepType.RETURN;
            default -> throw new IllegalArgumentException("Unsupported procedure step type: " + rawType);
        };
    }

    private static String normalizeType(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
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
}
