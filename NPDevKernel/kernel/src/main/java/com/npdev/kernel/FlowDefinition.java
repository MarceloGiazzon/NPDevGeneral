package com.npdev.kernel;

import com.npdev.kernel.schema.SchemaObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FlowDefinition {
    private final String name;
    private final String entityName;
    private final List<FlowStepDefinition> steps;
    private final SchemaObject inputSchema;
    private final SchemaObject outputSchema;

    public FlowDefinition(String name, String entityName, List<FlowStepDefinition> steps) {
        this(name, entityName, steps, null, null);
    }

    public FlowDefinition(
            String name,
            String entityName,
            List<FlowStepDefinition> steps,
            SchemaObject inputSchema,
            SchemaObject outputSchema
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName must be non-blank");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }
        this.name = name;
        this.entityName = entityName;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
    }

    public String getName() {
        return name;
    }

    public String getEntityName() {
        return entityName;
    }

    public List<FlowStepDefinition> getSteps() {
        return steps;
    }

    public SchemaObject getInputSchema() {
        return inputSchema;
    }

    public SchemaObject getOutputSchema() {
        return outputSchema;
    }
}
