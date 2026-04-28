package com.npdev.kernel.concepts;

public final class ConceptGatewayAccessDeniedException extends RuntimeException {
    private final String code;

    public ConceptGatewayAccessDeniedException(String code, String message) {
        super(message);
        this.code = normalize(code);
    }

    public String code() {
        return code;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "CONCEPT_GATEWAY_ACCESS_DENIED";
        }
        return value.trim();
    }
}
