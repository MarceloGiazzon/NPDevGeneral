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

    public DomainTypeAst(
            String name,
            String baseType,
            SchemaAst validationSchema,
            List<String> normalizationRules,
            String formatHint,
            List<String> examples,
            DomainTypeUiAst ui
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
}
