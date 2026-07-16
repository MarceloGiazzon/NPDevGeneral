package com.npdev.dsl.v1.compiled;

import java.util.List;

@SuppressWarnings("deprecation")
public final class CompiledConcept extends CompiledEntity {
    private final String module;
    private final List<CompiledIndex> indexes;
    private final CompiledConceptAccess access;

    public CompiledConcept(String name, String className, String tableName, List<CompiledField> fields) {
        this(name, className, tableName, fields, List.of(), List.of(), null, null, null, null, List.of());
    }

    public CompiledConcept(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants
    ) {
        this(name, className, tableName, fields, expressionInvariants, List.of(), null, null, null, null, List.of());
    }

    public CompiledConcept(
            String name,
            String className,
            String tableName,
            List<CompiledField> fields,
            List<String> expressionInvariants,
            List<CompiledInvariant> invariants
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, null, null, null, null, List.of());
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
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, null, null, null, List.of());
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
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, null, null, List.of());
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
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel, null, List.of());
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
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel, module, List.of());
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
            String module,
            List<CompiledIndex> indexes
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel, module, indexes, null);
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
            String module,
            List<CompiledIndex> indexes,
            CompiledConceptAccess access
    ) {
        super(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel);
        this.module = (module == null || module.isBlank()) ? null : module;
        this.indexes = indexes == null ? List.of() : List.copyOf(indexes);
        this.access = access;
    }

    /** Optional module membership (MODULE settings-cascade scope anchor); null if the concept declares none. */
    public String getModule() {
        return module;
    }

    /** LNCH-6: author-declared secondary indexes (indexes:[]); empty if the concept declares none. */
    public List<CompiledIndex> getIndexes() {
        return indexes;
    }

    /** LNCH-13: compiled row-level authorization (access: {read, write}); null if the concept declares none. */
    public CompiledConceptAccess getAccess() {
        return access;
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
