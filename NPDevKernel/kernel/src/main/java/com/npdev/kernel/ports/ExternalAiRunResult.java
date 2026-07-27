package com.npdev.kernel.ports;

/**
 * Mirrors {@code external-ai-run.schema.json}'s RUN / NOT_RUN split closely enough that
 * {@code run-external-ai-gate.ps1} (P8) can assert every mission has exactly one of these -- never
 * neither, which is the blind-spot shape {@code check-register-consistency.py} was already written
 * to catch one level over (ADR-0009).
 */
public record ExternalAiRunResult(
        String missionId,
        String runStatus,
        String notRunReason,
        String packManifestSha256,
        String vendorId
) {
    public static ExternalAiRunResult notRun(String missionId, String reason) {
        return new ExternalAiRunResult(missionId, "NOT_RUN", reason, null, null);
    }

    public static ExternalAiRunResult run(String missionId, String packManifestSha256, String vendorId) {
        return new ExternalAiRunResult(missionId, "RUN", null, packManifestSha256, vendorId);
    }
}
