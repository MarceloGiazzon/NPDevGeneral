package com.npdev.runtime.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-39 layer 2: {@link IdentityRoleLookup#tokenVersion} must keep its documented "never throws"
 * contract (this sits on the hot per-request claim-to-context path) even against a stale built-in-pack
 * copy missing {@code token_version} -- but it must stop failing SILENTLY. Before this, a schema
 * mismatch here just returned 0 with no trace, indistinguishable from "actor unknown" or "column is
 * NULL".
 */
class IdentityRoleLookupSchemaMismatchTest {

    private static final String TENANT = "tenantx";

    private DataSource dataSource;
    private CapturingHandler capturingHandler;
    private Logger logger;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        logger = Logger.getLogger(IdentityRoleLookup.class.getName());
        capturingHandler = new CapturingHandler();
        logger.addHandler(capturingHandler);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(capturingHandler);
    }

    @Test
    void staleSchemaMissingTokenVersionReturnsZeroButLogsLoudly() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // REG-39: a pre-LNCH-4 identity pack copy -- no token_version column at all.
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "active BOOLEAN, tenant_id VARCHAR(120))");
            s.execute("INSERT INTO identity_users VALUES ('" + UUID.randomUUID() + "','alice',TRUE,'" + TENANT + "')");
        }

        int version = IdentityRoleLookup.tokenVersion(dataSource, TENANT, "alice");

        assertEquals(0, version, "the never-throws contract must hold even against a stale schema");
        assertTrue(capturingHandler.records.stream().anyMatch(r ->
                        r.getLevel() == Level.SEVERE && r.getMessage() != null
                                && r.getMessage().toLowerCase().contains("token_version")),
                "a schema mismatch must be logged loudly, not silently swallowed: " + capturingHandler.records);
    }

    @Test
    void freshSchemaReadsVersionWithoutLogging() throws Exception {
        UUID userId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "active BOOLEAN, tenant_id VARCHAR(120), token_version INT)");
            s.execute("INSERT INTO identity_users VALUES ('" + userId + "','alice',TRUE,'" + TENANT + "',7)");
        }

        int version = IdentityRoleLookup.tokenVersion(dataSource, TENANT, "alice");

        assertEquals(7, version);
        assertFalse(capturingHandler.records.stream().anyMatch(r -> r.getLevel() == Level.SEVERE),
                "the fresh-schema path must not log a schema-mismatch severity");
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override public void publish(LogRecord record) { records.add(record); }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
