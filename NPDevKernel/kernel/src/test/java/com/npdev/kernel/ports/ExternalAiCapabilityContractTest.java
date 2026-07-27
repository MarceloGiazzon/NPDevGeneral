package com.npdev.kernel.ports;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-0009 / P1: RED-first proof that the fail-closed default actually throws before any adapter
 * opts in -- the same shape as the {@code authorizeWrite} proof this port's default mirrors.
 */
class ExternalAiCapabilityContractTest {

    @Test
    void submitPackDeniesEgressByDefault() {
        ExternalAiCapabilityContract contract = new ExternalAiCapabilityContract() {
            @Override
            public ExternalAiVerdictRecord ingestVerdict(String missionId, String vendorId, String verdictJson) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
        ExternalAiPackSubmission submission = new ExternalAiPackSubmission(
                "M1-SEC-GENCODE", "openai", "a".repeat(64), "{\"missionId\":\"M1-SEC-GENCODE\"}");

        ExternalAiEgressDeniedException thrown = assertThrows(
                ExternalAiEgressDeniedException.class,
                () -> contract.submitPack(submission));

        assertEquals("EGRESS_DENIED", thrown.code());
        assertEquals(
                "This ExternalAiCapabilityContract has no adapter opted in to send a pack; denying "
                        + "mission M1-SEC-GENCODE rather than sending unchecked.",
                thrown.getMessage());
    }
}
