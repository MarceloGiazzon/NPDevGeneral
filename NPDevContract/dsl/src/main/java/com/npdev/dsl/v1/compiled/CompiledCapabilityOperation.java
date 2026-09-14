package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledCapabilityOperation {
    private final String name;
    private final List<String> input;
    private final List<String> output;
    private final CompiledSchema inputSchema;
    private final CompiledSchema outputSchema;
    private final CompiledCapabilityExecutionPolicy executionPolicy;
    private final List<CompiledCapabilityOperationError> errors;
    private final String sideEffects;
    private final CompiledCapabilityAuth auth;

    public CompiledCapabilityOperation(String name, List<String> input, List<String> output) {
        this(name, input, output, null, null, CompiledCapabilityExecutionPolicy.defaults());
    }

    public CompiledCapabilityOperation(
            String name,
            List<String> input,
            List<String> output,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledCapabilityExecutionPolicy executionPolicy
    ) {
        this(name, input, output, inputSchema, outputSchema, executionPolicy, List.of(), null, null);
    }

    public CompiledCapabilityOperation(
            String name,
            List<String> input,
            List<String> output,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledCapabilityExecutionPolicy executionPolicy,
            List<CompiledCapabilityOperationError> errors,
            String sideEffects,
            CompiledCapabilityAuth auth
    ) {
        this.name = name;
        this.input = new ArrayList<>(input);
        this.output = new ArrayList<>(output);
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.executionPolicy = executionPolicy == null
                ? CompiledCapabilityExecutionPolicy.defaults()
                : executionPolicy;
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

    public CompiledSchema getInputSchema() {
        return inputSchema;
    }

    public CompiledSchema getOutputSchema() {
        return outputSchema;
    }

    public CompiledCapabilityExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    public List<CompiledCapabilityOperationError> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public String getSideEffects() {
        return sideEffects;
    }

    public CompiledCapabilityAuth getAuth() {
        return auth;
    }
}
