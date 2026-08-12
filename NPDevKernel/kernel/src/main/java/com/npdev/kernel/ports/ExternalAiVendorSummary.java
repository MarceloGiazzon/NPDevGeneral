package com.npdev.kernel.ports;

/**
 * The non-secret description of one configured vendor, for a caller that has to render a choice
 * ("which provider and model?") before it can send anything.
 *
 * <p>Every field here is safe to serialize into an HTTP response or a log line. {@code keyPresent}
 * is a boolean the adapter computes by asking whether the env var resolves to a non-blank value --
 * the value itself never enters this record, so no caller can leak it by returning what it was
 * given. {@code keyEnvVarName} is the NAME only, which
 * {@link com.npdev.kernel.ports.ExternalAiCapabilityContract} implementations already treat as
 * loggable, and which is the difference between "not configured" and an operator knowing which
 * variable to set.
 *
 * <p>Field naming is deliberate, not incidental: {@code keyPresent} and {@code keyEnvVarName} are
 * both chosen NOT to match the platform's redaction pattern
 * ({@code pass|pwd|secret|token|apikey|api[_-]?key|authorization|credential|privatekey}, see
 * {@code npdev_monitor.redact}). A field called {@code apiKeyEnvVar} would match, and every support
 * bundle carrying this response would show {@code <redacted>} where a diagnosable name should be.
 */
public record ExternalAiVendorSummary(
        String vendorId,
        String defaultModel,
        String keyEnvVarName,
        boolean keyPresent,
        boolean effortSupported
) {
    public ExternalAiVendorSummary {
        if (vendorId == null || vendorId.isBlank()) {
            throw new IllegalArgumentException("vendorId must be non-blank");
        }
    }
}
