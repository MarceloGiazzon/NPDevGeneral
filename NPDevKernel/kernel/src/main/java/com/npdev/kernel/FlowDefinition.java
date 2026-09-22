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

    /**
     * REG-238: lazily computed, then memoized. Safe because this class is immutable -- {@code name}
     * is final and {@code steps} is an unmodifiable copy -- so every computation yields the same
     * string and a benign race just recomputes it. In production a {@code FlowDefinition} is built
     * once per model snapshot and cached by {@code CompiledModelFlowDefinitionProvider}, so this
     * costs one hash per flow per model load and is free on the execute/resume path.
     */
    private volatile String shapeFingerprint;

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

    /**
     * REG-238: a structural fingerprint of this flow's step tree -- see {@link FlowShapeFingerprint}
     * for exactly what it covers and, just as importantly, what it deliberately ignores.
     */
    public String getShapeFingerprint() {
        String memoized = shapeFingerprint;
        if (memoized == null) {
            memoized = FlowShapeFingerprint.of(this);
            shapeFingerprint = memoized;
        }
        return memoized;
    }

    public SchemaObject getInputSchema() {
        return inputSchema;
    }

    public SchemaObject getOutputSchema() {
        return outputSchema;
    }
}
