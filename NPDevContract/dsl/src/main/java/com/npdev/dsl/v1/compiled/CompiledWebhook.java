package com.npdev.dsl.v1.compiled;

import java.util.Map;

/**
 * R6.2: compiled form of {@link com.npdev.dsl.v1.ast.WebhookAst} -- see that class's javadoc for
 * the full design rationale (R6.1's HMAC/env-var-secret posture reused for the inbound direction).
 */
public record CompiledWebhook(String source, String hmacSecretEnvVar, String eventName, Map<String, String> fieldMapping,
                              String startFlow, String idempotencyKey, Map<String, Object> signature) {
    public CompiledWebhook {
        eventName = eventName == null ? "" : eventName;
        fieldMapping = fieldMapping == null ? Map.of() : Map.copyOf(fieldMapping);
        startFlow = startFlow == null ? "" : startFlow;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        signature = signature == null ? Map.of() : Map.copyOf(signature);
    }

    /** Pre-INT-6 shape: an event-only door with the NPDev signature scheme. */
    public CompiledWebhook(String source, String hmacSecretEnvVar, String eventName, Map<String, String> fieldMapping) {
        this(source, hmacSecretEnvVar, eventName, fieldMapping, null, null, null);
    }
}
