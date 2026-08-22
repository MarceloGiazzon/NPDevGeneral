package com.npdev.dsl.v1.ast;

import java.util.List;

@SuppressWarnings("deprecation")
public final class ConceptAst extends EntityAst {
    private final String module;
    private final List<IndexAst> indexes;
    private final ConceptAccessAst access;
    private final String renamedFrom;
    private final String satelliteOf;
    private final OriginAst origin;
    private final boolean softDelete;
    private final boolean temporal;

    public ConceptAst(String name, List<FieldAst> fields, List<InvariantAst> invariants) {
        this(name, null, null, fields, invariants, List.of(), null, null, null, null, List.of());
    }

    public ConceptAst(String name, String extendsName, List<FieldAst> fields, List<InvariantAst> invariants) {
        this(name, extendsName, null, fields, invariants, List.of(), null, null, null, null, List.of());
    }

    public ConceptAst(
            String name,
            String extendsName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events
    ) {
        this(name, extendsName, null, fields, invariants, events, null, null, null, null, List.of());
    }

    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, null, null, null, null, List.of());
    }

    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, null, null, null, List.of());
    }

    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, null, null, List.of());
    }

    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui,
            TruthLevel truthLevel
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel, null, List.of());
    }

    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui,
            TruthLevel truthLevel,
            String module
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel, module, List.of());
    }

    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui,
            TruthLevel truthLevel,
            String module,
            List<IndexAst> indexes
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel, module, indexes, null);
    }

    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui,
            TruthLevel truthLevel,
            String module,
            List<IndexAst> indexes,
            ConceptAccessAst access
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel, module, indexes, access, null);
    }

    /** Declares this concept is a rename of a previously-existing concept, not a brand-new one (see getRenamedFrom). */
    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui,
            TruthLevel truthLevel,
            String module,
            List<IndexAst> indexes,
            ConceptAccessAst access,
            String renamedFrom
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel, module, indexes, access, renamedFrom, null);
    }

    /** PK-6: declares this concept is a satellite extension of a base concept owned by another pack (see getSatelliteOf). */
    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui,
            TruthLevel truthLevel,
            String module,
            List<IndexAst> indexes,
            ConceptAccessAst access,
            String renamedFrom,
            String satelliteOf
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel, module, indexes, access, renamedFrom, satelliteOf, null);
    }

    /** PACK-2: attaches pack-attribution provenance (see getOrigin) -- null for an app's own root-
     *  or context-declared concept, non-null for a pack-contributed one. */
    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui,
            TruthLevel truthLevel,
            String module,
            List<IndexAst> indexes,
            ConceptAccessAst access,
            String renamedFrom,
            String satelliteOf,
            OriginAst origin
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel, module,
                indexes, access, renamedFrom, satelliteOf, origin, false);
    }

    /** R5.4: declares this concept's rows are soft-deleted (deletedAt flipped, never physically removed) --
     *  see getSoftDelete. */
    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui,
            TruthLevel truthLevel,
            String module,
            List<IndexAst> indexes,
            ConceptAccessAst access,
            String renamedFrom,
            String satelliteOf,
            OriginAst origin,
            boolean softDelete
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel,
                module, indexes, access, renamedFrom, satelliteOf, origin, softDelete, false);
    }

    /** R5.8: declares this concept carries effective-dated rows (validFrom/validTo-scoped) -- see isTemporal. */
    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui,
            TruthLevel truthLevel,
            String module,
            List<IndexAst> indexes,
            ConceptAccessAst access,
            String renamedFrom,
            String satelliteOf,
            OriginAst origin,
            boolean softDelete,
            boolean temporal
    ) {
        super(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel);
        this.module = (module == null || module.isBlank()) ? null : module;
        this.indexes = indexes == null ? List.of() : List.copyOf(indexes);
        this.access = access;
        this.renamedFrom = renamedFrom;
        this.satelliteOf = satelliteOf;
        this.origin = origin;
        this.softDelete = softDelete;
        this.temporal = temporal;
    }

    /** Optional module membership (MODULE settings-cascade scope anchor); null if the concept declares none. */
    public String getModule() {
        return module;
    }

    /** LNCH-6: author-declared secondary indexes (indexes:[]); empty if the concept declares none. */
    public List<IndexAst> getIndexes() {
        return indexes;
    }

    /** LNCH-13: author-declared row-level authorization (access: {read, write}); null if the concept declares none. */
    public ConceptAccessAst getAccess() {
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
    public OriginAst getOrigin() {
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

    public static ConceptAst fromLegacyEntity(EntityAst legacy) {
        if (legacy instanceof ConceptAst concept) {
            return concept;
        }
        return new ConceptAst(
                legacy.getName(),
                legacy.getExtendsName(),
                legacy.getSpecializesName(),
                legacy.getFields(),
                legacy.getInvariants(),
                legacy.getEvents(),
                legacy.getLifecycle(),
                legacy.getUi(),
                legacy.getTruthLevel()
        );
    }
}
