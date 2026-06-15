package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @deprecated Use {@link ConceptAst}. EntityAst is retained only as an
 * import/source compatibility adapter for legacy callers.
 */
@Deprecated(forRemoval = false)
public class EntityAst {
    private final String name;
    private final String extendsName;
    private final String specializesName;
    private final List<FieldAst> fields;
    private final List<InvariantAst> invariants;
    private final List<EventAst> events;
    private final LifecycleAst lifecycle;
    private final PresentationMetadataAst ui;
    private final TruthLevel truthLevel;

    public EntityAst(String name, List<FieldAst> fields, List<InvariantAst> invariants) {
        this(name, null, null, fields, invariants, List.of(), null, null);
    }

    public EntityAst(String name, String extendsName, List<FieldAst> fields, List<InvariantAst> invariants) {
        this(name, extendsName, null, fields, invariants, List.of(), null, null);
    }

    public EntityAst(
            String name,
            String extendsName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events
    ) {
        this(name, extendsName, null, fields, invariants, events, null, null);
    }

    public EntityAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, null, null);
    }

    public EntityAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, null);
    }

    public EntityAst(
            String name,
            String extendsName,
            String specializesName,
            List<FieldAst> fields,
            List<InvariantAst> invariants,
            List<EventAst> events,
            LifecycleAst lifecycle,
            PresentationMetadataAst ui
    ) {
        this(name, extendsName, specializesName, fields, invariants, events, lifecycle, ui, TruthLevel.DEFAULT);
    }

    public EntityAst(
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
        this.name = name;
        this.extendsName = extendsName;
        this.specializesName = firstNonBlank(specializesName, extendsName);
        this.fields = new ArrayList<>(fields);
        this.invariants = new ArrayList<>(invariants);
        this.events = events == null ? List.of() : new ArrayList<>(events);
        this.lifecycle = lifecycle;
        this.ui = ui;
        this.truthLevel = truthLevel == null ? TruthLevel.DEFAULT : truthLevel;
    }

    public String getName() { return name; }
    public String getExtendsName() { return extendsName; }
    public String getSpecializesName() { return specializesName; }

    public List<FieldAst> getFields() {
        return Collections.unmodifiableList(fields);
    }

    public List<InvariantAst> getInvariants() {
        return Collections.unmodifiableList(invariants);
    }

    public List<EventAst> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public LifecycleAst getLifecycle() {
        return lifecycle;
    }

    public PresentationMetadataAst getUi() {
        return ui;
    }

    public TruthLevel getTruthLevel() {
        return truthLevel;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }
}
