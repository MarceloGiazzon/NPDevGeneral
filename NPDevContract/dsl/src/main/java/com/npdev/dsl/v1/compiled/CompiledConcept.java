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
    private final boolean temporal;
    private final String uid;
    private final List<CompiledRollup> rollups;
    private final List<String> fieldOrder;

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
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel,
                module, indexes, access, renamedFrom, satelliteOf, origin, softDelete, false);
    }

    /** R5.8: declares this concept carries effective-dated rows (validFrom/validTo-scoped) -- see isTemporal. */
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
            boolean softDelete,
            boolean temporal
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel,
                module, indexes, access, renamedFrom, satelliteOf, origin, softDelete, temporal, null);
    }

    /** REG-209 (B1 lift): a stable identity for this concept, generated once and never reused -- see getUid. */
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
            boolean softDelete,
            boolean temporal,
            String uid
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel, module, indexes, access, renamedFrom, satelliteOf, origin, softDelete, temporal, uid, List.of());
    }

    /** P8 prelude: platform-maintained stored aggregates over a child concept -- see getRollups. */
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
            boolean softDelete,
            boolean temporal,
            String uid,
            List<CompiledRollup> rollups
    ) {
        this(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel, module, indexes, access, renamedFrom, satelliteOf, origin, softDelete, temporal, uid, rollups, List.of());
    }

    /** Pigmentampas friction #18: the author's field declaration order -- see getFieldOrder. */
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
            boolean softDelete,
            boolean temporal,
            String uid,
            List<CompiledRollup> rollups,
            List<String> fieldOrder
    ) {
        super(name, className, tableName, fields, expressionInvariants, invariants, lifecycle, ui, truthLevel);
        this.module = (module == null || module.isBlank()) ? null : module;
        this.indexes = indexes == null ? List.of() : List.copyOf(indexes);
        this.access = access;
        this.renamedFrom = renamedFrom;
        this.satelliteOf = satelliteOf;
        this.origin = origin;
        this.softDelete = softDelete;
        this.temporal = temporal;
        this.uid = uid;
        this.rollups = rollups == null ? List.of() : List.copyOf(rollups);
        this.fieldOrder = fieldOrder == null ? List.of() : List.copyOf(fieldOrder);
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

    /** R5.8: true if this concept carries effective-dated rows -- resolution reads a `validFrom`/
     *  `validTo` window (both author-declared `date` fields, checked by SemanticValidator) against a
     *  caller-supplied `asOf` date; false (the default) leaves the concept's read path unchanged. */
    public boolean isTemporal() {
        return temporal;
    }

    /** REG-209 (B1 lift): a stable identity for this concept, generated once and never reused
     *  (npdev migrate assign-uids stamps one); null if the concept declares none. */
    public String getUid() {
        return uid;
    }

    /** P8 prelude: declared {@code rollups[]} -- stored fields of this concept the platform keeps
     *  equal to an aggregate over a child concept's rows; empty if none. */
    public List<CompiledRollup> getRollups() {
        return rollups;
    }

    /** Pigmentampas friction #18: field names in the order the author declared them (inherited
     *  fields first). {@link #getFields()} stays sorted by name -- the canonical, deterministic
     *  layout every schema/codegen consumer relies on -- so this is the ONLY place declaration order
     *  survives compilation. Empty for a concept compiled before it existed. */
    public List<String> getFieldOrder() {
        return fieldOrder;
    }

    /** {@link #getFields()} in declaration order, for anything a person reads (forms, detail, list
     *  columns): fields named in {@link #getFieldOrder()} first, in that order, then any others in
     *  their {@code getFields()} order (e.g. a field an extension pack added). */
    public List<CompiledField> getFieldsInDeclaredOrder() {
        if (fieldOrder.isEmpty()) {
            return getFields();
        }
        java.util.Map<String, CompiledField> byName = new java.util.LinkedHashMap<>();
        for (CompiledField field : getFields()) {
            byName.putIfAbsent(field.getName(), field);
        }
        List<CompiledField> out = new java.util.ArrayList<>(byName.size());
        for (String name : fieldOrder) {
            CompiledField field = byName.remove(name);
            if (field != null) {
                out.add(field);
            }
        }
        out.addAll(byName.values());
        return List.copyOf(out);
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
