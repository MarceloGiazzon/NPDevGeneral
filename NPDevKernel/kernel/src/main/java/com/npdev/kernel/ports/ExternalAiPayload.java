package com.npdev.kernel.ports;

import java.util.Map;

/**
 * Parses the untyped {@code Map} payload a flow/procedure capability-call step passes for
 * {@code externalAi.submitPack} into an {@link ExternalAiPackSubmission} -- shared by
 * external-ai-inproc and external-ai-http so the payload shape only needs to be defined once,
 * the same convention {@link MailPayload} established for mail.
 */
public final class ExternalAiPayload {

    private ExternalAiPayload() {
    }

    public static ExternalAiPackSubmission parseSubmission(java.util.List<Object> args) {
        if (args == null || args.isEmpty() || !(args.get(0) instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("submitPack requires a pack submission payload");
        }
        return new ExternalAiPackSubmission(
                String.valueOf(map.get("missionId")),
                map.get("vendorId") == null ? null : String.valueOf(map.get("vendorId")),
                String.valueOf(map.get("packManifestSha256")),
                String.valueOf(map.get("packJson"))
        );
    }
}
