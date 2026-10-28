package com.npdev.adapters.authz.defaultpolicy;

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
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-15: on a TRANSIENT database fault (lock/statement timeout, pool exhaustion -- not a schema mismatch)
 * {@link IdentityPermissionOverrideLookup#overridesFor} fails CLOSED (owner decision 2026-10-28): it throws
 * OverrideLookupUnavailable, which the policy turns into a denial, and warns. The schema-mismatch path (SQLState
 * 42xxx) stays fail-open ("no override") with its SEVERE log.
 */
class IdentityPermissionOverrideLookupTransientFaultTest {

    private static final IdentityPackTableNames TABLES = new IdentityPackTableNames(
            "identity_users", "identity_roles", "identity_user_roles", "identity_user_role_permissions");

    private final List<LogRecord> records = new ArrayList<>();
    private Handler handler;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = Logger.getLogger(IdentityPermissionOverrideLookup.class.getName());
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
    void aTransientSqlFaultFailsClosedAndIsLoggedAsDenied() {
        assertThrows(IdentityPermissionOverrideLookup.OverrideLookupUnavailable.class,
                () -> IdentityPermissionOverrideLookup.overridesFor(new FailingDataSource("40001"), TABLES, "tenantx", "alice"),
                "SEC-15 fail closed: unknown overrides must not read as 'no restriction'");
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING && r.getMessage() != null
                        && r.getMessage().contains("permission DENIED") && r.getMessage().contains("40001")),
                "a non-schema fault must be visible to the operator: " + records);
    }

    @Test
    void aSchemaMismatchKeepsItsSevereLogAndGetsNoSecondWarning() {
        Map<String, java.util.Set<String>> overrides =
                IdentityPermissionOverrideLookup.overridesFor(new FailingDataSource("42S02"), TABLES, "tenantx", "alice");

        assertEquals(Map.of(), overrides);
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
