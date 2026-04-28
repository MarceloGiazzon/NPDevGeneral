package com.npdev.dsl.v1.validation;

import java.util.Objects;

public final class ValidationDiagnostic {
    private final ValidationLayer layer;
    private final ValidationSeverity severity;
    private final String code;
    private final String message;
    private final String sourceModule;
    private final String path;
    private final String concept;
    private final String field;
    private final String section;
    private final String ruleName;
    private final String suggestedFix;
    private final String helpKey;

    public ValidationDiagnostic(
            ValidationLayer layer,
            ValidationSeverity severity,
            String code,
            String message,
            String sourceModule,
            String path
    ) {
        this(layer, severity, code, message, sourceModule, path, null, null, null, null, null, null);
    }

    public ValidationDiagnostic(
            ValidationLayer layer,
            ValidationSeverity severity,
            String code,
            String message,
            String sourceModule,
            String path,
            String concept,
            String field,
            String section,
            String ruleName,
            String suggestedFix,
            String helpKey
    ) {
        this.layer = Objects.requireNonNull(layer, "layer");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.code = sanitize(code);
        this.message = canonicalizeConceptTerminology(sanitize(message));
        this.sourceModule = sanitize(sourceModule);
        this.path = optional(path);
        this.concept = optional(concept);
        this.field = optional(field);
        this.section = optional(section);
        this.ruleName = optional(ruleName);
        this.suggestedFix = optional(suggestedFix);
        this.helpKey = optional(helpKey);
    }

    public ValidationLayer getLayer() {
        return layer;
    }

    public ValidationSeverity getSeverity() {
        return severity;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getSourceModule() {
        return sourceModule;
    }

    public String getPath() {
        return path;
    }

    public String getConcept() {
        return concept;
    }

    public String getField() {
        return field;
    }

    public String getSection() {
        return section;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getSuggestedFix() {
        return suggestedFix;
    }

    public String getHelpKey() {
        return helpKey;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String canonicalizeConceptTerminology(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replace("Entity ", "Concept ")
                .replace(" entity ", " concept ");
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
