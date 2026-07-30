package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CompiledFlowStep {
    private final String name;
    private final String type;
    private final String checkpoint;
    private final String scope;
    private final List<String> invariants;
    private final String eventName;
    private final String payloadRef;
    private final Map<String, String> eventDataRefs;
    private final String condition;
    private final List<CompiledFlowStep> thenSteps;
    private final List<CompiledFlowStep> elseSteps;
    private final String awaitEventName;
    private final String awaitRef;
    private final Boolean awaitMatchCorrelation;
    private final Map<String, String> awaitPayloadMatch;
    private final Long delaySeconds;
    private final String mapFromRef;
    private final String mapToRef;
    private final String returnValueRef;
    private final CompiledCapabilityCall capabilityCall;
    private final CompiledActionMetadata action;
    private final String generatedActionName;
    private final String collectionRef;
    private final String itemKey;
    private final List<CompiledFlowStep> loopSteps;
    private final Integer maxLoopIterations;
    private final List<CompiledFlowStep> onFailureSteps;
    private final String procedureName;

    public CompiledFlowStep(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String eventName,
            String payloadRef,
            String returnValueRef,
            CompiledCapabilityCall capabilityCall
    ) {
        this(name, type, checkpoint, scope, invariants, eventName, payloadRef, Map.of(),
                null, List.of(), List.of(), null, null, null, Map.of(), null, null, null, returnValueRef, capabilityCall, null);
    }

    public CompiledFlowStep(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            String condition,
            List<CompiledFlowStep> thenSteps,
            List<CompiledFlowStep> elseSteps,
            String awaitEventName,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String mapFromRef,
            String mapToRef,
            String returnValueRef,
            CompiledCapabilityCall capabilityCall
    ) {
        this(name, type, checkpoint, scope, invariants, eventName, payloadRef, eventDataRefs, condition, thenSteps,
                elseSteps, awaitEventName, awaitRef, awaitMatchCorrelation, awaitPayloadMatch, delaySeconds,
                mapFromRef, mapToRef, returnValueRef, capabilityCall, null);
    }

    public CompiledFlowStep(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            String condition,
            List<CompiledFlowStep> thenSteps,
            List<CompiledFlowStep> elseSteps,
            String awaitEventName,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String mapFromRef,
            String mapToRef,
            String returnValueRef,
            CompiledCapabilityCall capabilityCall,
            CompiledActionMetadata action
    ) {
        this(name, type, checkpoint, scope, invariants, eventName, payloadRef, eventDataRefs, condition, thenSteps,
                elseSteps, awaitEventName, awaitRef, awaitMatchCorrelation, awaitPayloadMatch, delaySeconds,
                mapFromRef, mapToRef, returnValueRef, capabilityCall, action, null);
    }

    public CompiledFlowStep(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            String condition,
            List<CompiledFlowStep> thenSteps,
            List<CompiledFlowStep> elseSteps,
            String awaitEventName,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String mapFromRef,
            String mapToRef,
            String returnValueRef,
            CompiledCapabilityCall capabilityCall,
            CompiledActionMetadata action,
            String generatedActionName
    ) {
        this(name, type, checkpoint, scope, invariants, eventName, payloadRef, eventDataRefs, condition, thenSteps,
                elseSteps, awaitEventName, awaitRef, awaitMatchCorrelation, awaitPayloadMatch, delaySeconds,
                mapFromRef, mapToRef, returnValueRef, capabilityCall, action, generatedActionName,
                null, null, List.of(), null, List.of());
    }

    /** LIFT-LOOP-P1: adds {@code collectionRef}/{@code itemKey}/{@code loopSteps}/
     * {@code maxLoopIterations} for a {@code forEach} flow step. */
    public CompiledFlowStep(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            String condition,
            List<CompiledFlowStep> thenSteps,
            List<CompiledFlowStep> elseSteps,
            String awaitEventName,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String mapFromRef,
            String mapToRef,
            String returnValueRef,
            CompiledCapabilityCall capabilityCall,
            CompiledActionMetadata action,
            String generatedActionName,
            String collectionRef,
            String itemKey,
            List<CompiledFlowStep> loopSteps,
            Integer maxLoopIterations
    ) {
        this(name, type, checkpoint, scope, invariants, eventName, payloadRef, eventDataRefs, condition, thenSteps,
                elseSteps, awaitEventName, awaitRef, awaitMatchCorrelation, awaitPayloadMatch, delaySeconds,
                mapFromRef, mapToRef, returnValueRef, capabilityCall, action, generatedActionName,
                collectionRef, itemKey, loopSteps, maxLoopIterations, List.of());
    }

    /** LNCH-17: adds {@code onFailureSteps} -- declared compensation steps run in reverse
     * completion order when a later step in the same flow terminally fails. */
    public CompiledFlowStep(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            String condition,
            List<CompiledFlowStep> thenSteps,
            List<CompiledFlowStep> elseSteps,
            String awaitEventName,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String mapFromRef,
            String mapToRef,
            String returnValueRef,
            CompiledCapabilityCall capabilityCall,
            CompiledActionMetadata action,
            String generatedActionName,
            String collectionRef,
            String itemKey,
            List<CompiledFlowStep> loopSteps,
            Integer maxLoopIterations,
            List<CompiledFlowStep> onFailureSteps
    ) {
        this(name, type, checkpoint, scope, invariants, eventName, payloadRef, eventDataRefs, condition, thenSteps,
                elseSteps, awaitEventName, awaitRef, awaitMatchCorrelation, awaitPayloadMatch, delaySeconds,
                mapFromRef, mapToRef, returnValueRef, capabilityCall, action, generatedActionName, collectionRef,
                itemKey, loopSteps, maxLoopIterations, onFailureSteps, null);
    }

    /** Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): canonical constructor, adding
     * {@code procedureName} -- the procedure a {@code callProcedure} flow step invokes. */
    public CompiledFlowStep(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String eventName,
            String payloadRef,
            Map<String, String> eventDataRefs,
            String condition,
            List<CompiledFlowStep> thenSteps,
            List<CompiledFlowStep> elseSteps,
            String awaitEventName,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String mapFromRef,
            String mapToRef,
            String returnValueRef,
            CompiledCapabilityCall capabilityCall,
            CompiledActionMetadata action,
            String generatedActionName,
            String collectionRef,
            String itemKey,
            List<CompiledFlowStep> loopSteps,
            Integer maxLoopIterations,
            List<CompiledFlowStep> onFailureSteps,
            String procedureName
    ) {
        this.name = name;
        this.type = type;
        this.checkpoint = checkpoint;
        this.scope = scope;
        this.invariants = invariants == null ? List.of() : new ArrayList<>(invariants);
        this.eventName = eventName;
        this.payloadRef = payloadRef;
        this.eventDataRefs = eventDataRefs == null ? Map.of() : new LinkedHashMap<>(eventDataRefs);
        this.condition = condition;
        this.thenSteps = thenSteps == null ? List.of() : new ArrayList<>(thenSteps);
        this.elseSteps = elseSteps == null ? List.of() : new ArrayList<>(elseSteps);
        this.awaitEventName = awaitEventName;
        this.awaitRef = awaitRef;
        this.awaitMatchCorrelation = awaitMatchCorrelation;
        this.awaitPayloadMatch = awaitPayloadMatch == null ? Map.of() : new LinkedHashMap<>(awaitPayloadMatch);
        this.delaySeconds = delaySeconds;
        this.mapFromRef = mapFromRef;
        this.mapToRef = mapToRef;
        this.returnValueRef = returnValueRef;
        this.capabilityCall = capabilityCall;
        this.action = action;
        this.generatedActionName = generatedActionName;
        this.collectionRef = collectionRef;
        this.itemKey = itemKey;
        this.loopSteps = loopSteps == null ? List.of() : new ArrayList<>(loopSteps);
        this.maxLoopIterations = maxLoopIterations;
        this.onFailureSteps = onFailureSteps == null ? List.of() : new ArrayList<>(onFailureSteps);
        this.procedureName = procedureName;
    }

    public String getName() { return name; }

    public String getType() { return type; }

    public String getCheckpoint() { return checkpoint; }

    public String getScope() { return scope; }

    public List<String> getInvariants() {
        return Collections.unmodifiableList(invariants);
    }

    public String getEventName() { return eventName; }

    public String getPayloadRef() { return payloadRef; }

    public Map<String, String> getEventDataRefs() {
        return Collections.unmodifiableMap(eventDataRefs);
    }

    public String getCondition() { return condition; }

    public List<CompiledFlowStep> getThenSteps() {
        return Collections.unmodifiableList(thenSteps);
    }

    public List<CompiledFlowStep> getElseSteps() {
        return Collections.unmodifiableList(elseSteps);
    }

    public String getAwaitEventName() { return awaitEventName; }

    public String getAwaitRef() { return awaitRef; }

    public Boolean getAwaitMatchCorrelation() { return awaitMatchCorrelation; }

    public Map<String, String> getAwaitPayloadMatch() {
        return Collections.unmodifiableMap(awaitPayloadMatch);
    }

    public Long getDelaySeconds() { return delaySeconds; }

    public String getMapFromRef() { return mapFromRef; }

    public String getMapToRef() { return mapToRef; }

    public String getReturnValueRef() { return returnValueRef; }

    public CompiledCapabilityCall getCapabilityCall() { return capabilityCall; }

    public CompiledActionMetadata getAction() { return action; }

    public String getGeneratedActionName() { return generatedActionName; }

    public String getCollectionRef() { return collectionRef; }

    public String getItemKey() { return itemKey; }

    public List<CompiledFlowStep> getLoopSteps() {
        return Collections.unmodifiableList(loopSteps);
    }

    public Integer getMaxLoopIterations() { return maxLoopIterations; }

    public List<CompiledFlowStep> getOnFailureSteps() {
        return Collections.unmodifiableList(onFailureSteps);
    }

    public String getProcedureName() { return procedureName; }
}
