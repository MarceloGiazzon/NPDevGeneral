package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-15: on a TRANSIENT database fault (lock/statement timeout, pool exhaustion -- not a schema mismatch) the
 * revocation check fails CLOSED (owner decision 2026-10-28): the stored version is unknown, so a tv-bearing token is
 * refused for that request, and a warning says so. {@link IdentityRoleLookup#tokenVersion} itself keeps returning 0
 * for its minting callers. The schema-mismatch path (SQLState 42xxx) stays fail-open with its SEVERE log.
 */
class IdentityRoleLookupTransientFaultTest {

    private static final IdentityPackTableNames TABLES = new IdentityPackTableNames(
            "identity_users", "identity_roles", "identity_user_roles", "identity_user_role_permissions");

    private final List<LogRecord> records = new ArrayList<>();
    private Handler handler;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger(IdentityRoleLookup.class.getName());
        handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
    }

    @AfterEach
    void tearDown() {
        logger.removeHandler(handler);
    }

    @Test
    void aTransientSqlFaultRefusesAVersionZeroTokenAndSaysSo() {
        DataSource failing = new FailingDataSource("40001");

        assertTrue(IdentityRoleLookup.isTokenRevoked(0, failing, TABLES, "tenantx", "alice"),
                "SEC-15 fail closed: an unknown stored version must not accept a (possibly revoked) tv=0 token");
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage() != null
                        && r.getMessage().contains("REFUSED") && r.getMessage().contains("40001")),
                "a non-schema fault must be visible to the operator: " + records);
    }

    @Test
    void aTransientSqlFaultStillReturnsZeroToTheMintingCallers() {
        assertEquals(0, IdentityRoleLookup.tokenVersion(new FailingDataSource("40001"), TABLES, "tenantx", "alice"),
                "tokenVersion() keeps its never-throws contract for AgentLink minting");
    }

    @Test
    void aSchemaMismatchStaysFailOpenSoAMissingColumnLocksNobodyOut() {
        assertFalse(IdentityRoleLookup.isTokenRevoked(0, new FailingDataSource("42S22"), TABLES, "tenantx", "alice"));
    }

    @Test
    void aSchemaMismatchKeepsItsSevereLogAndGetsNoSecondWarning() {
        DataSource failing = new FailingDataSource("42S22");

        int version = IdentityRoleLookup.tokenVersion(failing, TABLES, "tenantx", "alice");

        assertEquals(0, version);
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.SEVERE));
        assertFalse(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING),
                "the schema-mismatch path is already loud; it must not also emit the transient-fault warning");
    }

    private static final class FailingDataSource implements DataSource {
        private final String sqlState;

        private FailingDataSource(String sqlState) {
            this.sqlState = sqlState;
        }

        @Override public Connection getConnection() throws SQLException { throw new SQLException("simulated fault", sqlState); }
        @Override public Connection getConnection(String u, String p) throws SQLException { throw new SQLException("simulated fault", sqlState); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
