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
    }

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
}
