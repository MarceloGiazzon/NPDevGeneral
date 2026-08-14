package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledDomainType {
    private final String name;
    private final String baseType;
    private final String javaType;
    private final CompiledSchema validationSchema;
    private final List<String> normalizationRules;
    private final String formatHint;
    private final List<String> examples;
    private final CompiledDomainTypeUi ui;
    private final CompiledOrigin origin;

    public CompiledDomainType(
            String name,
            String baseType,
            String javaType,
            CompiledSchema validationSchema,
            List<String> normalizationRules,
            String formatHint,
            List<String> examples,
            CompiledDomainTypeUi ui
    ) {
        this(name, baseType, javaType, validationSchema, normalizationRules, formatHint, examples, ui, null);
    }

    /** PACK-2: attaches pack-attribution provenance (see getOrigin) -- null for an app's own root-
     *  or context-declared domain type, non-null for a pack-contributed one. */
    public CompiledDomainType(
            String name,
            String baseType,
            String javaType,
            CompiledSchema validationSchema,
            List<String> normalizationRules,
            String formatHint,
            List<String> examples,
            CompiledDomainTypeUi ui,
            CompiledOrigin origin
    ) {
        this.name = name;
        this.baseType = baseType;
        this.javaType = javaType;
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

    public String getJavaType() {
        return javaType;
    }

    public CompiledSchema getValidationSchema() {
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

    public CompiledDomainTypeUi getUi() {
        return ui;
    }

    /** PACK-2: pack-attribution provenance, or null if this domain type is not pack-contributed. */
    public CompiledOrigin getOrigin() {
        return origin;
    }
}
