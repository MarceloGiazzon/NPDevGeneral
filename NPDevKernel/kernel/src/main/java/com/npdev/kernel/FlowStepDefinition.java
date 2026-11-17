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
        FOR_EACH,
        /** FORM P3-T5b: one concept step of the merged vocabulary (set, readConcept, listConcepts,
         * runQuery, deleteConcept) carried as the procedure step the procedure executor already
         * implements, so a flow and a procedure run the SAME code for it -- see ProcedureFlowStep. */
        PROCEDURE_STEP,
        /** FORM P3-T5b: synchronously starts another flow by name ({@link #getProcedureName()} carries
         * the flow name here) and binds its output -- see CallFlowStep. */
        CALL_FLOW
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
    private final boolean parallelAwait;
    private final List<FlowStepDefinition> onFailureSteps;
    private final String procedureName;
    private final Long timeoutSeconds;
    private final List<FlowStepDefinition> onTimeoutSteps;
    private final ConceptWrite conceptWrite;
    /** FORM P3-T5b: set once by {@link #procedureStep}, before the instance is shared; copied by every copy constructor. */
    private com.npdev.kernel.procedures.ProcedureStep embeddedStep;
    /** FORM P3-T5c: capabilityCall -- a failed call is stored under this state key instead of failing the flow. */
    private String errorTarget;
    /** FORM P3-T5c: createConcept -- {@code "skip"} turns a unique-constraint conflict into a skipped step (output null). */
    private String ifExists;
    /** FORM P3-T5c stage 4: scheduleEvent -- expression that makes a delayed schedule unique across executions (null = per execution). */
    private String dedupeKey;

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

    /** LIFT-LOOP-P1: adds {@code collectionRef}/{@code itemKey}/{@code loopSteps}/
     * {@code maxLoopIterations} for {@link Type#FOR_EACH}. */
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
        this(
                name, type, checkpoint, invariantScope, invariants, capability, capabilityType,
                capabilityAdapterId, capabilityExecutionPolicy, capabilityInputSchema, capabilityOutputSchema,
                operation, inputRef, argsRefs, outputRef, eventName, payloadRef, eventDataRefs, condition,
                thenSteps, elseSteps, awaitEventName, awaitRef, awaitMatchCorrelation, awaitPayloadMatchRefs,
                delaySeconds, mapFromRef, mapToRef, returnRef, collectionRef, itemKey, loopSteps, maxLoopIterations,
                null, false
        );
    }

    /** Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): canonical constructor, adding
     * {@code procedureName} for {@link Type#CALL_FLOW}. B15(B) (S6,
     * docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)) adds the trailing {@code parallelAwait} for
     * {@link Type#FOR_EACH}. */
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
            Integer maxLoopIterations,
            String procedureName,
            boolean parallelAwait
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
        this.procedureName = procedureName;
        this.parallelAwait = parallelAwait;
        this.timeoutSeconds = null;
        this.onTimeoutSteps = List.of();
        this.conceptWrite = null;
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
        this.procedureName = source.procedureName;
        this.parallelAwait = source.parallelAwait;
        this.timeoutSeconds = source.timeoutSeconds;
        this.onTimeoutSteps = source.onTimeoutSteps;
        this.conceptWrite = source.conceptWrite;
        this.embeddedStep = source.embeddedStep;
        this.errorTarget = source.errorTarget;
        this.ifExists = source.ifExists;
        this.dedupeKey = source.dedupeKey;
    }

    /**
     * What a {@code createConcept}/{@code updateConcept} flow step MEANS, as opposed to the bare
     * {@code persistence.save} capability call both compile to (REG-262/263/264). The kernel cannot
     * tell the two apart from the call alone, and the difference is the whole point: a create must
     * satisfy the concept's required-field invariants, an update must find the row it names and merge
     * into it.
     *
     * @param mode                    {@code "create"} or {@code "update"}
     * @param requiredInvariantRefs   for a create: the {@code required(field)} refs the persistence
     *                                layer will NOT satisfy by itself (no default, no derived value, not
     *                                the lifecycle status field), checked against the payload before the
     *                                write. Empty for an update.
     */
    public record ConceptWrite(String mode, List<String> requiredInvariantRefs, String conceptName) {
        public static final String CREATE = "create";
        public static final String UPDATE = "update";

        /** The concept written is the flow's own (FORM P3-T5c added {@code conceptName} for steps whose {@code scope} differs). */
        public ConceptWrite(String mode, List<String> requiredInvariantRefs) {
            this(mode, requiredInvariantRefs, null);
        }

        public ConceptWrite {
            if (!CREATE.equals(mode) && !UPDATE.equals(mode)) {
                throw new IllegalArgumentException("mode must be create or update: " + mode);
            }
            requiredInvariantRefs = requiredInvariantRefs == null ? List.of() : List.copyOf(requiredInvariantRefs);
        }

        public boolean isCreate() {
            return CREATE.equals(mode);
        }

        public boolean isUpdate() {
            return UPDATE.equals(mode);
        }
    }

    /** Copy constructor used only by {@link #withConceptWrite}. */
    private FlowStepDefinition(FlowStepDefinition source, ConceptWrite conceptWrite) {
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
        this.onFailureSteps = source.onFailureSteps;
        this.procedureName = source.procedureName;
        this.parallelAwait = source.parallelAwait;
        this.timeoutSeconds = source.timeoutSeconds;
        this.onTimeoutSteps = source.onTimeoutSteps;
        this.conceptWrite = conceptWrite;
        this.embeddedStep = source.embeddedStep;
        this.errorTarget = source.errorTarget;
        this.ifExists = source.ifExists;
        this.dedupeKey = source.dedupeKey;
    }

    /** FORM P3-T5c: the capability-call options {@code errorTarget} and {@code ifExists} (either may be null). */
    public FlowStepDefinition withCallOptions(String errorTarget, String ifExists) {
        FlowStepDefinition copy = new FlowStepDefinition(this, this.onFailureSteps);
        copy.errorTarget = errorTarget == null || errorTarget.isBlank() ? null : errorTarget.trim();
        copy.ifExists = ifExists == null || ifExists.isBlank() ? null : ifExists.trim();
        return copy;
    }

    public String getErrorTarget() {
        return errorTarget;
    }

    public String getIfExists() {
        return ifExists;
    }

    /** FORM P3-T5c stage 4: the scheduleEvent step's {@code dedupeKey} expression (null = schedule is unique per execution). */
    public FlowStepDefinition withDedupeKey(String dedupeKey) {
        FlowStepDefinition copy = new FlowStepDefinition(this, this.onFailureSteps);
        copy.dedupeKey = dedupeKey == null || dedupeKey.isBlank() ? null : dedupeKey.trim();
        return copy;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    /** Marks a capability-call step as a concept create/update (see {@link ConceptWrite}). */
    public FlowStepDefinition withConceptWrite(ConceptWrite conceptWrite) {
        return new FlowStepDefinition(this, conceptWrite);
    }

    /** {@code null} for every step that is not a createConcept/updateConcept. */
    public ConceptWrite getConceptWrite() {
        return conceptWrite;
    }

    /** LNCH-17: attaches declared compensation steps to an already-built step. */
    public FlowStepDefinition withOnFailure(List<FlowStepDefinition> onFailureSteps) {
        return new FlowStepDefinition(this, onFailureSteps);
    }

    public List<FlowStepDefinition> getOnFailureSteps() {
        return onFailureSteps;
    }

    /**
     * R2.5 (durable await timeouts): copy constructor used only by {@link #withTimeout}, the same
     * attach-after-the-fact pattern {@link #withOnFailure} already uses -- only {@link
     * Type#AWAIT_EVENT} ever sets this, so threading it through every factory method (map,
     * forEach, capabilityCall, ...) would be dead plumbing everywhere else.
     */
    private FlowStepDefinition(FlowStepDefinition source, Long timeoutSeconds, List<FlowStepDefinition> onTimeoutSteps) {
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
        this.onFailureSteps = source.onFailureSteps;
        this.procedureName = source.procedureName;
        this.parallelAwait = source.parallelAwait;
        this.timeoutSeconds = timeoutSeconds;
        this.onTimeoutSteps = onTimeoutSteps == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(onTimeoutSteps));
        this.conceptWrite = source.conceptWrite;
        this.embeddedStep = source.embeddedStep;
        this.errorTarget = source.errorTarget;
        this.ifExists = source.ifExists;
        this.dedupeKey = source.dedupeKey;
    }

    /** R2.5: attaches a durable await deadline (seconds from when the step first parks) and the
     *  escalation steps to run once it passes without the awaited event arriving. */
    public FlowStepDefinition withTimeout(long timeoutSeconds, List<FlowStepDefinition> onTimeoutSteps) {
        return new FlowStepDefinition(this, timeoutSeconds, onTimeoutSteps);
    }

    /** R2.5: {@code null} means no timeout -- the pre-R2.5 default (wait forever). */
    public Long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public List<FlowStepDefinition> getOnTimeoutSteps() {
        return onTimeoutSteps;
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
        return forEach(name, collectionRef, itemKey, loopSteps, maxLoopIterations, false);
    }

    /**
     * B15(B) (S6, docs/BOUNDARY_LIFT_ROADMAP.md §B15(B)): {@code parallelAwait=true} opts a loop
     * body of EXACTLY ONE {@code AWAIT_EVENT} step into N-way parallel waiting -- every iteration's
     * await is attempted up front, all still-outstanding ones stay durably parked at once (not one
     * at a time, unlike the default sequential mode B15(A) lifted), and the step completes only
     * once every iteration has resolved. See {@link ParallelAwaitForEachStep} for the execution
     * semantics and {@link FlowStateCodec}'s parallel-await keys for the durable representation.
     * Deliberately scoped to a single-step loop body (no steps before or after the await) -- see
     * {@link ParallelAwaitForEachStep}'s own javadoc for why.
     */
    public static FlowStepDefinition forEach(
            String name,
            String collectionRef,
            String itemKey,
            List<FlowStepDefinition> loopSteps,
            Integer maxLoopIterations,
            boolean parallelAwait
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
                maxLoopIterations,
                null,
                parallelAwait
        );
    }

    private static FlowStepDefinition procedureLike(
            Type type,
            String name,
            String procedureName,
            String inputRef,
            String outputRef
    ) {
        return new FlowStepDefinition(
                name,
                type,
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
                inputRef,
                List.of(),
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
                null,
                null,
                null,
                List.of(),
                null,
                requireNonBlank(procedureName, "procedureName"),
                false
        );
    }

    /**
     * FORM P3-T5b: a concept step of the merged vocabulary, carried as the {@link
     * com.npdev.kernel.procedures.ProcedureStep} that implements it. {@code outputRef} is the flow-state
     * key the result lands in (the procedure step's own {@code outputKey}).
     */
    public static FlowStepDefinition procedureStep(
            String name,
            com.npdev.kernel.procedures.ProcedureStep step,
            String outputRef
    ) {
        if (step == null) {
            throw new IllegalArgumentException("step must be non-null");
        }
        FlowStepDefinition built = procedureLike(Type.PROCEDURE_STEP, name, "-", null, outputRef);
        built.embeddedStep = step;
        return built;
    }

    /** FORM P3-T5b: synchronously starts the flow {@code flowName}; {@code inputRef} is its input, {@code outputRef} receives its output. */
    public static FlowStepDefinition callFlow(String name, String flowName, String inputRef, String outputRef) {
        return procedureLike(Type.CALL_FLOW, name, flowName, inputRef, outputRef);
    }

    /** The procedure step a {@link Type#PROCEDURE_STEP} carries; null for every other type. */
    public com.npdev.kernel.procedures.ProcedureStep getEmbeddedStep() {
        return embeddedStep;
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

    public boolean isParallelAwait() {
        return parallelAwait;
    }

    public String getProcedureName() {
        return procedureName;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value;
    }
}
