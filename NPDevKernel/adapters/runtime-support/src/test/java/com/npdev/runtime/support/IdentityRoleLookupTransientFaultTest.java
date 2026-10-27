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
 * SEC-15: {@link IdentityRoleLookup#tokenVersion} keeps its documented "never throws, returns 0" contract on a
 * TRANSIENT database fault (lock/statement timeout, pool exhaustion -- not a schema mismatch), but that fail-open
 * must not be silent: for that request a revoked version-0 token cannot be told from a live one, and the operator
 * has to be able to see it. The schema-mismatch path (SQLState 42xxx) keeps its SEVERE log and is not asserted here.
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
    void aTransientSqlFaultStillReturnsZeroButIsLoggedAsRevocationNotEnforced() {
        DataSource failing = new FailingDataSource("40001");

        int version = IdentityRoleLookup.tokenVersion(failing, TABLES, "tenantx", "alice");

        assertEquals(0, version, "the never-throws contract is unchanged");
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage() != null
                        && r.getMessage().contains("revocation is NOT enforced") && r.getMessage().contains("40001")),
                "a non-schema fault must be visible to the operator: " + records);
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
