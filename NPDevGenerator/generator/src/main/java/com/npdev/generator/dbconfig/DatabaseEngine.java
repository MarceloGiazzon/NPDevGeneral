package com.npdev.generator.dbconfig;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.PostgresDialect;
import com.npdev.kernel.storage.sql.SqlDialect;

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

    /**
     * The SQL dialect this engine speaks.
     *
     * <p>The engine is known at GENERATION time, which is why the two emitters that decide column
     * types ({@code SchemaRealizationEmitter}, {@code ConversionHookEmitter}) ask here rather than
     * emitting code that resolves a dialect at runtime -- a hook that needs live state at generation
     * time is a known dead end in this codebase.
     *
     * @throws IllegalStateException for {@link #IN_MEMORY}, which has no SQL at all. Returning
     *         Postgres "so the caller has something" would be the silent-wrong-answer defect in the
     *         one place it is guaranteed to be wrong; {@link #jdbc()} is how a caller asks first.
     */
    public SqlDialect dialect() {
        return switch (this) {
            case POSTGRES -> PostgresDialect.INSTANCE;
            case H2_LOCAL, H2_SERVER -> H2Dialect.INSTANCE;
            case IN_MEMORY -> throw new IllegalStateException(
                    "database.engine=" + externalName + " stores nothing in SQL, so it has no SqlDialect. "
                    + "Guard with DatabaseEngine.jdbc() before asking.");
        };
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
