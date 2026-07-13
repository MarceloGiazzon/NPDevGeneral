package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledField {
    private final String name;
    private final String dslType;
    private final String javaType;
    private final boolean id;
    private final boolean required;
    private final boolean unique;
    private final List<String> enumValues;
    private final List<CompiledEnumOption> enumOptions;
    private final String referenceTarget;
    private final CompiledReferenceSemantics referenceSemantics;
    private final String domainType;
    private final CompiledSchema schema;
    private final CompiledPresentationMetadata ui;
    private final String connectable;
    private final String renamedFrom;
    private final CompiledFileMetadata file;

    public CompiledField(String name, String dslType, String javaType, boolean id, boolean required, boolean unique) {
        this(name, dslType, javaType, id, required, unique, List.of(), null, null, null, null, List.of(), null);
    }

    public CompiledField(
            String name,
            String dslType,
            String javaType,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget
    ) {
        this(name, dslType, javaType, id, required, unique, enumValues, referenceTarget, null, null, null, List.of(), null);
    }

    public CompiledField(
            String name,
            String dslType,
            String javaType,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            CompiledSchema schema
    ) {
        this(name, dslType, javaType, id, required, unique, enumValues, referenceTarget, null, null, schema, List.of(), null);
    }

    public CompiledField(
            String name,
            String dslType,
            String javaType,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            String domainType,
            CompiledSchema schema
    ) {
        this(name, dslType, javaType, id, required, unique, enumValues, referenceTarget, null, domainType, schema, List.of(), null);
    }

    public CompiledField(
            String name,
            String dslType,
            String javaType,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            CompiledReferenceSemantics referenceSemantics,
            String domainType,
            CompiledSchema schema,
            List<CompiledEnumOption> enumOptions,
            CompiledPresentationMetadata ui
    ) {
        this(name, dslType, javaType, id, required, unique, enumValues, referenceTarget,
                referenceSemantics, domainType, schema, enumOptions, ui, null);
    }

    public CompiledField(
            String name,
            String dslType,
            String javaType,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            CompiledReferenceSemantics referenceSemantics,
            String domainType,
            CompiledSchema schema,
            List<CompiledEnumOption> enumOptions,
            CompiledPresentationMetadata ui,
            String connectable
    ) {
        this(name, dslType, javaType, id, required, unique, enumValues, referenceTarget,
                referenceSemantics, domainType, schema, enumOptions, ui, connectable, null);
    }

    /** Declares this field is a rename of a previously-existing column, not a brand-new one (see getRenamedFrom). */
    public CompiledField(
            String name,
            String dslType,
            String javaType,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            CompiledReferenceSemantics referenceSemantics,
            String domainType,
            CompiledSchema schema,
            List<CompiledEnumOption> enumOptions,
            CompiledPresentationMetadata ui,
            String connectable,
            String renamedFrom
    ) {
        this(name, dslType, javaType, id, required, unique, enumValues, referenceTarget, referenceSemantics,
                domainType, schema, enumOptions, ui, connectable, renamedFrom, null);
    }

    /** LIFT-UPLOAD-P2: {@code file} carries a `file`-typed field's contentTypes/maxSizeBytes/multiple. */
    public CompiledField(
            String name,
            String dslType,
            String javaType,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            CompiledReferenceSemantics referenceSemantics,
            String domainType,
            CompiledSchema schema,
            List<CompiledEnumOption> enumOptions,
            CompiledPresentationMetadata ui,
            String connectable,
            String renamedFrom,
            CompiledFileMetadata file
    ) {
        this.name = name;
        this.dslType = dslType;
        this.javaType = javaType;
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
    }

    public CompiledField(
            String name,
            String dslType,
            String javaType,
            boolean id,
            boolean required,
            boolean unique,
            List<String> enumValues,
            String referenceTarget,
            CompiledReferenceSemantics referenceSemantics,
            String domainType,
            CompiledSchema schema,
            List<CompiledEnumOption> enumOptions
    ) {
        this(
                name,
                dslType,
                javaType,
                id,
                required,
                unique,
                enumValues,
                referenceTarget,
                referenceSemantics,
                domainType,
                schema,
                enumOptions,
                null
        );
    }

    public String getName() { return name; }
    public String getDslType() { return dslType; }
    public String getJavaType() { return javaType; }
    public boolean isId() { return id; }
    public boolean isRequired() { return required; }
    public boolean isUnique() { return unique; }
    public List<String> getEnumValues() { return Collections.unmodifiableList(enumValues); }
    public List<CompiledEnumOption> getEnumOptions() { return Collections.unmodifiableList(enumOptions); }
    public String getReferenceTarget() { return referenceTarget; }
    public CompiledReferenceSemantics getReferenceSemantics() { return referenceSemantics; }
    public String getDomainType() { return domainType; }
    public CompiledSchema getSchema() { return schema; }
    public CompiledPresentationMetadata getUi() { return ui; }
    public String getConnectable() { return connectable; }
    /** The previous field name this field was renamed from, or null if this is not a declared rename. */
    public String getRenamedFrom() { return renamedFrom; }
    /** LIFT-UPLOAD-P2: contentTypes/maxSizeBytes/multiple for a `file`-typed field; null otherwise. */
    public CompiledFileMetadata getFile() { return file; }
}
