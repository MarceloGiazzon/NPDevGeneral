package com.npdev.kernel.procedures;

public record ProcedureStepResult(
        String name,
        ProcedureStepType type,
        boolean ok,
        String code,
        String message
) {
    public ProcedureStepResult {
        name = name == null ? "" : name.trim();
        code = code == null ? "" : code.trim();
        message = message == null ? "" : message.trim();
    }

    public static ProcedureStepResult success(ProcedureStep step) {
        return new ProcedureStepResult(step.name(), step.type(), true, "OK", "");
    }

    public static ProcedureStepResult failure(ProcedureStep step, String code, String message) {
        return new ProcedureStepResult(step.name(), step.type(), false, code, message);
    }
}
