package com.npdev.dsl.v1.compiled;

import java.util.Map;

/**
 * R6.2: compiled form of {@link com.npdev.dsl.v1.ast.WebhookAst} -- see that class's javadoc for
 * the full design rationale (R6.1's HMAC/env-var-secret posture reused for the inbound direction).
 */
public record CompiledWebhook(String source, String hmacSecretEnvVar, String eventName, Map<String, String> fieldMapping) {
    public CompiledWebhook {
        fieldMapping = fieldMapping == null ? Map.of() : Map.copyOf(fieldMapping);
    }
}
