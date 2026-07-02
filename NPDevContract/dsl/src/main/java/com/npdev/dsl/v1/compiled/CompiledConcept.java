package com.npdev.dsl.v1.compiled;

import java.util.List;

@SuppressWarnings("deprecation")
public final class CompiledConcept extends CompiledEntity {
    private final String module;

    public CompiledConcept(String name, String className, String tableName, List<CompiledField> fields) {
        this(name, className, tableName, fields, List.of(), List.of(), null, null, null, null);
    }

    public CompiledConcept(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants
    ) {
        this(name, className, tableName, fields, expressionInvariants, List.of(), null, null, null, null);
    }

    public CompiledConcept(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants,
            List<CompiledInvariant> invariants
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, null, null, null, null);
    }

    public CompiledConcept(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants,
            List<CompiledInvariant> invariants,
            CompiledLifecycle lifecycle
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, null, null, null);
    }

    public CompiledConcept(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants,
            List<CompiledInvariant> invariants,
            CompiledLifecycle lifecycle,
            CompiledPresentationMetadata ui
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, null, null);
    }

    public CompiledConcept(
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
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel, null);
    }

    public CompiledConcept(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants,
            List<CompiledInvariant> invariants,
            CompiledLifecycle lifecycle,
            CompiledPresentationMetadata ui,
            String truthLevel,
            String module
    ) {
        super(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel);
        this.module = (module == null || module.isBlank()) ? null : module;
    }

    /** Optional module membership (MODULE settings-cascade scope anchor); null if the concept declares none. */
    public String getModule() {
        return module;
    }

    public static CompiledConcept fromLegacyEntity(CompiledEntity legacy) {
        if (legacy instanceof CompiledConcept concept) {
            return concept;
        }
        return new CompiledConcept(
                legacy.getName(),
                legacy.getClassName(),
                legacy.getTableName(),
                legacy.getFields(),
                legacy.getExpressionInvariants(),
                legacy.getInvariants(),
                legacy.getLifecycle(),
                legacy.getUi(),
                legacy.getTruthLevel()
        );
    }
}
