package com.npdev.dsl.v1.validation;

public enum ValidationLayer {
    STRUCTURAL("structural"),
    SEMANTIC("semantic"),
    UX_METADATA("ux-metadata");

    private final String externalName;

    ValidationLayer(String externalName) {
        this.externalName = externalName;
    }

    public String getExternalName() {
        return externalName;
    }
}
