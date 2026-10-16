package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final Map<String, Object> http;

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
        this(name, input, output, inputSchema, outputSchema, executionPolicy, errors, sideEffects, auth, Map.of());
    }

    /** INT-1: attaches the operation's declarative HTTP request -- see getHttp. */
    public CompiledCapabilityOperation(
            String name,
            List<String> input,
            List<String> output,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledCapabilityExecutionPolicy executionPolicy,
            List<CompiledCapabilityOperationError> errors,
            String sideEffects,
            CompiledCapabilityAuth auth,
            Map<String, Object> http
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
        this.http = http == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(http));
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

    /** INT-1: the {@code http} request block of an http-connector operation (empty if none). */
    public Map<String, Object> getHttp() {
        return http;
    }
}
