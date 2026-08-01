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
    private final String renamedFrom;
    private final FileMetadataAst file;
    private final boolean sensitive;
    private final FieldPickerAst picker;

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
        this(name, type, id, required, unique, enumValues, referenceTarget, referenceSemantics,
                domainType, schema, enumOptions, ui, connectable, null);
    }

    /** Declares this field is a rename of a previously-existing column, not a brand-new one (see CompiledField.getRenamedFrom). */
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
            String connectable,
            String renamedFrom
    ) {
        this(name, type, id, required, unique, enumValues, referenceTarget, referenceSemantics,
                domainType, schema, enumOptions, ui, connectable, renamedFrom, null);
    }

    /** LIFT-UPLOAD-P2: {@code file} carries a `file`-typed field's contentTypes/maxSizeBytes/multiple. */
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
            String connectable,
            String renamedFrom,
            FileMetadataAst file
    ) {
        this(name, type, id, required, unique, enumValues, referenceTarget, referenceSemantics,
                domainType, schema, enumOptions, ui, connectable, renamedFrom, file, false);
    }

    /** ADR-0009: {@code sensitive} marks this field for external-AI pack redaction (model.schema.json's field.sensitive). */
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
            String connectable,
            String renamedFrom,
            FileMetadataAst file,
            boolean sensitive
    ) {
        this(name, type, id, required, unique, enumValues, referenceTarget, referenceSemantics,
                domainType, schema, enumOptions, ui, connectable, renamedFrom, file, sensitive, null);
    }

    /** B16/B19 (Move 9 A3): {@code picker} declares a filter/multiSelect for this field's auto-picker. */
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
            String connectable,
            String renamedFrom,
            FileMetadataAst file,
            boolean sensitive,
            FieldPickerAst picker
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
        this.renamedFrom = renamedFrom;
        this.file = file;
        this.sensitive = sensitive;
        this.picker = picker;
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
    /** The previous field name this field was renamed from, or null if this is not a declared rename. */
    public String getRenamedFrom() { return renamedFrom; }
    /** LIFT-UPLOAD-P2: contentTypes/maxSizeBytes/multiple for a `file`-typed field; null otherwise. */
    public FileMetadataAst getFile() { return file; }
    /** ADR-0009: true when this field is marked sensitive for external-AI pack redaction. */
    public boolean isSensitive() { return sensitive; }
    /** B16/B19 (Move 9 A3): this field's declared picker filter/multiSelect, or null if undeclared. */
    public FieldPickerAst getPicker() { return picker; }
}
