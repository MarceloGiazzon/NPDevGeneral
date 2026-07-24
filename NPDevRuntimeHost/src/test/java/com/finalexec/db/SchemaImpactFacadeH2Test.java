package com.finalexec.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * H2 coverage for {@link SchemaImpactFacade} (SER-P6.0). {@code forLiveDatabase} calls {@code
 * loadManifest()}, a fixed classpath lookup that returns {@code null} in this test's classpath (no
 * generated {@code npdev/db/schema-realization-manifest.json} resource here) — so the facade's
 * no-manifest branch is what's actually exercised. That is precisely the "never throws, sane result"
 * contract P6.0 promises: a non-null {@link SchemaImpactFacade.Result} with a {@code NO_CHANGES} report
 * and no fingerprints/token.
 */
class SchemaImpactFacadeH2Test {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void neverThrowsAndReturnsNoChangesWhenNoManifestOnClasspath() {
        SchemaImpactFacade.Result result = SchemaImpactFacade.forLiveDatabase(dataSource);
        assertNotNull(result);
        assertNotNull(result.report());
        assertNull(result.ackToken(), "no manifest -> no diff -> never DESTRUCTIVE -> no token");
    }

    /** Minimal {@link DataSource} over {@link DriverManager} (no H2-specific compile dependency). */
    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
