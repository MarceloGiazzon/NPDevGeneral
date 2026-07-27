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
}
