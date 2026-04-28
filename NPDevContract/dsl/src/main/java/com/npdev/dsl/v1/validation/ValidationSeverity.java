package com.npdev.dsl.v1.validation;

public enum ValidationSeverity {
    ERROR("error"),
    WARNING("warning"),
    INFO("info");

    private final String externalName;

    ValidationSeverity(String externalName) {
        this.externalName = externalName;
    }

    public String getExternalName() {
        return externalName;
    }
}
