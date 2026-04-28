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
        this.name = name;
        this.input = new ArrayList<>(input);
        this.output = new ArrayList<>(output);
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.executionPolicy = executionPolicy == null
                ? CompiledCapabilityExecutionPolicy.defaults()
                : executionPolicy;
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
}
