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
    private final CompiledOrigin origin;

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

    /** PACK-2: attaches pack-attribution provenance (see getOrigin) -- null for an app's own root-
     *  or context-declared flow, non-null for a pack-contributed one. */
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
    }

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
}
