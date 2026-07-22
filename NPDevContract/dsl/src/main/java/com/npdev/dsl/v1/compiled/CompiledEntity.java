package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @deprecated Use {@link CompiledConcept}. Entity remains only as a source-compatible
 * legacy adapter for older generator/runtime integrations.
 */
@Deprecated(forRemoval = false)
public class CompiledEntity {
    private final String name;
    private final String className;
    private final String tableName;
    private final List<CompiledField> fields;
    private final List<String> expressionInvariants;
    private final List<CompiledInvariant> invariants;
    private final CompiledLifecycle lifecycle;
    private final CompiledPresentationMetadata ui;
    private final String truthLevel;

    public CompiledEntity(String name, String className, String tableName, List<CompiledField> fields) {
        this(name, className, tableName, fields, List.of(), List.of(), null, null);
    }

    public CompiledEntity(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants
    ) {
        this(name, className, tableName, fields, expressionInvariants, List.of(), null, null);
    }

    public CompiledEntity(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants,
            List<CompiledInvariant> invariants
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, null, null);
    }

    public CompiledEntity(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants,
            List<CompiledInvariant> invariants,
            CompiledLifecycle lifecycle
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, null);
    }

    public CompiledEntity(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants,
            List<CompiledInvariant> invariants,
            CompiledLifecycle lifecycle,
            CompiledPresentationMetadata ui
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, "T1");
    }

    public CompiledEntity(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants,
            List<CompiledInvariant> invariants,
            CompiledLifecycle lifecycle,
            CompiledPresentationMetadata ui,
            String truthLevel
    ) {
        this.name = name;
        this.className = className;
        this.tableName = tableName;
        this.fields = new ArrayList<>(fields);
        this.expressionInvariants = new ArrayList<>(expressionInvariants);
        this.invariants = new ArrayList<>(invariants);
        this.lifecycle = lifecycle;
        this.ui = ui;
        this.truthLevel = (truthLevel == null || truthLevel.isBlank()) ? "T1" : truthLevel;
    }

    public String getName() { return name; }
    public String getClassName() { return className; }
    public String getTableName() { return tableName; }

    public List<CompiledField> getFields() {
        return Collections.unmodifiableList(fields);
    }

    public List<String> getExpressionInvariants() {
        return Collections.unmodifiableList(expressionInvariants);
    }

    public List<CompiledInvariant> getInvariants() {
        return Collections.unmodifiableList(invariants);
    }

    public CompiledLifecycle getLifecycle() {
        return lifecycle;
    }

    public CompiledPresentationMetadata getUi() {
        return ui;
    }

    /** Concept truth classification code (T0..T6); defaults to T1 (Declared). */
    public String getTruthLevel() {
        return truthLevel;
    }
}
