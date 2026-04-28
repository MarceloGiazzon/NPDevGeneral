package com.npdev.kernel.procedures;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.ports.CapabilityDispatcher;
import com.npdev.kernel.ports.EventBus;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DefaultProcedureExecutor implements ProcedureExecutor {
    private final ConceptGateway conceptGateway;
    private final CapabilityDispatcher capabilityDispatcher;
    private final EventBus eventBus;
    private final Map<String, ProcedureDefinition> procedureRegistry;
    private final ProcedureExecutionLimits limits;

    public DefaultProcedureExecutor(
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus
    ) {
        this(conceptGateway, capabilityDispatcher, eventBus, Map.of());
    }

    public DefaultProcedureExecutor(
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus,
            Map<String, ProcedureDefinition> procedureRegistry
    ) {
        this(conceptGateway, capabilityDispatcher, eventBus, procedureRegistry, ProcedureExecutionLimits.defaults());
    }

    public DefaultProcedureExecutor(
            ConceptGateway conceptGateway,
            CapabilityDispatcher capabilityDispatcher,
            EventBus eventBus,
            Map<String, ProcedureDefinition> procedureRegistry,
            ProcedureExecutionLimits limits
    ) {
        this.conceptGateway = Objects.requireNonNull(conceptGateway, "conceptGateway");
        this.capabilityDispatcher = Objects.requireNonNull(capabilityDispatcher, "capabilityDispatcher");
        this.eventBus = eventBus == null ? event -> { } : eventBus;
        this.procedureRegistry = procedureRegistry == null ? Map.of() : Map.copyOf(procedureRegistry);
        this.limits = limits == null ? ProcedureExecutionLimits.defaults() : limits;
    }

    @Override
    public ProcedureExecutionResult execute(
            ProcedureDefinition definition,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        return executeDefinition(definition, input, context, new ProcedureExecutionScope(limits), 1);
    }

    private ProcedureExecutionResult executeDefinition(
            ProcedureDefinition definition,
            Map<String, Object> input,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        Objects.requireNonNull(definition, "definition");
        ExecutionContext effectiveContext = context == null ? ExecutionContext.anonymous() : context;
        Map<String, Object> state = new LinkedHashMap<>();
        if (input != null) {
            state.putAll(input);
        }
        state.putIfAbsent("input", input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input)));

        List<ProcedureStepResult> stepResults = new ArrayList<>();
        for (int index = 0; index < definition.steps().size(); index++) {
            ProcedureStep step = definition.steps().get(index);
            ProcedureStepResult result = executeStepWithBudget(
                    definition,
                    step,
                    index,
                    state,
                    effectiveContext,
                    scope,
                    recursionDepth
            );
            stepResults.add(result);
            if (!result.ok()) {
                return ProcedureExecutionResult.failure(state, stepResults, result.code(), result.message());
            }
            if (step.type() == ProcedureStepType.RETURN) {
                return ProcedureExecutionResult.success(state, stepResults);
            }
        }
        return ProcedureExecutionResult.success(state, stepResults);
    }

    private ProcedureStepResult executeStepWithBudget(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        ProcedureStepResult budget = scope.beforeStep(step);
        if (!budget.ok()) {
            return budget;
        }
        try {
            return executeStep(definition, step, stepIndex, state, context, scope, recursionDepth);
        } catch (RuntimeException exception) {
            return ProcedureStepResult.failure(
                    step,
                    "PROCEDURE_STEP_FAILED",
                    exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage()
            );
        }
    }

    private ProcedureStepResult executeStep(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        return switch (step.type()) {
            case SAVE_CONCEPT -> saveConcept(step, state, context);
            case READ_CONCEPT -> readConcept(step, state, context);
            case LIST_CONCEPTS -> listConcepts(step, state, context);
            case RUN_QUERY -> runQuery(step, state, context);
            case DELETE_CONCEPT -> deleteConcept(step, state, context);
            case CALL_CAPABILITY -> callCapability(step, state, context);
            case PUBLISH_EVENT -> publishEvent(definition, step, stepIndex, state, context);
            case CALL_PROCEDURE -> callProcedure(step, state, context, scope, recursionDepth);
            case IF -> ifThenElse(definition, step, stepIndex, state, context, scope, recursionDepth);
            case FOR_EACH -> forEach(definition, step, stepIndex, state, context, scope, recursionDepth);
            case MAP_VALUE -> mapValue(step, state);
            case RETURN -> returnValue(step, state);
        };
    }

    private ProcedureStepResult saveConcept(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        String id = requireString(state, step.idRef(), step.name(), "idRef");
        Map<String, Object> data = requireMap(state, step.dataRef(), step.name(), "dataRef");
        ConceptRecord saved = conceptGateway.save(new ConceptWriteRequest(step.conceptName(), id, null, data), context);
        putOutput(state, step.outputKey(), saved);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult readConcept(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        String id = requireString(state, step.idRef(), step.name(), "idRef");
        Optional<ConceptRecord> record = conceptGateway.read(new ConceptReadRequest(step.conceptName(), id, null), context);
        if (record.isEmpty()) {
            return ProcedureStepResult.failure(step, "CONCEPT_NOT_FOUND", "Concept record not found: " + step.conceptName() + ":" + id);
        }
        putOutput(state, step.outputKey(), record.orElseThrow());
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult listConcepts(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        List<ConceptRecord> records = conceptGateway.list(new ConceptListRequest(step.conceptName(), null), context);
        putOutput(state, step.outputKey(), records);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult runQuery(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        if (step.conceptName() == null || step.conceptName().isBlank()) {
            return ProcedureStepResult.failure(step, "QUERY_UNSUPPORTED", "Procedure query steps require conceptName in the current runtime.");
        }
        List<ConceptRecord> records = conceptGateway.list(new ConceptListRequest(step.conceptName(), null), context);
        putOutput(state, step.outputKey(), records);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult deleteConcept(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        String id = requireString(state, step.idRef(), step.name(), "idRef");
        conceptGateway.delete(new ConceptReadRequest(step.conceptName(), id, null), context);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult callCapability(ProcedureStep step, Map<String, Object> state, ExecutionContext context) {
        List<Object> args = new ArrayList<>();
        for (String ref : step.argRefs()) {
            args.add(resolve(state, ref));
        }
        CapabilityResult result = capabilityDispatcher.invoke(
                new CapabilityCall(
                        step.capability(),
                        step.capabilityType(),
                        step.adapterId(),
                        step.operation(),
                        args,
                        correlationId(context),
                        context.idempotencyKey()
                ),
                state
        );
        if (!result.ok()) {
            String code = result.error() == null ? "CAPABILITY_FAILED" : result.error().code();
            String message = result.error() == null ? "Capability failed." : result.error().message();
            return ProcedureStepResult.failure(step, code, message);
        }
        putOutput(state, step.outputKey(), result.value());
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult publishEvent(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context
    ) {
        Map<String, Object> payload = requireMap(state, step.payloadRef(), step.name(), "payloadRef");
        eventBus.publish(EventEnvelope.create(
                step.eventName(),
                payload,
                correlationId(context),
                UUID.randomUUID().toString(),
                definition.name(),
                stepIndex,
                context.tenantId(),
                context.actorId()
        ));
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult callProcedure(
            ProcedureStep step,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        if (step.procedureName() == null || step.procedureName().isBlank()) {
            return ProcedureStepResult.failure(step, "PROCEDURE_REQUIRED", "Procedure step requires procedureName.");
        }
        if (recursionDepth >= limits.maxRecursionDepth()) {
            return ProcedureStepResult.failure(
                    step,
                    "PROCEDURE_RECURSION_LIMIT_EXCEEDED",
                    "Procedure recursion depth exceeded maxRecursionDepth=" + limits.maxRecursionDepth()
            );
        }
        ProcedureDefinition nestedDefinition = procedureRegistry.get(step.procedureName());
        if (nestedDefinition == null) {
            return ProcedureStepResult.failure(step, "PROCEDURE_NOT_FOUND", "Procedure not found: " + step.procedureName());
        }
        Map<String, Object> input = step.payloadRef() == null ? Map.of() : requireMap(state, step.payloadRef(), step.name(), "payloadRef");
        ProcedureExecutionResult result = executeDefinition(nestedDefinition, input, context, scope, recursionDepth + 1);
        if (!result.ok()) {
            return ProcedureStepResult.failure(step, result.failureCode(), result.failureMessage());
        }
        Object output = result.state().containsKey("return") ? result.state().get("return") : result.state();
        putOutput(state, step.outputKey(), output);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult ifThenElse(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        boolean condition = truthy(resolve(state, step.conditionRef()));
        List<ProcedureStep> selectedSteps = condition ? step.thenSteps() : step.elseSteps();
        ProcedureStepResult nestedResult = executeNestedSteps(
                definition,
                selectedSteps,
                stepIndex,
                state,
                context,
                scope,
                recursionDepth
        );
        return nestedResult.ok() ? ProcedureStepResult.success(step) : nestedResult;
    }

    private ProcedureStepResult forEach(
            ProcedureDefinition definition,
            ProcedureStep step,
            int stepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        Iterable<?> items = toIterable(resolve(state, step.collectionRef()));
        if (items == null) {
            return ProcedureStepResult.failure(step, "COLLECTION_REQUIRED", "Procedure forEach requires an iterable collection.");
        }
        String itemKey = step.itemKey() == null ? "item" : step.itemKey();
        Object previous = state.get(itemKey);
        boolean hadPrevious = state.containsKey(itemKey);
        int iterations = 0;
        try {
            for (Object item : items) {
                iterations++;
                if (iterations > limits.maxLoopIterations()) {
                    return ProcedureStepResult.failure(
                            step,
                            "PROCEDURE_LOOP_LIMIT_EXCEEDED",
                            "Procedure loop exceeded maxLoopIterations=" + limits.maxLoopIterations()
                    );
                }
                state.put(itemKey, item);
                ProcedureStepResult nestedResult = executeNestedSteps(
                        definition,
                        step.steps(),
                        stepIndex,
                        state,
                        context,
                        scope,
                        recursionDepth
                );
                if (!nestedResult.ok()) {
                    return nestedResult;
                }
            }
        } finally {
            if (hadPrevious) {
                state.put(itemKey, previous);
            } else {
                state.remove(itemKey);
            }
        }
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult mapValue(ProcedureStep step, Map<String, Object> state) {
        Object value = resolve(state, step.valueRef());
        putOutput(state, step.outputKey(), value);
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult returnValue(ProcedureStep step, Map<String, Object> state) {
        state.put("return", resolve(state, step.returnRef()));
        return ProcedureStepResult.success(step);
    }

    private ProcedureStepResult executeNestedSteps(
            ProcedureDefinition definition,
            List<ProcedureStep> steps,
            int parentStepIndex,
            Map<String, Object> state,
            ExecutionContext context,
            ProcedureExecutionScope scope,
            int recursionDepth
    ) {
        for (int offset = 0; offset < steps.size(); offset++) {
            ProcedureStep child = steps.get(offset);
            ProcedureStepResult childResult = executeStepWithBudget(
                    definition,
                    child,
                    parentStepIndex + offset + 1,
                    state,
                    context,
                    scope,
                    recursionDepth
            );
            if (!childResult.ok()) {
                return childResult;
            }
            if (child.type() == ProcedureStepType.RETURN) {
                return childResult;
            }
        }
        return ProcedureStepResult.success(new ProcedureStep(
                definition.name() + ".nested",
                ProcedureStepType.MAP_VALUE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of()
        ));
    }

    private static void putOutput(Map<String, Object> state, String outputKey, Object value) {
        if (outputKey != null && !outputKey.isBlank()) {
            state.put(outputKey, value);
        }
    }

    private static String requireString(Map<String, Object> state, String ref, String stepName, String field) {
        Object value = resolve(state, ref);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Procedure step " + stepName + " requires non-blank " + field + ": " + ref);
        }
        return String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Map<String, Object> state, String ref, String stepName, String field) {
        Object value = resolve(state, ref);
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> out = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> out.put(String.valueOf(key), item));
            return out;
        }
        if (value instanceof ConceptRecord record) {
            return record.data();
        }
        throw new IllegalArgumentException("Procedure step " + stepName + " requires map " + field + ": " + ref);
    }

    private static Object resolve(Map<String, Object> state, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String normalized = ref.trim();
        if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        if (state.containsKey(normalized)) {
            return state.get(normalized);
        }
        String[] parts = normalized.split("\\.");
        Object current = state.get(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            if (current instanceof ConceptRecord record) {
                current = record.data();
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[index]);
            } else {
                return null;
            }
        }
        return current;
    }

    private static String correlationId(ExecutionContext context) {
        String existing = context.correlationId();
        return existing == null || existing.isBlank() ? UUID.randomUUID().toString() : existing;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static Iterable<?> toIterable(Object value) {
        if (value instanceof Iterable<?> iterable) {
            return iterable;
        }
        if (value instanceof Collection<?> collection) {
            return collection;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                items.add(Array.get(value, index));
            }
            return items;
        }
        return null;
    }

    private static final class ProcedureExecutionScope {
        private final ProcedureExecutionLimits limits;
        private int stepsExecuted;

        private ProcedureExecutionScope(ProcedureExecutionLimits limits) {
            this.limits = limits;
        }

        private ProcedureStepResult beforeStep(ProcedureStep step) {
            stepsExecuted++;
            if (stepsExecuted > limits.maxSteps()) {
                return ProcedureStepResult.failure(
                        step,
                        "PROCEDURE_STEP_LIMIT_EXCEEDED",
                        "Procedure exceeded maxSteps=" + limits.maxSteps()
                );
            }
            return ProcedureStepResult.success(step);
        }
    }
}
