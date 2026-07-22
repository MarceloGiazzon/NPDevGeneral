package com.npdev.dsl.v1.parser;

import com.npdev.dsl.v1.validation.ValidationDiagnostic;

import java.io.IOException;
import java.util.Objects;

public final class DeprecationException extends IOException {
    private final ValidationDiagnostic diagnostic;

    public DeprecationException(String message) {
        super(message);
        this.diagnostic = null;
    }

    public DeprecationException(ValidationDiagnostic diagnostic) {
        super(Objects.requireNonNull(diagnostic, "diagnostic").getMessage());
        this.diagnostic = diagnostic;
    }

    public ValidationDiagnostic getDiagnostic() {
        return diagnostic;
    }
}
