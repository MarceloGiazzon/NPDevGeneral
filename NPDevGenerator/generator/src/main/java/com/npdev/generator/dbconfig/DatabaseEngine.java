package com.npdev.generator.dbconfig;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.MySqlDialect;
import com.npdev.kernel.storage.sql.PostgresDialect;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlServerDialect;

import java.util.Locale;

public enum DatabaseEngine {
    IN_MEMORY("InMemory", "in-memory"),
    H2_LOCAL("H2Local", "jdbc"),
    H2_SERVER("H2Server", "jdbc"),
    POSTGRES("Postgres", "jdbc"),
    // storage/PLAN.md S4b/S5. New VALUES on the existing paradigm axis -- storageMode stays "jdbc",
    // because that second string is the split a document engine will use ("document"), not a
    // dialect name. Adding these as jdbc engines rather than inventing a parallel concept is the
    // whole reason the axis was already there.
    MYSQL("MySQL", "jdbc"),
    SQL_SERVER("SqlServer", "jdbc");

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
     * The engine's standard listening port, or 0 when it does not listen.
     *
     * <p>Lives on the enum rather than as a chain of {@code engine == X ? p : ...} in the loader:
     * that chain had already been written twice with different fallbacks, and a third engine added
     * to one copy and not the other is how an app gets a plan with port 0 and a connection refused
     * that names nothing useful.
     */
    public int defaultPort() {
        return switch (this) {
            case POSTGRES -> 5432;
            case MYSQL -> 3306;
            case SQL_SERVER -> 1433;
            case H2_SERVER -> 9092;
            case H2_LOCAL, IN_MEMORY -> 0;
        };
    }

    /** Whether NPDev's generated Docker Compose runs this engine as a container. */
    public boolean usesContainer() {
        return this == POSTGRES || this == MYSQL || this == SQL_SERVER;
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
            case MYSQL -> MySqlDialect.INSTANCE;
            case SQL_SERVER -> SqlServerDialect.INSTANCE;
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
