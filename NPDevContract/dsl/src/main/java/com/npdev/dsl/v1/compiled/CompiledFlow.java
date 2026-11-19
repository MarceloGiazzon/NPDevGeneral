package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledFlow {
    private final String name;
    private final String concept;
    private final String mode;
    private final List<CompiledFlowStep> steps;
    private final CompiledSchema inputSchema;
    private final CompiledSchema outputSchema;
    private final CompiledActionMetadata action;
    private final boolean startEndpoint;
    private final CompiledFlowSchedule schedule;
    private final CompiledFlowTrigger trigger;
    private final String description;
    private final String expose;
    private final String runAs;
    private final CompiledOrigin origin;
    private final java.util.Map<String, Object> metadata;

    public CompiledFlow(String name, String concept, List<CompiledFlowStep> steps) {
        this(name, concept, null, steps, null, null, null);
    }

    public CompiledFlow(String name, String concept, String mode, List<CompiledFlowStep> steps) {
        this(name, concept, mode, steps, null, null, null);
    }

    public CompiledFlow(
            String name,
            String concept,
            String mode,
            List<CompiledFlowStep> steps,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema
    ) {
        this(name, concept, mode, steps, inputSchema, outputSchema, null);
    }

    public CompiledFlow(
            String name,
            String concept,
            String mode,
            List<CompiledFlowStep> steps,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledActionMetadata action
    ) {
        this(name, concept, mode, steps, inputSchema, outputSchema, action, false);
    }

    public CompiledFlow(
            String name,
            String concept,
            String mode,
            List<CompiledFlowStep> steps,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledActionMetadata action,
            boolean startEndpoint
    ) {
        this(name, concept, mode, steps, inputSchema, outputSchema, action, startEndpoint, null);
    }

    public CompiledFlow(
            String name,
            String concept,
            String mode,
            List<CompiledFlowStep> steps,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledActionMetadata action,
            boolean startEndpoint,
            CompiledFlowSchedule schedule
    ) {
        this(name, concept, mode, steps, inputSchema, outputSchema, action, startEndpoint, schedule, null);
    }

    /** PACK-2: origin is null for an app- or context-declared flow, non-null for a pack-contributed one. */
    public CompiledFlow(
            String name,
            String concept,
            String mode,
            List<CompiledFlowStep> steps,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledActionMetadata action,
            boolean startEndpoint,
            CompiledFlowSchedule schedule,
            CompiledOrigin origin
    ) {
        this(name, concept, mode, steps, inputSchema, outputSchema, action, startEndpoint, schedule, origin, null, null, null);
    }

    /** FORM P3-T5a: canonical constructor, adding {@code trigger}, {@code expose} and {@code runAs}. */
    /** Pre-P3-T5c constructor: {@code null} for every key the merged flow vocabulary added after it. */
    public CompiledFlow(
            String name,
            String concept,
            String mode,
            List<CompiledFlowStep> steps,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledActionMetadata action,
            boolean startEndpoint,
            CompiledFlowSchedule schedule,
            CompiledOrigin origin,
            CompiledFlowTrigger trigger,
            String expose,
            String runAs
    ) {
        this(name, concept, mode, steps, inputSchema, outputSchema, action, startEndpoint, schedule, origin, trigger, expose, runAs, null);
    }

    public CompiledFlow(
            String name,
            String concept,
            String mode,
            List<CompiledFlowStep> steps,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledActionMetadata action,
            boolean startEndpoint,
            CompiledFlowSchedule schedule,
            CompiledOrigin origin,
            CompiledFlowTrigger trigger,
            String expose,
            String runAs,
            String description
    ) {
        this(name, concept, mode, steps, inputSchema, outputSchema, action, startEndpoint, schedule, origin, trigger, expose, runAs,
                description, null);
    }

    /** REG-342: canonical constructor, adding {@code metadata} ({@code untrustedExtensionEntrypoint} marks an extension-backed flow). */
    public CompiledFlow(
            String name,
            String concept,
            String mode,
            List<CompiledFlowStep> steps,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledActionMetadata action,
            boolean startEndpoint,
            CompiledFlowSchedule schedule,
            CompiledOrigin origin,
            CompiledFlowTrigger trigger,
            String expose,
            String runAs,
            String description,
            java.util.Map<String, Object> metadata
    ) {
        this.metadata = metadata == null ? java.util.Map.of() : java.util.Map.copyOf(metadata);
        this.name = name;
        this.concept = concept;
        this.mode = mode;
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

    /** FORM P3-T5c: what the flow is for (one or two sentences; null = none). */
    public String getDescription() { return description; }

    /** REG-342: free-form flow metadata; never null. */
    public java.util.Map<String, Object> getMetadata() { return metadata; }

    public String getName() { return name; }

    public String getConcept() { return concept; }

    public String getMode() { return mode; }

    public List<CompiledFlowStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public CompiledSchema getInputSchema() {
        return inputSchema;
    }

    public CompiledSchema getOutputSchema() {
        return outputSchema;
    }

    public CompiledActionMetadata getAction() {
        return action;
    }

    public boolean isStartEndpoint() {
        return startEndpoint;
    }

    public CompiledFlowSchedule getSchedule() {
        return schedule;
    }

    /** PACK-2: pack-attribution provenance, or null if this flow is not pack-contributed. */
    public CompiledOrigin getOrigin() {
        return origin;
    }

    /** FORM P3-T5a: compiled counterpart of {@code FlowAst#getTrigger()}; null means an API call. */
    public CompiledFlowTrigger getTrigger() { return trigger; }

    public String getExpose() { return expose; }

    public String getRunAs() { return runAs; }
}
