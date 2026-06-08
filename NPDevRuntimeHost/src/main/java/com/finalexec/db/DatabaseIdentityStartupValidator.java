package com.finalexec.db;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

@Component
public final class DatabaseIdentityStartupValidator implements ApplicationRunner {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final String engine;
    private final String storageMode;
    private final String resolvedDatabaseName;
    private final String jdbcUrl;

    public DatabaseIdentityStartupValidator(
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${npdev.database.engine:}") String engine,
            @Value("${npdev.storage.mode:}") String storageMode,
            @Value("${npdev.database.resolved-name:}") String resolvedDatabaseName,
            @Value("${spring.datasource.url:}") String jdbcUrl
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.engine = engine == null ? "" : engine.trim();
        this.storageMode = storageMode == null ? "" : storageMode.trim();
        this.resolvedDatabaseName = resolvedDatabaseName == null ? "" : resolvedDatabaseName.trim();
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!"jdbc".equalsIgnoreCase(storageMode) || resolvedDatabaseName.isBlank()) {
            return;
        }

        if ("Postgres".equalsIgnoreCase(engine)) {
            DataSource dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource == null) {
                throw new IllegalStateException("Configured physical database '" + resolvedDatabaseName
                        + "', but no JDBC DataSource is available. Check db.definition.json, resolved-db-plan.json, Docker/service, and data folder.");
            }
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("select current_database()")) {
                String actual = rs.next() ? rs.getString(1) : "";
                if (!resolvedDatabaseName.equals(actual)) {
                    throw mismatch(resolvedDatabaseName, actual);
                }
            }
            return;
        }

        if ("H2Local".equalsIgnoreCase(engine) || "H2Server".equalsIgnoreCase(engine)) {
            String normalizedUrl = jdbcUrl.toLowerCase(Locale.ROOT);
            if (!normalizedUrl.contains(resolvedDatabaseName.toLowerCase(Locale.ROOT))) {
                throw mismatch(resolvedDatabaseName, jdbcUrl);
            }
        }
    }

    private static IllegalStateException mismatch(String expected, String actual) {
        return new IllegalStateException("Configured database is '" + expected + "', but connected database is '"
                + actual + "'. Check db.definition.json, resolved-db-plan.json, Docker/service, and data folder.");
    }
}
