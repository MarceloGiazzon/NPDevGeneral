package com.npdev.dsl.v1.ast;

public final class CapabilityOperationErrorAst {
    private final String name;
    private final String classification;
    private final String description;

    public CapabilityOperationErrorAst(String name, String classification, String description) {
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
