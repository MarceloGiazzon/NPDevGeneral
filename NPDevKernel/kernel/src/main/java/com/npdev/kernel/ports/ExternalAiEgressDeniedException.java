package com.npdev.kernel.ports;

public final class ExternalAiEgressDeniedException extends RuntimeException {
    private final String code;

    public ExternalAiEgressDeniedException(String code, String message) {
        super(message);
        this.code = normalize(code);
    }

    public String code() {
        return code;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "EXTERNAL_AI_EGRESS_DENIED";
        }
        return value.trim();
    }
}
