package com.npdev.dsl.v1.ast;

import java.util.List;

@SuppressWarnings("deprecation")
public final class ConceptAst extends EntityAst {
    private final String module;

    public ConceptAst(String name, List<FieldAst> fields, List<InvariantAst> invariants) {
        this(name, null, null, fields, invariants, List.of(), null, null, null, null);
    }

    public ConceptAst(String name, String extendsName, List<FieldAst> fields, List<InvariantAst> invariants) {
        this(name, extendsName, null, fields, invariants, List.of(), null, null, null, null);
    }

    public ConceptAst(
            String name,
            String extendsName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events
    ) {
        this(name, extendsName, null, fields, invariants, events, null, null, null, null);
    }

    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, null, null, null, null);
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
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, null, null, null);
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
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, null, null);
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
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel, null);
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
        super(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel);
        this.module = (module == null || module.isBlank()) ? null : module;
    }

    /** Optional module membership (MODULE settings-cascade scope anchor); null if the concept declares none. */
    public String getModule() {
        return module;
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
