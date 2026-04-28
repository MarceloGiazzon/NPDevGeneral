package com.npdev.dsl.v1.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {
    private final List<String> errors;
    private final List<String> warnings;
    private final List<ValidationDiagnostic> diagnostics;

    public ValidationResult(List<String> errors, List<String> warnings) {
        this(
                errors,
                warnings,
                inferSemanticDiagnostics(errors, warnings)
        );
    }

    public ValidationResult(List<String> errors, List<String> warnings, List<ValidationDiagnostic> diagnostics) {
        this.errors = errors == null ? List.of() : List.copyOf(new ArrayList<>(errors));
        this.warnings = warnings == null ? List.of() : List.copyOf(new ArrayList<>(warnings));
        this.diagnostics = diagnostics == null ? List.of() : List.copyOf(new ArrayList<>(diagnostics));
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public List<ValidationDiagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public static ValidationResult fromDiagnostics(List<ValidationDiagnostic> diagnostics) {
        List<ValidationDiagnostic> safeDiagnostics =
                diagnostics == null ? List.of() : List.copyOf(new ArrayList<>(diagnostics));

        List<String> errors = safeDiagnostics.stream()
                .filter(diagnostic -> diagnostic.getSeverity() == ValidationSeverity.ERROR)
                .map(ValidationDiagnostic::getMessage)
                .toList();
        List<String> warnings = safeDiagnostics.stream()
                .filter(diagnostic -> diagnostic.getSeverity() == ValidationSeverity.WARNING)
                .map(ValidationDiagnostic::getMessage)
                .toList();

        return new ValidationResult(errors, warnings, safeDiagnostics);
    }

    private static List<ValidationDiagnostic> inferSemanticDiagnostics(List<String> errors, List<String> warnings) {
        List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        if (errors != null) {
            for (String error : errors) {
                diagnostics.add(ValidationDiagnosticNormalizer.semanticDiagnostic(error, ValidationSeverity.ERROR));
            }
        }
        if (warnings != null) {
            for (String warning : warnings) {
                diagnostics.add(ValidationDiagnosticNormalizer.semanticDiagnostic(warning, ValidationSeverity.WARNING));
            }
        }
        return List.copyOf(diagnostics);
    }
}
