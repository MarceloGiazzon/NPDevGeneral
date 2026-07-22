package com.npdev.dsl.v1.validation;

import java.io.IOException;
import java.util.List;

public final class ModelSchemaValidationException extends IOException {
    private final String sourceLabel;
    private final List<ValidationDiagnostic> diagnostics;

    public ModelSchemaValidationException(String sourceLabel, List<ValidationDiagnostic> diagnostics) {
        super(buildMessage(sourceLabel, diagnostics));
        this.sourceLabel = sourceLabel;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public List<ValidationDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    public ValidationDiagnostic getFirstDiagnostic() {
        return diagnostics.isEmpty() ? null : diagnostics.get(0);
    }

    private static String buildMessage(String sourceLabel, List<ValidationDiagnostic> diagnostics) {
        StringBuilder builder = new StringBuilder();
        builder.append("Model schema validation failed for ")
                .append(sourceLabel)
                .append(":");
        for (ValidationDiagnostic diagnostic : diagnostics) {
            builder.append(System.lineSeparator())
                    .append(" - ")
                    .append(diagnostic.getPath() == null ? "$" : diagnostic.getPath())
                    .append(": ")
                    .append(diagnostic.getMessage());
            if (diagnostic.getSuggestedFix() != null) {
                builder.append(" Suggested fix: ").append(diagnostic.getSuggestedFix());
            }
        }
        return builder.toString();
    }
}
