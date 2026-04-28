package com.npdev.dsl.v1.resolution;

public final class ModelResolutionException extends RuntimeException {
    private final ResolutionDiagnosticCode code;

    public ModelResolutionException(ResolutionDiagnosticCode code, String message) {
        super((code == null ? ResolutionDiagnosticCode.ILLEGAL_OVERRIDE : code).name()
                + ": "
                + (message == null ? "" : message));
        this.code = code == null ? ResolutionDiagnosticCode.ILLEGAL_OVERRIDE : code;
    }

    public ResolutionDiagnosticCode getCode() {
        return code;
    }
}
