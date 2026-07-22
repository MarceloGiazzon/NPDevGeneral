package com.npdev.kernel;

import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;
import com.npdev.kernel.schema.SchemaObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FlowStepDefinition {
    public enum Type {
        INVARIANT_CHECK,
        CAPABILITY_CALL,
        EMIT_EVENT,
        SCHEDULE_EVENT,
        BRANCH,
        AWAIT_EVENT,
        MAP,
        RETURN,
        /** LIFT-LOOP-P1: bounded iteration over a collection ref, mirroring Procedures'
         * forEach shape (collectionRef/itemKey/nested steps) -- but, unlike Procedures' plain
         * in-memory loop, durably checkpoints progress per iteration (see KernelRunner). */
        FOR_EACH
    }

    public enum InvariantCheckpoint {
        PRE,
        POST
    }

    private final String name;
    private final Type type;
    private final InvariantCheckpoint checkpoint;
    private final String invariantScope;
    private final List<String> invariants;
    private final String capability;
    private final String capabilityType;
    private final String capabilityAdapterId;
    private final CapabilityExecutionPolicy capabilityExecutionPolicy;
    private final SchemaObject capabilityInputSchema;
    private final SchemaObject capabilityOutputSchema;
    private final String operation;
    private final String inputRef;
    private final List<String> argsRefs;
    private final String outputRef;
    private final String eventName;
    private final String payloadRef;
    private final Map<String, String> eventDataRefs;
    private final String condition;
    private final List<FlowStepDefinition> thenSteps;
    private final List<FlowStepDefinition> elseSteps;
    private final String awaitEventName;
    private final String awaitRef;
    private final boolean awaitMatchCorrelation;
    private final Map<String, String> awaitPayloadMatchRefs;
    private final Long delaySeconds;
    private final String mapFromRef;
    private final String mapToRef;
    private final String returnRef;
    private final String collectionRef;
    private final String itemKey;
    private final List<FlowStepDefinition> loopSteps;
    private final Integer maxLoopIterations;
    private final List<FlowStepDefinition> onFailureSteps;

    private FlowStepDefinition(
            String name,
            Type type,
            InvariantCheckpoint checkpoint,
            String invariantScope,
            List<String> invariants,
            String capability,
            String capabilityType,
            String capabilityAdapterId,
            CapabilityExecutionPolicy capabilityExecutionPolicy,
            String operation,
            String inputRef,
            List<String> argsRefs,
            String outputRef,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            String condition,
            List<FlowStepDefinition> thenSteps,
            List<FlowStepDefinition> elseSteps,
            String awaitEventName,
            String awaitRef,
            boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatchRefs,
            Long delaySeconds,
            String mapFromRef,
            String mapToRef,
            String returnRef
    ) {
        this(
                name,
                type,
                checkpoint,
                invariantScope,
                invariants,
                capability,
                capabilityType,
                capabilityAdapterId,
                capabilityExecutionPolicy,
                null,
                null,
                operation,
                inputRef,
                argsRefs,
                outputRef,
                eventName,
                payloadRef,
                eventDataRefs,
                condition,
                thenSteps,
                elseSteps,
                awaitEventName,
                awaitRef,
                awaitMatchCorrelation,
                awaitPayloadMatchRefs,
                delaySeconds,
                mapFromRef,
                mapToRef,
                returnRef
        );
    }

    private FlowStepDefinition(
            String name,
            Type type,
            InvariantCheckpoint checkpoint,
            String invariantScope,
            List<String> invariants,
            String capability,
            String capabilityType,
            String capabilityAdapterId,
            CapabilityExecutionPolicy capabilityExecutionPolicy,
            SchemaObject capabilityInputSchema,
            SchemaObject capabilityOutputSchema,
            String operation,
            String inputRef,
            List<String> argsRefs,
            String outputRef,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            String condition,
            List<FlowStepDefinition> thenSteps,
            List<FlowStepDefinition> elseSteps,
            String awaitEventName,
            String awaitRef,
            boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatchRefs,
            Long delaySeconds,
            String mapFromRef,
            String mapToRef,
            String returnRef
    ) {
        this(
                name, type, checkpoint, invariantScope, invariants, capability, capabilityType,
                capabilityAdapterId, capabilityExecutionPolicy, capabilityInputSchema, capabilityOutputSchema,
                operation, inputRef, argsRefs, outputRef, eventName, payloadRef, eventDataRefs, condition,
                thenSteps, elseSteps, awaitEventName, awaitRef, awaitMatchCorrelation, awaitPayloadMatchRefs,
                delaySeconds, mapFromRef, mapToRef, returnRef, null, null, List.of(), null
        );
    }

    /** LIFT-LOOP-P1: canonical constructor, adding {@code collectionRef}/{@code itemKey}/
     * {@code loopSteps}/{@code maxLoopIterations} for {@link Type#FOR_EACH}. */
    private FlowStepDefinition(
            String name,
            Type type,
            InvariantCheckpoint checkpoint,
            String invariantScope,
            List<String> invariants,
            String capability,
            String capabilityType,
            String capabilityAdapterId,
            CapabilityExecutionPolicy capabilityExecutionPolicy,
            SchemaObject capabilityInputSchema,
            SchemaObject capabilityOutputSchema,
            String operation,
            String inputRef,
            List<String> argsRefs,
            String outputRef,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            String condition,
            List<FlowStepDefinition> thenSteps,
            List<FlowStepDefinition> elseSteps,
            String awaitEventName,
            String awaitRef,
            boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatchRefs,
            Long delaySeconds,
            String mapFromRef,
            String mapToRef,
            String returnRef,
            String collectionRef,
            String itemKey,
            List<FlowStepDefinition> loopSteps,
            Integer maxLoopIterations
    ) {
        this.name = requireNonBlank(name, "name");
        this.type = type;
        this.checkpoint = checkpoint;
        this.invariantScope = invariantScope;
        this.invariants = invariants == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(invariants));
        this.capability = capability;
        this.capabilityType = capabilityType;
        this.capabilityAdapterId = capabilityAdapterId;
        this.capabilityExecutionPolicy = capabilityExecutionPolicy;
        this.capabilityInputSchema = capabilityInputSchema;
        this.capabilityOutputSchema = capabilityOutputSchema;
        this.operation = operation;
        this.inputRef = inputRef;
        this.argsRefs = argsRefs == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(argsRefs));
        this.outputRef = outputRef;
        this.eventName = eventName;
        this.payloadRef = payloadRef;
        this.eventDataRefs = eventDataRefs == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(eventDataRefs));
        this.condition = condition;
        this.thenSteps = thenSteps == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(thenSteps));
        this.elseSteps = elseSteps == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(elseSteps));
        this.awaitEventName = awaitEventName;
        this.awaitRef = awaitRef;
        this.awaitMatchCorrelation = awaitMatchCorrelation;
        this.awaitPayloadMatchRefs = awaitPayloadMatchRefs == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(awaitPayloadMatchRefs));
        this.delaySeconds = delaySeconds;
        this.mapFromRef = mapFromRef;
        this.mapToRef = mapToRef;
        this.returnRef = returnRef;
        this.collectionRef = collectionRef;
        this.itemKey = itemKey;
        this.loopSteps = loopSteps == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(loopSteps));
        this.maxLoopIterations = maxLoopIterations;
        this.onFailureSteps = List.of();
    }

    /**
     * LNCH-17: copy constructor used only by {@link #withOnFailure}, since {@code onFailureSteps}
     * is attachable to any step type/factory method rather than threaded through every one of
     * them individually.
     */
    private FlowStepDefinition(FlowStepDefinition source, List<FlowStepDefinition> onFailureSteps) {
        this.name = source.name;
        this.type = source.type;
        this.checkpoint = source.checkpoint;
        this.invariantScope = source.invariantScope;
        this.invariants = source.invariants;
        this.capability = source.capability;
        this.capabilityType = source.capabilityType;
        this.capabilityAdapterId = source.capabilityAdapterId;
        this.capabilityExecutionPolicy = source.capabilityExecutionPolicy;
        this.capabilityInputSchema = source.capabilityInputSchema;
        this.capabilityOutputSchema = source.capabilityOutputSchema;
        this.operation = source.operation;
        this.inputRef = source.inputRef;
        this.argsRefs = source.argsRefs;
        this.outputRef = source.outputRef;
        this.eventName = source.eventName;
        this.payloadRef = source.payloadRef;
        this.eventDataRefs = source.eventDataRefs;
        this.condition = source.condition;
        this.thenSteps = source.thenSteps;
        this.elseSteps = source.elseSteps;
        this.awaitEventName = source.awaitEventName;
        this.awaitRef = source.awaitRef;
        this.awaitMatchCorrelation = source.awaitMatchCorrelation;
        this.awaitPayloadMatchRefs = source.awaitPayloadMatchRefs;
        this.delaySeconds = source.delaySeconds;
        this.mapFromRef = source.mapFromRef;
        this.mapToRef = source.mapToRef;
        this.returnRef = source.returnRef;
        this.collectionRef = source.collectionRef;
        this.itemKey = source.itemKey;
        this.loopSteps = source.loopSteps;
        this.maxLoopIterations = source.maxLoopIterations;
        this.onFailureSteps = onFailureSteps == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(onFailureSteps));
    }

    /** LNCH-17: attaches declared compensation steps to an already-built step. */
    public FlowStepDefinition withOnFailure(List<FlowStepDefinition> onFailureSteps) {
        return new FlowStepDefinition(this, onFailureSteps);
    }

    public List<FlowStepDefinition> getOnFailureSteps() {
        return onFailureSteps;
    }

    public static FlowStepDefinition invariant(String name, InvariantCheckpoint checkpoint, List<String> invariants) {
        return invariant(name, null, checkpoint, invariants);
    }

    public static FlowStepDefinition invariant(
            String name,
            String scope,
            InvariantCheckpoint checkpoint,
            List<String> invariants
    ) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint must be non-null");
        }
        return new FlowStepDefinition(name, Type.INVARIANT_CHECK, checkpoint, scope, invariants,
                null, null, null, null, null, null, null, null, null, null, Map.of(), null,
                List.of(), List.of(), null, null, true, Map.of(), null, null, null, null);
    }

    public static FlowStepDefinition capabilityCall(
            String name,
            String capability,
            String operation,
            String inputRef,
            String outputRef
    ) {
        List<String> argRefs = (inputRef == null || inputRef.isBlank()) ? List.of() : List.of(inputRef);
        return capabilityCall(name, capability, null, operation, argRefs, outputRef);
    }

    public static FlowStepDefinition capabilityCall(
            String name,
            String capability,
            String capabilityType,
            String capabilityAdapterId,
            String operation,
            List<String> argRefs,
            String outputRef
    ) {
        return capabilityCall(
                name,
                capability,
                capabilityType,
                capabilityAdapterId,
                operation,
                argRefs,
                outputRef,
                CapabilityExecutionPolicy.defaults()
        );
    }

    public static FlowStepDefinition capabilityCall(
            String name,
            String capability,
            String capabilityType,
            String capabilityAdapterId,
            String operation,
            List<String> argRefs,
            String outputRef,
            CapabilityExecutionPolicy capabilityExecutionPolicy
    ) {
        return capabilityCall(
                name,
                capability,
                capabilityType,
                capabilityAdapterId,
                operation,
                argRefs,
                outputRef,
                capabilityExecutionPolicy,
                null,
                null
        );
    }

    public static FlowStepDefinition capabilityCall(
            String name,
            String capability,
            String capabilityType,
            String capabilityAdapterId,
            String operation,
            List<String> argRefs,
            String outputRef,
            CapabilityExecutionPolicy capabilityExecutionPolicy,
            SchemaObject inputSchema,
            SchemaObject outputSchema
    ) {
        return new FlowStepDefinition(
                name,
                Type.CAPABILITY_CALL,
                null,
                null,
                List.of(),
                requireNonBlank(capability, "capability"),
                capabilityType,
                requireNonBlank(capabilityAdapterId, "capabilityAdapterId"),
                capabilityExecutionPolicy == null ? CapabilityExecutionPolicy.defaults() : capabilityExecutionPolicy,
                inputSchema,
                outputSchema,
                requireNonBlank(operation, "operation"),
                null,
                argRefs,
                outputRef,
                null,
                null,
                Map.of(),
                null,
                List.of(),
                List.of(),
                null,
                null,
                true,
                Map.of(),
                null,
                null,
                null,
                null
        );
    }

    public static FlowStepDefinition capabilityCall(
            String name,
            String capability,
            String capabilityType,
            String operation,
            List<String> argRefs,
            String outputRef
    ) {
        return capabilityCall(
                name,
                capability,
                capabilityType,
                operation,
                argRefs,
                outputRef,
                CapabilityExecutionPolicy.defaults()
        );
    }

    public static FlowStepDefinition capabilityCall(
            String name,
            String capability,
            String capabilityType,
            String operation,
            List<String> argRefs,
            String outputRef,
            CapabilityExecutionPolicy capabilityExecutionPolicy
    ) {
        return capabilityCall(
                name,
                capability,
                capabilityType,
                operation,
                argRefs,
                outputRef,
                capabilityExecutionPolicy,
                null,
                null
        );
    }

    public static FlowStepDefinition capabilityCall(
            String name,
            String capability,
            String capabilityType,
            String operation,
            List<String> argRefs,
            String outputRef,
            CapabilityExecutionPolicy capabilityExecutionPolicy,
            SchemaObject inputSchema,
            SchemaObject outputSchema
    ) {
        return new FlowStepDefinition(
                name,
                Type.CAPABILITY_CALL,
                null,
                null,
                List.of(),
                requireNonBlank(capability, "capability"),
                capabilityType,
                null,
                capabilityExecutionPolicy == null ? CapabilityExecutionPolicy.defaults() : capabilityExecutionPolicy,
                inputSchema,
                outputSchema,
                requireNonBlank(operation, "operation"),
                null,
                argRefs,
                outputRef,
                null,
                null,
                Map.of(),
                null,
                List.of(),
                List.of(),
                null,
                null,
                true,
                Map.of(),
                null,
                null,
                null,
                null
        );
    }

    public static FlowStepDefinition emitEvent(String name, String eventName, String payloadRef) {
        return emitEvent(name, eventName, payloadRef, Map.of());
    }

    public static FlowStepDefinition emitEvent(
            String name,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs
    ) {
        return new FlowStepDefinition(
                name,
                Type.EMIT_EVENT,
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
                requireNonBlank(eventName, "eventName"),
                payloadRef,
                eventDataRefs,
                null,
                List.of(),
                List.of(),
                null,
                null,
                true,
                Map.of(),
                null,
                null,
                null,
                null
        );
    }

    public static FlowStepDefinition scheduleEvent(
            String name,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            long delaySeconds
    ) {
        if (delaySeconds < 0L) {
            throw new IllegalArgumentException("delaySeconds must be >= 0");
        }
        return new FlowStepDefinition(
                name,
                Type.SCHEDULE_EVENT,
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
                requireNonBlank(eventName, "eventName"),
                payloadRef,
                eventDataRefs,
                null,
                List.of(),
                List.of(),
                null,
                null,
                true,
                Map.of(),
                delaySeconds,
                null,
                null,
                null
        );
    }

    public static FlowStepDefinition branch(
            String name,
            String condition,
            List<FlowStepDefinition> thenSteps,
            List<FlowStepDefinition> elseSteps
    ) {
        return new FlowStepDefinition(
                name,
                Type.BRANCH,
                null,
                null,
                List.of(),
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
                Map.of(),
                requireNonBlank(condition, "condition"),
                thenSteps,
                elseSteps,
                null,
                null,
                true,
                Map.of(),
                null,
                null,
                null,
                null
        );
    }

    public static FlowStepDefinition awaitEvent(String name, String eventName, String awaitRef) {
        return awaitEvent(name, eventName, awaitRef, true, Map.of());
    }

    public static FlowStepDefinition awaitEvent(
            String name,
            String eventName,
            String awaitRef,
            boolean matchCorrelation,
            Map<String, String> payloadMatchRefs
    ) {
        return new FlowStepDefinition(
                name,
                Type.AWAIT_EVENT,
                null,
                null,
                List.of(),
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
                Map.of(),
                null,
                List.of(),
                List.of(),
                requireNonBlank(eventName, "eventName"),
                awaitRef,
                matchCorrelation,
                payloadMatchRefs,
                null,
                null,
                null,
                null
        );
    }

    public static FlowStepDefinition map(String name, String fromRef, String toRef) {
        return new FlowStepDefinition(
                name,
                Type.MAP,
                null,
                null,
                List.of(),
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
                Map.of(),
                null,
                List.of(),
                List.of(),
                null,
                null,
                true,
                Map.of(),
                null,
                requireNonBlank(fromRef, "fromRef"),
                requireNonBlank(toRef, "toRef"),
                null
        );
    }

    public static FlowStepDefinition forEach(
            String name,
            String collectionRef,
            String itemKey,
            List<FlowStepDefinition> loopSteps,
            Integer maxLoopIterations
    ) {
        if (loopSteps == null || loopSteps.isEmpty()) {
            throw new IllegalArgumentException("forEach requires at least one loop step");
        }
        return new FlowStepDefinition(
                name,
                Type.FOR_EACH,
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
                List.of(),
                null,
                null,
                null,
                Map.of(),
                null,
                List.of(),
                List.of(),
                null,
                null,
                true,
                Map.of(),
                null,
                null,
                null,
                null,
                requireNonBlank(collectionRef, "collectionRef"),
                requireNonBlank(itemKey, "itemKey"),
                loopSteps,
                maxLoopIterations
        );
    }

    public static FlowStepDefinition returnValue(String name, String returnRef) {
        return new FlowStepDefinition(
                name,
                Type.RETURN,
                null,
                null,
                List.of(),
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
                Map.of(),
                null,
                List.of(),
                List.of(),
                null,
                null,
                true,
                Map.of(),
                null,
                null,
                null,
                requireNonBlank(returnRef, "returnRef")
        );
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public InvariantCheckpoint getCheckpoint() {
        return checkpoint;
    }

    public String getInvariantScope() {
        return invariantScope;
    }

    public List<String> getInvariants() {
        return invariants;
    }

    public String getCapability() {
        return capability;
    }

    public String getCapabilityType() {
        return capabilityType;
    }

    public String getCapabilityAdapterId() {
        return capabilityAdapterId;
    }

    public CapabilityExecutionPolicy getCapabilityExecutionPolicy() {
        return capabilityExecutionPolicy;
    }

    public SchemaObject getCapabilityInputSchema() {
        return capabilityInputSchema;
    }

    public SchemaObject getCapabilityOutputSchema() {
        return capabilityOutputSchema;
    }

    public String getOperation() {
        return operation;
    }

    public String getInputRef() {
        return inputRef;
    }

    public List<String> getArgsRefs() {
        return argsRefs;
    }

    public String getOutputRef() {
        return outputRef;
    }

    public String getEventName() {
        return eventName;
    }

    public String getPayloadRef() {
        return payloadRef;
    }

    public Map<String, String> getEventDataRefs() {
        return eventDataRefs;
    }

    public String getCondition() {
        return condition;
    }

    public List<FlowStepDefinition> getThenSteps() {
        return thenSteps;
    }

    public List<FlowStepDefinition> getElseSteps() {
        return elseSteps;
    }

    public String getAwaitEventName() {
        return awaitEventName;
    }

    public String getAwaitRef() {
        return awaitRef;
    }

    public boolean isAwaitMatchCorrelation() {
        return awaitMatchCorrelation;
    }

    public Map<String, String> getAwaitPayloadMatchRefs() {
        return awaitPayloadMatchRefs;
    }

    public Long getDelaySeconds() {
        return delaySeconds;
    }

    public String getMapFromRef() {
        return mapFromRef;
    }

    public String getMapToRef() {
        return mapToRef;
    }

    public String getReturnRef() {
        return returnRef;
    }

    public String getCollectionRef() {
        return collectionRef;
    }

    public String getItemKey() {
        return itemKey;
    }

    public List<FlowStepDefinition> getLoopSteps() {
        return loopSteps;
    }

    public Integer getMaxLoopIterations() {
        return maxLoopIterations;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }
}
