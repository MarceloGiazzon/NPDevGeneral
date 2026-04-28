package com.npdev.kernel.procedures;

import java.util.List;

public record ProcedureDefinition(
        String name,
        List<ProcedureStep> steps
) {
    public ProcedureDefinition {
        name = normalizeRequired(name, "name");
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value.trim();
    }
}
