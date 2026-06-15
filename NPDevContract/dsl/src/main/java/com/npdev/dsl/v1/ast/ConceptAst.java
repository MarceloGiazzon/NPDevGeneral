package com.npdev.dsl.v1.ast;

import java.util.List;

public final class ConceptAst extends EntityAst {
    public ConceptAst(String name, List<FieldAst> fields, List<InvariantAst> invariants) {
        super(name, fields, invariants);
    }

    public ConceptAst(String name, String extendsName, List<FieldAst> fields, List<InvariantAst> invariants) {
        super(name, extendsName, fields, invariants);
    }

    public ConceptAst(
            String name,
            String extendsName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events
    ) {
        super(name, extendsName, fields, invariants, events);
    }

    public ConceptAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events
    ) {
        super(name, extendsName, specializesName, fields, invariants, events);
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
        super(name, extendsName, specializesName, fields, invariants, events, lifecycle);
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
        super(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui);
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
        super(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, truthLevel);
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
