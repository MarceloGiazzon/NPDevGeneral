package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FlowAst {
    private final String name;
    private final String concept;
    private final String mode;
    private final String specializesName;
    private final List<FlowHookAst> hooks;
    private final List<StepAst> steps;
    private final SchemaAst inputSchema;
    private final SchemaAst outputSchema;
    private final ActionMetadataAst action;
    private final boolean startEndpoint;
    private final FlowScheduleAst schedule;
    private final FlowTriggerAst trigger;
    private final String description;
    private final String crossAggregateReason;
    private final String expose;
    private final String runAs;
    private final OriginAst origin;
    private final java.util.Map<String, Object> metadata;

    public FlowAst(String name, String concept, List<StepAst> steps) {
        this(name, concept, null, null, List.of(), steps, null, null, null);
    }

    public FlowAst(String name, String concept, String mode, List<StepAst> steps) {
        this(name, concept, mode, null, List.of(), steps, null, null, null);
    }

    public FlowAst(
            String name,
            String concept,
            String mode,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema
    ) {
        this(name, concept, mode, null, List.of(), steps, inputSchema, outputSchema, null);
    }

    public FlowAst(
            String name,
            String concept,
            String mode,
            String specializesName,
            List<FlowHookAst> hooks,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema
    ) {
        this(name, concept, mode, specializesName, hooks, steps, inputSchema, outputSchema, null);
    }

    public FlowAst(
            String name,
            String concept,
            String mode,
            String specializesName,
            List<FlowHookAst> hooks,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            ActionMetadataAst action
    ) {
        this(name, concept, mode, specializesName, hooks, steps, inputSchema, outputSchema, action, false);
    }

    public FlowAst(
            String name,
            String concept,
            String mode,
            String specializesName,
            List<FlowHookAst> hooks,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            ActionMetadataAst action,
            boolean startEndpoint
    ) {
        this(name, concept, mode, specializesName, hooks, steps, inputSchema, outputSchema, action, startEndpoint, null);
    }

    public FlowAst(
            String name,
            String concept,
            String mode,
            String specializesName,
            List<FlowHookAst> hooks,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            ActionMetadataAst action,
            boolean startEndpoint,
            FlowScheduleAst schedule
    ) {
        this(name, concept, mode, specializesName, hooks, steps, inputSchema, outputSchema, action, startEndpoint, schedule, null);
    }

    /** PACK-2: origin is null for an app- or context-declared flow, non-null for a pack-contributed one. */
    public FlowAst(
            String name,
            String concept,
            String mode,
            String specializesName,
            List<FlowHookAst> hooks,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            ActionMetadataAst action,
            boolean startEndpoint,
            FlowScheduleAst schedule,
            OriginAst origin
    ) {
        this(name, concept, mode, specializesName, hooks, steps, inputSchema, outputSchema, action, startEndpoint, schedule, origin, null, null, null);
    }

    /** FORM P3-T5a: canonical constructor, adding {@code trigger}, {@code expose} and {@code runAs}; {@code null} means the default of each. */
    /** Pre-P3-T5c constructor: {@code null} for every key the merged flow vocabulary added after it. */
    public FlowAst(
            String name,
            String concept,
            String mode,
            String specializesName,
            List<FlowHookAst> hooks,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            ActionMetadataAst action,
            boolean startEndpoint,
            FlowScheduleAst schedule,
            OriginAst origin,
            FlowTriggerAst trigger,
            String expose,
            String runAs
    ) {
        this(name, concept, mode, specializesName, hooks, steps, inputSchema, outputSchema, action, startEndpoint, schedule, origin, trigger, expose, runAs, null);
    }

    public FlowAst(
            String name,
            String concept,
            String mode,
            String specializesName,
            List<FlowHookAst> hooks,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            ActionMetadataAst action,
            boolean startEndpoint,
            FlowScheduleAst schedule,
            OriginAst origin,
            FlowTriggerAst trigger,
            String expose,
            String runAs,
            String description
    ) {
        this(name, concept, mode, specializesName, hooks, steps, inputSchema, outputSchema, action, startEndpoint, schedule,
                origin, trigger, expose, runAs, description, null);
    }

    public FlowAst(
            String name,
            String concept,
            String mode,
            String specializesName,
            List<FlowHookAst> hooks,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            ActionMetadataAst action,
            boolean startEndpoint,
            FlowScheduleAst schedule,
            OriginAst origin,
            FlowTriggerAst trigger,
            String expose,
            String runAs,
            String description,
            String crossAggregateReason
    ) {
        this(name, concept, mode, specializesName, hooks, steps, inputSchema, outputSchema, action, startEndpoint, schedule,
                origin, trigger, expose, runAs, description, crossAggregateReason, null);
    }

    /** FORM P4-T5 / REG-342: canonical constructor, adding {@code metadata} (today only {@code untrustedExtensionEntrypoint}). */
    public FlowAst(
            String name,
            String concept,
            String mode,
            String specializesName,
            List<FlowHookAst> hooks,
            List<StepAst> steps,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            ActionMetadataAst action,
            boolean startEndpoint,
            FlowScheduleAst schedule,
            OriginAst origin,
            FlowTriggerAst trigger,
            String expose,
            String runAs,
            String description,
            String crossAggregateReason,
            java.util.Map<String, Object> metadata
    ) {
        this.metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
        this.crossAggregateReason = crossAggregateReason;
        this.name = name;
        this.concept = concept;
        this.mode = mode;
        this.specializesName = specializesName;
        this.hooks = hooks == null ? List.of() : new ArrayList<>(hooks);
        this.steps = steps == null ? List.of() : new ArrayList<>(steps);
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.action = action;
        this.startEndpoint = startEndpoint;
        this.schedule = schedule;
        this.origin = origin;
        this.trigger = trigger;
        this.expose = expose;
        this.runAs = runAs;
        this.description = description;
    }

    /** REG-342: free-form flow metadata; {@code untrustedExtensionEntrypoint} marks an extension-backed flow. Never null. */
    public java.util.Map<String, Object> getMetadata() { return metadata; }

    /** FORM P3-T5c: what the flow is for (one or two sentences; null = none). */
    public String getDescription() { return description; }

    /** FORM P3-T5c: the stated reason this flow is exempt from the one-aggregate-one-transaction rule; null = not exempt. */
    public String getCrossAggregateReason() { return crossAggregateReason; }

    public String getName() { return name; }

    public String getConcept() { return concept; }

    public String getMode() { return mode; }

    public String getSpecializesName() {
        return specializesName;
    }

    public List<FlowHookAst> getHooks() {
        return Collections.unmodifiableList(hooks);
    }

    public List<StepAst> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public SchemaAst getInputSchema() {
        return inputSchema;
    }

    public SchemaAst getOutputSchema() {
        return outputSchema;
    }

    public ActionMetadataAst getAction() {
        return action;
    }

    public boolean isStartEndpoint() {
        return startEndpoint;
    }

    public FlowScheduleAst getSchedule() {
        return schedule;
    }

    /** PACK-2: pack-attribution provenance, or null if this flow is not pack-contributed. */
    public OriginAst getOrigin() {
        return origin;
    }

    /** FORM P3-T5a: what starts this flow; {@code null} means the default, an API call. */
    public FlowTriggerAst getTrigger() { return trigger; }

    /** FORM P3-T5a: {@code api} (default, null), {@code ui} or {@code internal}. */
    public String getExpose() { return expose; }

    /** FORM P3-T5a: {@code caller} (default, null) or {@code system}. */
    public String getRunAs() { return runAs; }
}
