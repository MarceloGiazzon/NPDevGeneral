package com.npdev.kernel.ports;

/**
 * A free-form prompt for {@link ExternalAiCapabilityContract#generateText}, as distinct from
 * {@link ExternalAiPackSubmission}'s review mission.
 *
 * <p>Why this is a separate operation rather than a second use of {@code submitPack}: that path
 * validates the vendor's answer as an {@link ExternalAiVerdictRecord} -- it requires
 * {@code recordKind}, {@code noRepoAccess} and {@code autoApplied} fields and throws without them.
 * That is exactly right for an ADR-0009 review verdict and exactly wrong for prose, so a prompt sent
 * through {@code submitPack} would fail on every well-formed reply.
 *
 * <p>{@code vendorId} names WHICH configured vendor to use. It deliberately cannot name WHERE the
 * request goes: the endpoint comes from the server-side vendor profile, so an untrusted caller
 * choosing a vendor cannot turn this into an SSRF primitive.
 *
 * <p>{@code effort} is optional and advisory ({@code low}/{@code medium}/{@code high}); a vendor
 * whose API has no equivalent ignores it rather than failing -- see
 * {@link ExternalAiVendorSummary#effortSupported()} for what a caller can know in advance.
 */
public record ExternalAiGenerationRequest(
        String vendorId,
        String model,
        String effort,
        String prompt
) {
    public ExternalAiGenerationRequest {
        if (vendorId == null || vendorId.isBlank()) {
            throw new IllegalArgumentException("vendorId must be non-blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must be non-blank");
        }
        if (effort != null && !effort.isBlank()
                && !("low".equals(effort) || "medium".equals(effort) || "high".equals(effort))) {
            throw new IllegalArgumentException("effort must be one of low, medium, high -- got: " + effort);
        }
    }
}
