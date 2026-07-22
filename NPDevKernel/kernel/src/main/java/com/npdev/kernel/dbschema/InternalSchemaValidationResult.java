package com.npdev.kernel.dbschema;

import java.util.List;

public record InternalSchemaValidationResult(List<String> errors) {
    public InternalSchemaValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean valid() {
        return errors.isEmpty();
    }
}
