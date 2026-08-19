package com.npdev.dsl.v1.compiled;

import java.util.List;

@SuppressWarnings("deprecation")
public final class CompiledConcept extends CompiledEntity {
    private final String module;
    private final List<CompiledIndex> indexes;
    private final CompiledConceptAccess access;
    private final String renamedFrom;
    private final String satelliteOf;
    private final CompiledOrigin origin;
    private final boolean softDelete;

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
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel, module, indexes, access, null);
    }

    /** Declares this concept is a rename of a previously-existing concept, not a brand-new one (see getRenamedFrom). */
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
            CompiledConceptAccess access,
            String renamedFrom
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel, module, indexes, access, renamedFrom, null);
    }

    /** PK-6: declares this concept is a satellite extension of a base concept owned by another pack (see getSatelliteOf). */
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
            CompiledConceptAccess access,
            String renamedFrom,
            String satelliteOf
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel, module, indexes, access, renamedFrom, satelliteOf, null);
    }

    /** PACK-2: attaches pack-attribution provenance (see getOrigin) -- null for an app's own root-
     *  or context-declared concept, non-null for a pack-contributed one. */
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
            CompiledConceptAccess access,
            String renamedFrom,
            String satelliteOf,
            CompiledOrigin origin
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel,
                module, indexes, access, renamedFrom, satelliteOf, origin, false);
    }

    /** R5.4: declares this concept's rows are soft-deleted (deletedAt flipped, never physically removed) --
     *  see isSoftDelete. */
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
            CompiledConceptAccess access,
            String renamedFrom,
            String satelliteOf,
            CompiledOrigin origin,
            boolean softDelete
    ) {
        super(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel);
        this.module = (module == null || module.isBlank()) ? null : module;
        this.indexes = indexes == null ? List.of() : List.copyOf(indexes);
        this.access = access;
        this.renamedFrom = renamedFrom;
        this.satelliteOf = satelliteOf;
        this.origin = origin;
        this.softDelete = softDelete;
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

    /** The previous concept name this concept was renamed from, or null if this is not a declared rename. */
    public String getRenamedFrom() {
        return renamedFrom;
    }

    /** PK-6: the pack-qualified base concept this concept is a satellite extension of, or null if it declares none. */
    public String getSatelliteOf() {
        return satelliteOf;
    }

    /** PACK-2: pack-attribution provenance, or null if this concept is not pack-contributed. */
    public CompiledOrigin getOrigin() {
        return origin;
    }

    /** R5.4: true if this concept's rows are soft-deleted (a delete flips a platform-managed
     *  deletedAt timestamp instead of removing the row); false (the default) preserves today's
     *  physical-delete behavior exactly. */
    public boolean isSoftDelete() {
        return softDelete;
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
