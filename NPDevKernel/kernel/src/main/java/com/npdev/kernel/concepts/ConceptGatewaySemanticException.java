package com.npdev.kernel.concepts;

public final class ConceptGatewaySemanticException extends RuntimeException {
    private final String code;

    public ConceptGatewaySemanticException(String code, String message) {
        super(message);
        this.code = code == null || code.isBlank() ? "CONCEPT_SEMANTIC_DENIED" : code.trim();
    }

    public String code() {
        return code;
    }
}
