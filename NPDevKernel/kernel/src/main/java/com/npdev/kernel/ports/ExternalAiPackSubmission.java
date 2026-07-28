package com.npdev.kernel.ports;

/**
 * ADR-0009: what a caller asks an {@link ExternalAiCapabilityContract} to send. {@code packJson}
 * is expected to already be a redacted, chunked artefact matching {@code external-ai-pack.schema.json}
 * (built by the P2 pack core) -- this port never redacts anything itself, it only transports what
 * it is handed. {@code packManifestSha256} is restated here so an adapter can prove it sent exactly
 * the pack it was given, not some other content.
 */
public record ExternalAiPackSubmission(
        String missionId,
        String vendorId,
        String packManifestSha256,
        String packJson
) {
    public ExternalAiPackSubmission {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("missionId must be non-blank");
        }
        if (packManifestSha256 == null || packManifestSha256.isBlank()) {
            throw new IllegalArgumentException("packManifestSha256 must be non-blank");
        }
        if (packJson == null || packJson.isBlank()) {
            throw new IllegalArgumentException("packJson must be non-blank");
        }
    }
}
