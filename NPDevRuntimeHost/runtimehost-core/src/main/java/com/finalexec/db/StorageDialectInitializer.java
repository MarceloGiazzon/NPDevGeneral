package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.Locale;

/**
 * Pins the process's {@link SqlDialect} from the engine this app was GENERATED for, before anything
 * asks for one.
 *
 * <p><b>Without this, adding MySQL to the registry changes nothing.</b> Every store falls back to
 * {@link SqlDialects#active()}, which defaults to Postgres -- so an app generated for MySQL would
 * boot happily and emit Postgres SQL, which is the silent-wrong-answer failure this whole seam
 * exists to prevent, arriving through the back door.
 *
 * <p><b>Why {@code npdev.database.engine} and not the JDBC URL or the driver.</b> The engine is a
 * GENERATION-time fact: the schema realization SQL, the conversion hooks and the capability check
 * were all produced for it. Sniffing the connection at boot would let a misconfigured URL silently
 * select a different dialect than the one the app's own DDL was written in -- the app would run,
 * and the mismatch would surface as a query failing much later. Reading the generated property
 * means a URL that disagrees with the generated schema fails as a connection error, which is a
 * question an operator can answer.
 *
 * <p>{@code InMemory} deliberately does not pin anything: it has no SQL, and no store that consults
 * a dialect is created for it.
 */
@Configuration
public class StorageDialectInitializer {

    private final String engine;

    public StorageDialectInitializer(@Value("${npdev.database.engine:InMemory}") String engine) {
        this.engine = engine;
    }

    /**
     * @throws IllegalStateException naming the engine and the registered dialects when the app was
     *         generated for an engine no dialect serves. Falling back to Postgres here would be the
     *         exact defect described above; refusing at boot is the honest alternative, and it is
     *         also the earliest point at which this is detectable.
     */
    @PostConstruct
    public void pinDialect() {
        String normalized = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || "inmemory".equals(normalized)) {
            return;
        }
        String dialectName = switch (normalized) {
            case "postgres", "postgresql" -> "postgres";
            case "h2local", "h2server", "h2" -> "h2";
            case "mysql", "mariadb" -> "mysql";
            case "sqlserver", "mssql" -> "sqlserver";
            default -> normalized;
        };
        try {
            SqlDialect dialect = SqlDialects.forName(dialectName);
            SqlDialects.setActive(dialect);
            System.out.println("NPDev storage: dialect '" + dialect.name() + "' pinned from "
                    + "npdev.database.engine=" + engine);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException(
                    "This app was generated for npdev.database.engine=" + engine + ", which no registered "
                    + "SqlDialect serves (" + unknown.getMessage() + "). Refusing to boot: falling back to "
                    + "Postgres would run the app against a different engine than its own schema SQL was "
                    + "written for, and every query would look fine until one did not.", unknown);
        }
    }
}
