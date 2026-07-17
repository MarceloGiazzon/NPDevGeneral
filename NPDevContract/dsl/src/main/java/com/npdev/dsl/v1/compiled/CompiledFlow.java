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
        this.name = name;
        this.concept = concept;
        this.mode = mode;
        this.steps = steps == null ? List.of() : new ArrayList<>(steps);
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.action = action;
        this.startEndpoint = startEndpoint;
        this.schedule = schedule;
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
}
