package com.npdev.kernel.ports;

/**
 * ADR-0009: the kernel port mediating all egress to an external AI vendor -- sibling of
 * {@link PersistenceCapabilityContract} in {@code CapabilityContractCatalog}.
 *
 * <p>{@link #submitPack} is deliberately <b>fail-closed</b>, the same inverted-default-method
 * convention as {@code ConceptGateway.authorizeWrite()}: a contract with no concrete adapter opted
 * in denies rather than silently sending anything. Nothing leaves any app until an adapter
 * explicitly overrides this method -- per app, per mission, per vendor, matching the model
 * surface's {@code externalAi.egress} default of {@code denied}.</p>
 */
public interface ExternalAiCapabilityContract {

    default ExternalAiRunResult submitPack(ExternalAiPackSubmission submission) {
        throw new ExternalAiEgressDeniedException(
                "EGRESS_DENIED",
                "This ExternalAiCapabilityContract has no adapter opted in to send a pack; denying "
                        + "mission " + submission.missionId() + " rather than sending unchecked.");
    }

    /**
     * Ingest a verdict obtained out-of-band (e.g. pasted back after a manual paste-transport
     * round, per D2). Ingestion never causes egress, so there is no safe "deny" default here --
     * a concrete adapter must implement it.
     */
    ExternalAiVerdictRecord ingestVerdict(String missionId, String vendorId, String verdictJson);

    /**
     * Send a free-form prompt to a configured vendor and return its prose answer.
     *
     * <p>Fail-closed by the same inverted-default convention as {@link #submitPack}: an app whose
     * {@code npdev.externalai.provider} is still the default {@code inproc} has no adapter opted in
     * here, so it denies rather than sending. The agent-proxy surface in a generated app depends on
     * that: "no provider configured" has to be an honest, reportable state, never an accidental
     * egress.
     *
     * <p>Separate from {@link #submitPack} because that method validates the vendor's reply as an
     * {@link ExternalAiVerdictRecord} and rejects anything that is not one -- see
     * {@link ExternalAiGenerationRequest} for why reusing it would fail on every valid answer.
     */
    default ExternalAiGenerationResult generateText(ExternalAiGenerationRequest request) {
        throw new ExternalAiEgressDeniedException(
                "EGRESS_DENIED",
                "This ExternalAiCapabilityContract has no adapter opted in to send a prompt; denying "
                        + "rather than sending unchecked to vendor '" + request.vendorId() + "'.");
    }

    /**
     * The vendors this contract can actually reach, for a caller that must render a choice before
     * sending. Empty by default: an adapter that cannot generate text has no vendors to offer, and
     * an empty list is what tells a UI to say "not configured" instead of showing an empty dropdown
     * that fails on use.
     */
    default java.util.List<ExternalAiVendorSummary> configuredVendors() {
        return java.util.List.of();
    }
}
