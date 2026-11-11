package com.npdev.kernel.procedures;

import java.util.List;

/**
 * @param runAsSystem {@code runAs: "system"} in the model: every step of this procedure, and of every procedure it
 *                    calls (the call happens inside the elevated scope, so it stays elevated), runs with the tenant's
 *                    system principal. The tenant never changes.
 */
public record ProcedureDefinition(
        String name,
        List<ProcedureStep> steps,
        boolean runAsSystem
) {
    /** Runs as the caller (the default). */
    public ProcedureDefinition(String name, List<ProcedureStep> steps) {
        this(name, steps, false);
    }

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
