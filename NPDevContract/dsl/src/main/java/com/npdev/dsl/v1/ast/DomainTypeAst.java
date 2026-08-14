package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DomainTypeAst {
    private final String name;
    private final String baseType;
    private final SchemaAst validationSchema;
    private final List<String> normalizationRules;
    private final String formatHint;
    private final List<String> examples;
    private final DomainTypeUiAst ui;
    private final OriginAst origin;

    public DomainTypeAst(
            String name,
            String baseType,
            SchemaAst validationSchema,
            List<String> normalizationRules,
            String formatHint,
            List<String> examples,
            DomainTypeUiAst ui
    ) {
        this(name, baseType, validationSchema, normalizationRules, formatHint, examples, ui, null);
    }

    /** PACK-2: attaches pack-attribution provenance (see getOrigin) -- null for an app's own root-
     *  or context-declared domain type, non-null for a pack-contributed one. */
    public DomainTypeAst(
            String name,
            String baseType,
            SchemaAst validationSchema,
            List<String> normalizationRules,
            String formatHint,
            List<String> examples,
            DomainTypeUiAst ui,
            OriginAst origin
    ) {
        this.name = name;
        this.baseType = baseType;
        this.validationSchema = validationSchema;
        this.normalizationRules = normalizationRules == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(normalizationRules));
        this.formatHint = formatHint;
        this.examples = examples == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(examples));
        this.ui = ui;
        this.origin = origin;
    }

    public String getName() {
        return name;
    }

    public String getBaseType() {
        return baseType;
    }

    public SchemaAst getValidationSchema() {
        return validationSchema;
    }

    public List<String> getNormalizationRules() {
        return normalizationRules;
    }

    public String getFormatHint() {
        return formatHint;
    }

    public List<String> getExamples() {
        return examples;
    }

    public DomainTypeUiAst getUi() {
        return ui;
    }

    /** PACK-2: pack-attribution provenance, or null if this domain type is not pack-contributed. */
    public OriginAst getOrigin() {
        return origin;
    }
}
