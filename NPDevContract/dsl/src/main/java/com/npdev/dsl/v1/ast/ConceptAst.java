package com.npdev.dsl.v1.ast;

import java.util.List;

@SuppressWarnings("deprecation")
public final class ConceptAst extends EntityAst {
    private final String module;
    private final List<IndexAst> indexes;

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
        super(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel);
        this.module = (module == null || module.isBlank()) ? null : module;
        this.indexes = indexes == null ? List.of() : List.copyOf(indexes);
    }

    /** Optional module membership (MODULE settings-cascade scope anchor); null if the concept declares none. */
    public String getModule() {
        return module;
    }

    /** LNCH-6: author-declared secondary indexes (indexes:[]); empty if the concept declares none. */
    public List<IndexAst> getIndexes() {
        return indexes;
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
