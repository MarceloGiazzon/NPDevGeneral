package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledCapabilityCall {
    private final String capabilityName;
    private final String capabilityType;
    private final String operation;
    private final List<String> argsRefs;
    private final String inputRef;
    private final String outputRef;
    private final CompiledSchema inputSchema;
    private final CompiledSchema outputSchema;
    private final CompiledCapabilityExecutionPolicy executionPolicy;

    public CompiledCapabilityCall(
            String capabilityName,
            String capabilityType,
            String operation,
            List<String> argsRefs,
            String inputRef,
            String outputRef
    ) {
        this(capabilityName, capabilityType, operation, argsRefs, inputRef, outputRef, null, null,
                CompiledCapabilityExecutionPolicy.defaults());
    }

    public CompiledCapabilityCall(
            String capabilityName,
            String capabilityType,
            String operation,
            List<String> argsRefs,
            String inputRef,
            String outputRef,
            CompiledSchema inputSchema,
            CompiledSchema outputSchema,
            CompiledCapabilityExecutionPolicy executionPolicy
    ) {
        this.capabilityName = capabilityName;
        this.capabilityType = capabilityType;
        this.operation = operation;
        this.argsRefs = argsRefs == null ? List.of() : new ArrayList<>(argsRefs);
        this.inputRef = inputRef;
        this.outputRef = outputRef;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
        this.executionPolicy = executionPolicy == null
                ? CompiledCapabilityExecutionPolicy.defaults()
                : executionPolicy;
    }

    public String getCapability() { return capabilityName; }

    public String getCapabilityName() { return capabilityName; }

    public String getCapabilityType() { return capabilityType; }

    public String getOperation() { return operation; }

    public List<String> getArgsRefs() {
        return Collections.unmodifiableList(argsRefs);
    }

    public String getInputRef() { return inputRef; }

    public String getOutputRef() { return outputRef; }

    public CompiledSchema getInputSchema() {
        return inputSchema;
    }

    public CompiledSchema getOutputSchema() {
        return outputSchema;
    }

    public CompiledCapabilityExecutionPolicy getExecutionPolicy() { return executionPolicy; }
}
