package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FieldAst {
    private final String name;
    private final String type;
    private final boolean id;
    private final boolean required;
    private final boolean unique;
    private final List<String> enumValues;
    private final List<EnumOptionAst> enumOptions;
    private final String referenceTarget;
    private final ReferenceSemanticsAst referenceSemantics;
    private final String domainType;
    private final SchemaAst schema;
    private final PresentationMetadataAst ui;
    private final String connectable;

    public FieldAst(String name, String type, boolean id, boolean required, boolean unique) {
        this(name, type, id, required, unique, List.of(), null, null, null, null, List.of(), null);
    }

    public FieldAst(
            String name,
            String type,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget
    ) {
        this(name, type, id, required, unique, enumValues, referenceTarget, null, null, null, List.of(), null);
    }

    public FieldAst(
            String name,
            String type,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            SchemaAst schema
    ) {
        this(name, type, id, required, unique, enumValues, referenceTarget, null, null, schema, List.of(), null);
    }

    public FieldAst(
            String name,
            String type,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            String domainType,
            SchemaAst schema
    ) {
        this(name, type, id, required, unique, enumValues, referenceTarget, null, domainType, schema, List.of(), null);
    }

    public FieldAst(
            String name,
            String type,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            ReferenceSemanticsAst referenceSemantics,
            String domainType,
            SchemaAst schema,
            List<EnumOptionAst> enumOptions,
            PresentationMetadataAst ui
    ) {
        this(name, type, id, required, unique, enumValues, referenceTarget, referenceSemantics,
                domainType, schema, enumOptions, ui, null);
    }

    public FieldAst(
            String name,
            String type,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            ReferenceSemanticsAst referenceSemantics,
            String domainType,
            SchemaAst schema,
            List<EnumOptionAst> enumOptions,
            PresentationMetadataAst ui,
            String connectable
    ) {
        this.name = name;
        this.type = type;
        this.id = id;
        this.required = required;
        this.unique = unique;
        this.enumValues = enumValues == null ? List.of() : new ArrayList<>(enumValues);
        this.enumOptions = enumOptions == null ? List.of() : new ArrayList<>(enumOptions);
        this.referenceTarget = referenceTarget;
        this.referenceSemantics = referenceSemantics;
        this.domainType = domainType;
        this.schema = schema;
        this.ui = ui;
        this.connectable = connectable;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public boolean isId() { return id; }
    public boolean isRequired() { return required; }
    public boolean isUnique() { return unique; }
    public List<String> getEnumValues() { return Collections.unmodifiableList(enumValues); }
    public List<EnumOptionAst> getEnumOptions() { return Collections.unmodifiableList(enumOptions); }
    public String getReferenceTarget() { return referenceTarget; }
    public ReferenceSemanticsAst getReferenceSemantics() { return referenceSemantics; }
    public String getDomainType() { return domainType; }
    public SchemaAst getSchema() { return schema; }
    public PresentationMetadataAst getUi() { return ui; }
    /** Connection role of this field: "anchor" marks it as a bondable target key; null for ordinary fields. */
    public String getConnectable() { return connectable; }
}
