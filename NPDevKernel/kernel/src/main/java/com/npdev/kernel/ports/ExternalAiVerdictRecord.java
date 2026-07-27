package com.npdev.kernel.ports;

/**
 * The typed shell around an ingested {@code external-ai-verdict.schema.json} body (ADR-0009).
 * {@link #RECORD_KIND} is the literal every verdict is filed under -- never
 * {@code independent-human-review}, never a sign-off (honesty rule 1). Concrete adapters validate
 * the raw {@code verdictJson} actually carries {@code recordKind == RECORD_KIND},
 * {@code noRepoAccess == true} and {@code autoApplied == false} before constructing one of these;
 * a verdict record existing at all is the proof those checks already passed.
 */
public record ExternalAiVerdictRecord(
        String missionId,
        String packManifestSha256,
        String vendorId,
        String model,
        String verdictJson
) {
    public static final String RECORD_KIND = "external-ai-verdict";

    public ExternalAiVerdictRecord {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("missionId must be non-blank");
        }
        if (vendorId == null || vendorId.isBlank()) {
            throw new IllegalArgumentException("vendorId must be non-blank");
        }
        if (verdictJson == null || verdictJson.isBlank()) {
            throw new IllegalArgumentException("verdictJson must be non-blank");
        }
    }
}
