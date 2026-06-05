package com.npdev.generator.dbconfig;

import java.util.Locale;

public enum DatabaseEngine {
    IN_MEMORY("InMemory", "in-memory"),
    H2_LOCAL("H2Local", "jdbc"),
    H2_SERVER("H2Server", "jdbc"),
    POSTGRES("Postgres", "jdbc");

    private final String externalName;
    private final String storageMode;

    DatabaseEngine(String externalName, String storageMode) {
        this.externalName = externalName;
        this.storageMode = storageMode;
    }

    public String externalName() {
        return externalName;
    }

    public String storageMode() {
        return storageMode;
    }

    public boolean jdbc() {
        return "jdbc".equals(storageMode);
    }

    public static DatabaseEngine parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("database.engine is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DatabaseEngine engine : values()) {
            if (engine.externalName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return engine;
            }
        }
        throw new IllegalArgumentException("Unsupported database.engine: " + value
                + " (expected Postgres, InMemory, H2Local, or H2Server)");
    }
}
