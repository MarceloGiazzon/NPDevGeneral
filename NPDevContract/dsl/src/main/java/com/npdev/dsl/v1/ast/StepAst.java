package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class StepAst {
    private final String name;
    private final String type;
    private final String checkpoint;
    private final String scope;
    private final List<String> invariants;
    private final String capability;
    private final String operation;
    private final CapabilityPolicyAst capabilityPolicy;
    private final String input;
    private final String output;
    private final List<String> args;
    private final String event;
    private final String payload;
    private final Map<String, String> data;
    private final String condition;
    private final List<StepAst> thenSteps;
    private final List<StepAst> elseSteps;
    private final String awaitEvent;
    private final String awaitRef;
    private final Boolean awaitMatchCorrelation;
    private final Map<String, String> awaitPayloadMatch;
    private final Long delaySeconds;
    private final String returnValue;
    private final ActionMetadataAst action;
    private final String generatedActionName;
    private final String collectionRef;
    private final String itemKey;
    private final List<StepAst> loopSteps;
    private final Integer maxLoopIterations;
    private final List<StepAst> onFailureSteps;
    private final String procedure;
    private final Boolean parallelAwait;
    private final Long timeoutSeconds;
    private final List<StepAst> onTimeoutSteps;

    public StepAst(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String capability,
            String operation,
            CapabilityPolicyAst capabilityPolicy,
            String input,
            String output,
            List<String> args,
            String event,
            String payload,
            String returnValue
    ) {
        this(name, type, checkpoint, scope, invariants, capability, operation, capabilityPolicy, input, output, args,
                event, payload, Map.of(), null, List.of(), List.of(), null, null, null, Map.of(), null, returnValue, null);
    }

    public StepAst(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String capability,
            String operation,
            String input,
            String output,
            List<String> args,
            String event,
            String payload,
            String returnValue
    ) {
        this(name, type, checkpoint, scope, invariants, capability, operation, null, input, output, args,
                event, payload, Map.of(), null, List.of(), List.of(), null, null, null, Map.of(), null, returnValue, null);
    }

    public StepAst(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String capability,
            String operation,
            CapabilityPolicyAst capabilityPolicy,
            String input,
            String output,
            List<String> args,
            String event,
            String payload,
            Map<String, String> data,
            String condition,
            List<StepAst> thenSteps,
            List<StepAst> elseSteps,
            String awaitEvent,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String returnValue
    ) {
        this(name, type, checkpoint, scope, invariants, capability, operation, capabilityPolicy, input, output, args,
                event, payload, data, condition, thenSteps, elseSteps, awaitEvent, awaitRef, awaitMatchCorrelation,
                awaitPayloadMatch, delaySeconds, returnValue, null);
    }

    public StepAst(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String capability,
            String operation,
            CapabilityPolicyAst capabilityPolicy,
            String input,
            String output,
            List<String> args,
            String event,
            String payload,
            Map<String, String> data,
            String condition,
            List<StepAst> thenSteps,
            List<StepAst> elseSteps,
            String awaitEvent,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String returnValue,
            ActionMetadataAst action
    ) {
        this(name, type, checkpoint, scope, invariants, capability, operation, capabilityPolicy, input, output, args,
                event, payload, data, condition, thenSteps, elseSteps, awaitEvent, awaitRef, awaitMatchCorrelation,
                awaitPayloadMatch, delaySeconds, returnValue, action, null);
    }

    public StepAst(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String capability,
            String operation,
            CapabilityPolicyAst capabilityPolicy,
            String input,
            String output,
            List<String> args,
            String event,
            String payload,
            Map<String, String> data,
            String condition,
            List<StepAst> thenSteps,
            List<StepAst> elseSteps,
            String awaitEvent,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String returnValue,
            ActionMetadataAst action,
            String generatedActionName
    ) {
        this(name, type, checkpoint, scope, invariants, capability, operation, capabilityPolicy, input, output, args,
                event, payload, data, condition, thenSteps, elseSteps, awaitEvent, awaitRef, awaitMatchCorrelation,
                awaitPayloadMatch, delaySeconds, returnValue, action, generatedActionName, null, null, List.of(), null,
                List.of());
    }

    /** LIFT-LOOP-P1: adds {@code collectionRef}/{@code itemKey}/{@code loopSteps}/
     * {@code maxLoopIterations} for a {@code forEach} flow step. */
    public StepAst(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String capability,
            String operation,
            CapabilityPolicyAst capabilityPolicy,
            String input,
            String output,
            List<String> args,
            String event,
            String payload,
            Map<String, String> data,
            String condition,
            List<StepAst> thenSteps,
            List<StepAst> elseSteps,
            String awaitEvent,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String returnValue,
            ActionMetadataAst action,
            String generatedActionName,
            String collectionRef,
            String itemKey,
            List<StepAst> loopSteps,
            Integer maxLoopIterations
    ) {
        this(name, type, checkpoint, scope, invariants, capability, operation, capabilityPolicy, input, output, args,
                event, payload, data, condition, thenSteps, elseSteps, awaitEvent, awaitRef, awaitMatchCorrelation,
                awaitPayloadMatch, delaySeconds, returnValue, action, generatedActionName, collectionRef, itemKey,
                loopSteps, maxLoopIterations, List.of());
    }

    /** LNCH-17: adds {@code onFailureSteps} -- declared compensation steps run in reverse
     * completion order when a later step in the same flow terminally fails. */
    public StepAst(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String capability,
            String operation,
            CapabilityPolicyAst capabilityPolicy,
            String input,
            String output,
            List<String> args,
            String event,
            String payload,
            Map<String, String> data,
            String condition,
            List<StepAst> thenSteps,
            List<StepAst> elseSteps,
            String awaitEvent,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String returnValue,
            ActionMetadataAst action,
            String generatedActionName,
            String collectionRef,
            String itemKey,
            List<StepAst> loopSteps,
            Integer maxLoopIterations,
            List<StepAst> onFailureSteps
    ) {
        this(name, type, checkpoint, scope, invariants, capability, operation, capabilityPolicy, input, output, args,
                event, payload, data, condition, thenSteps, elseSteps, awaitEvent, awaitRef, awaitMatchCorrelation,
                awaitPayloadMatch, delaySeconds, returnValue, action, generatedActionName, collectionRef, itemKey,
                loopSteps, maxLoopIterations, onFailureSteps, null, null);
    }

    /** Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): canonical constructor, adding
     * {@code procedure} -- the procedure name a {@code callProcedure} flow step invokes. B15(B)
     * (S6, docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)) adds the trailing {@code parallelAwait} for a
     * {@code forEach} step. */
    public StepAst(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String capability,
            String operation,
            CapabilityPolicyAst capabilityPolicy,
            String input,
            String output,
            List<String> args,
            String event,
            String payload,
            Map<String, String> data,
            String condition,
            List<StepAst> thenSteps,
            List<StepAst> elseSteps,
            String awaitEvent,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String returnValue,
            ActionMetadataAst action,
            String generatedActionName,
            String collectionRef,
            String itemKey,
            List<StepAst> loopSteps,
            Integer maxLoopIterations,
            List<StepAst> onFailureSteps,
            String procedure,
            Boolean parallelAwait
    ) {
        this(name, type, checkpoint, scope, invariants, capability, operation, capabilityPolicy, input, output, args,
                event, payload, data, condition, thenSteps, elseSteps, awaitEvent, awaitRef, awaitMatchCorrelation,
                awaitPayloadMatch, delaySeconds, returnValue, action, generatedActionName, collectionRef, itemKey,
                loopSteps, maxLoopIterations, onFailureSteps, procedure, parallelAwait, null, List.of());
    }

    /** R2.5 (durable await timeouts): canonical constructor, adding {@code timeoutSeconds} +
     * {@code onTimeoutSteps} -- an {@code awaitEvent} step's optional durable deadline and the
     * escalation steps to run when it passes before the awaited event ever arrives. {@code null}/
     * empty means no timeout, fully backward compatible (mirrors {@code delaySeconds}/
     * {@code onFailureSteps}'s own null-means-absent convention). */
    public StepAst(
            String name,
            String type,
            String checkpoint,
            String scope,
            List<String> invariants,
            String capability,
            String operation,
            CapabilityPolicyAst capabilityPolicy,
            String input,
            String output,
            List<String> args,
            String event,
            String payload,
            Map<String, String> data,
            String condition,
            List<StepAst> thenSteps,
            List<StepAst> elseSteps,
            String awaitEvent,
            String awaitRef,
            Boolean awaitMatchCorrelation,
            Map<String, String> awaitPayloadMatch,
            Long delaySeconds,
            String returnValue,
            ActionMetadataAst action,
            String generatedActionName,
            String collectionRef,
            String itemKey,
            List<StepAst> loopSteps,
            Integer maxLoopIterations,
            List<StepAst> onFailureSteps,
            String procedure,
            Boolean parallelAwait,
            Long timeoutSeconds,
            List<StepAst> onTimeoutSteps
    ) {
        this.name = name;
        this.type = type;
        this.checkpoint = checkpoint;
        this.scope = scope;
        this.invariants = invariants == null ? List.of() : new ArrayList<>(invariants);
        this.capability = capability;
        this.operation = operation;
        this.capabilityPolicy = capabilityPolicy;
        this.input = input;
        this.output = output;
        this.args = args == null ? List.of() : new ArrayList<>(args);
        this.event = event;
        this.payload = payload;
        this.data = data == null ? Map.of() : new LinkedHashMap<>(data);
        this.condition = condition;
        this.thenSteps = thenSteps == null ? List.of() : new ArrayList<>(thenSteps);
        this.elseSteps = elseSteps == null ? List.of() : new ArrayList<>(elseSteps);
        this.awaitEvent = awaitEvent;
        this.awaitRef = awaitRef;
        this.awaitMatchCorrelation = awaitMatchCorrelation;
        this.awaitPayloadMatch = awaitPayloadMatch == null ? Map.of() : new LinkedHashMap<>(awaitPayloadMatch);
        this.delaySeconds = delaySeconds;
        this.returnValue = returnValue;
        this.action = action;
        this.generatedActionName = generatedActionName;
        this.collectionRef = collectionRef;
        this.itemKey = itemKey;
        this.loopSteps = loopSteps == null ? List.of() : new ArrayList<>(loopSteps);
        this.maxLoopIterations = maxLoopIterations;
        this.onFailureSteps = onFailureSteps == null ? List.of() : new ArrayList<>(onFailureSteps);
        this.procedure = procedure;
        this.parallelAwait = parallelAwait;
        this.timeoutSeconds = timeoutSeconds;
        this.onTimeoutSteps = onTimeoutSteps == null ? List.of() : new ArrayList<>(onTimeoutSteps);
    }

    public String getName() { return name; }

    public String getType() { return type; }

    public String getCheckpoint() { return checkpoint; }

    public String getScope() { return scope; }

    public List<String> getInvariants() {
        return Collections.unmodifiableList(invariants);
    }

    public String getCapability() { return capability; }

    public String getOperation() { return operation; }

    public CapabilityPolicyAst getCapabilityPolicy() { return capabilityPolicy; }

    public String getInput() { return input; }

    public String getOutput() { return output; }

    public List<String> getArgs() {
        return Collections.unmodifiableList(args);
    }

    public String getEvent() { return event; }

    public String getPayload() { return payload; }

    public Map<String, String> getData() {
        return Collections.unmodifiableMap(data);
    }

    public String getCondition() { return condition; }

    public List<StepAst> getThenSteps() {
        return Collections.unmodifiableList(thenSteps);
    }

    public List<StepAst> getElseSteps() {
        return Collections.unmodifiableList(elseSteps);
    }

    public String getAwaitEvent() { return awaitEvent; }

    public String getAwaitRef() { return awaitRef; }

    public Boolean getAwaitMatchCorrelation() { return awaitMatchCorrelation; }

    public Map<String, String> getAwaitPayloadMatch() {
        return Collections.unmodifiableMap(awaitPayloadMatch);
    }

    public Long getDelaySeconds() { return delaySeconds; }

    public String getReturnValue() { return returnValue; }

    public ActionMetadataAst getAction() { return action; }

    public String getGeneratedActionName() { return generatedActionName; }

    public String getCollectionRef() { return collectionRef; }

    public String getItemKey() { return itemKey; }

    public List<StepAst> getLoopSteps() {
        return Collections.unmodifiableList(loopSteps);
    }

    public Integer getMaxLoopIterations() { return maxLoopIterations; }

    public List<StepAst> getOnFailureSteps() {
        return Collections.unmodifiableList(onFailureSteps);
    }

    public String getProcedure() { return procedure; }

    /** B15(B) (S6, docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)): {@code true} opts a {@code forEach}
     *  step's loop body into N-way parallel waiting (all iterations' awaits genuinely outstanding
     *  at once) instead of B15(A)'s default sequential (one-at-a-time) behavior. {@code null}/absent
     *  means sequential -- fully backward compatible. */
    public Boolean getParallelAwait() { return parallelAwait; }

    /** R2.5: an {@code awaitEvent} step's optional durable wait deadline, in seconds from when it
     *  first parks -- {@code null} means no timeout (default, fully backward compatible). */
    public Long getTimeoutSeconds() { return timeoutSeconds; }

    /** R2.5: escalation steps run in place of re-parking once {@link #getTimeoutSeconds()}'s
     *  deadline has passed and the awaited event still has not arrived. */
    public List<StepAst> getOnTimeoutSteps() {
        return Collections.unmodifiableList(onTimeoutSteps);
    }
}
