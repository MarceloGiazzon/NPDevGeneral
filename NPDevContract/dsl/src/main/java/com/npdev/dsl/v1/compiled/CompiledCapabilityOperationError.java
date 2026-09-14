package com.npdev.dsl.v1.compiled;

public final class CompiledCapabilityOperationError {
    private final String name;
    private final String classification;
    private final String description;

    public CompiledCapabilityOperationError(String name, String classification, String description) {
        this.name = name;
        this.classification = classification;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getClassification() {
        return classification;
    }

    public String getDescription() {
        return description;
    }
}
