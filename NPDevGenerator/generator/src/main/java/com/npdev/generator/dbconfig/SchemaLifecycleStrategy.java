package com.npdev.generator.dbconfig;

import java.util.Locale;

public enum SchemaLifecycleStrategy {
    DROP_AND_RECREATE_ON_STRUCTURE_CHANGE("DropAndRecreateOnStructureChange"),
    RECREATE_ON_APP_START("RecreateOnAppStart"),
    KEEP_EXISTING_IF_COMPATIBLE("KeepExistingIfCompatible");

    private final String externalName;

    SchemaLifecycleStrategy(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    public static SchemaLifecycleStrategy parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("schemaLifecycle.strategy is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SchemaLifecycleStrategy strategy : values()) {
            if (strategy.externalName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unsupported schemaLifecycle.strategy: " + value);
    }
}
