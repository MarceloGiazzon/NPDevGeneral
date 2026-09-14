package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CapabilityOperationAst {
    private final String name;
    private final List<String> input;
    private final List<String> output;
    private final SchemaAst inputSchema;
    private final SchemaAst outputSchema;
    private final CapabilityPolicyAst executionPolicy;
    private final List<CapabilityOperationErrorAst> errors;
    private final String sideEffects;
    private final CapabilityAuthAst auth;

    public CapabilityOperationAst(String name, List<String> input, List<String> output) {
        this(name, input, output, null, null, null);
    }

    public CapabilityOperationAst(
            String name,
            List<String> input,
            List<String> output,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            CapabilityPolicyAst executionPolicy
    ) {
        this(name, input, output, inputSchema, outputSchema, executionPolicy, List.of(), null, null);
    }

    public CapabilityOperationAst(
            String name,
            List<String> input,
            List<String> output,
            SchemaAst inputSchema,
            SchemaAst outputSchema,
            CapabilityPolicyAst executionPolicy,
            List<CapabilityOperationErrorAst> errors,
            String sideEffects,
            CapabilityAuthAst auth
    ) {
        this.name = name;
        this.input = new ArrayList<>(input);
        this.output = new ArrayList<>(output);
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.executionPolicy = executionPolicy;
        this.errors = new ArrayList<>(errors);
        this.sideEffects = sideEffects;
        this.auth = auth;
    }

    public String getName() { return name; }

    public List<String> getInput() {
        return Collections.unmodifiableList(input);
    }

    public List<String> getOutput() {
        return Collections.unmodifiableList(output);
    }

    public SchemaAst getInputSchema() {
        return inputSchema;
    }

    public SchemaAst getOutputSchema() {
        return outputSchema;
    }

    public CapabilityPolicyAst getExecutionPolicy() {
        return executionPolicy;
    }

    public List<CapabilityOperationErrorAst> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public String getSideEffects() {
        return sideEffects;
    }

    public CapabilityAuthAst getAuth() {
        return auth;
    }
}
