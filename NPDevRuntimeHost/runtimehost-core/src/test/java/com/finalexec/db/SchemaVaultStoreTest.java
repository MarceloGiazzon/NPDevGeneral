package com.finalexec.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S17a (NPDEV_MEGA_ROADMAP.md): pins the Artifact Vault's read surface over the existing
 * per-fingerprint schema snapshots. The write side is the schema lifecycle's own -- this class only
 * reads -- so the fixtures here store snapshots in the same {@code npdev_schema_snapshot} shape
 * (fingerprint / snapshot_json / recorded_at_utc) and assert what a reader sees.
 */
class SchemaVaultStoreTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:vault-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE npdev_schema_snapshot ("
                    + "fingerprint VARCHAR(64) PRIMARY KEY, snapshot_json CLOB NOT NULL, recorded_at_utc BIGINT NOT NULL)");
        }
    }

    /** A minimal snapshot JSON in the exact shape SchemaSnapshotStore.toJson produces. */
    private static String snapshotJson(String... tableNames) {
        StringBuilder json = new StringBuilder("{\"tables\":{");
        for (int i = 0; i < tableNames.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(tableNames[i]).append("\":{\"name\":\"").append(tableNames[i])
                    .append("\",\"columns\":{},\"uniques\":[],\"foreignKeys\":[],\"indexes\":[]}");
        }
        return json.append("}}").toString();
    }

    private void store(String fingerprint, long recordedAtUtc, String json) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                connection.prepareStatement("INSERT INTO npdev_schema_snapshot (fingerprint, snapshot_json, recorded_at_utc) VALUES (?, ?, ?)")) {
            statement.setString(1, fingerprint);
            statement.setString(2, json);
            statement.setLong(3, recordedAtUtc);
            statement.executeUpdate();
        }
    }

    @Test
    void listsEverySnapshotNewestFirstWithTableCounts() throws Exception {
        store("fp-old", 1_000L, snapshotJson("pigments", "suppliers"));
        store("fp-new", 2_000L, snapshotJson("pigments", "suppliers", "orders"));

        List<SchemaVaultStore.VaultEntry> entries = SchemaVaultStore.list(dataSource);

        assertEquals(2, entries.size());
        assertEquals("fp-new", entries.get(0).fingerprint(), "newest recorded first");
        assertEquals(3, entries.get(0).tableCount());
        assertEquals("fp-old", entries.get(1).fingerprint());
        assertEquals(2, entries.get(1).tableCount());
        assertEquals(1_000L, entries.get(1).recordedAtUtc());
    }

    @Test
    void listsEmptyWhenTheTableWasNeverCreated() {
        // No npdev_schema_snapshot table at all -- a pre-B5-A database. Must read empty, not throw.
        String url = "jdbc:h2:mem:vault-empty-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        DataSource empty = new UrlDataSource(url);

        assertTrue(SchemaVaultStore.list(empty).isEmpty());
    }

    @Test
    void restorePlanForAnUnknownFingerprintReportsNotFound() throws Exception {
        Map<String, Object> plan = SchemaVaultStore.restorePlan(dataSource, "nope");

        assertEquals("nope", plan.get("targetFingerprint"));
        assertEquals(false, plan.get("found"));
        assertTrue(plan.containsKey("reason"));
    }

    @Test
    void restorePlanNamesTheTablesThatExistOnlyInTheTarget() throws Exception {
        store("fp-vaulted", 1_000L, snapshotJson("alpha", "bravo"));
        store("fp-current", 2_000L, snapshotJson("alpha", "gamma"));

        Map<String, Object> plan = SchemaVaultStore.restorePlan(dataSource, "fp-current", "fp-vaulted");

        assertEquals("fp-vaulted", plan.get("targetFingerprint"));
        assertEquals("fp-current", plan.get("currentFingerprint"));
        assertEquals(true, plan.get("found"));
        @SuppressWarnings("unchecked")
        List<String> onlyInTarget = (List<String>) plan.get("tablesOnlyInTarget");
        assertEquals(List.of("bravo"), onlyInTarget,
                "bravo exists in the vaulted target but not in the current snapshot");
        @SuppressWarnings("unchecked")
        List<String> onlyInCurrent = (List<String>) plan.get("tablesOnlyInCurrent");
        assertEquals(List.of("gamma"), onlyInCurrent,
                "gamma exists live but not in the vaulted target -- the destructive half");
        assertTrue(Boolean.TRUE.equals(plan.get("destructive")),
                "a live table absent from the target makes the plan destructive");
        assertEquals(List.of("alpha"), plan.get("commonTables"));
        assertEquals(2, plan.get("targetTableCount"));
    }

    @Test
    void restorePlanWithNoCurrentSnapshotDegradesHonestly() throws Exception {
        // No manifest on disk in a unit test means currentFingerprint is null -- the plan must still
        // answer what the target contains and never claim destructive certainty it cannot see.
        store("fp-vaulted", 1_000L, snapshotJson("alpha"));

        Map<String, Object> plan = SchemaVaultStore.restorePlan(dataSource, null, "fp-vaulted");

        assertEquals(true, plan.get("found"));
        assertFalse(Boolean.TRUE.equals(plan.get("destructive")),
                "without a live fingerprint the plan must not claim destructive drops");
        @SuppressWarnings("unchecked")
        List<String> onlyInTarget = (List<String>) plan.get("tablesOnlyInTarget");
        assertEquals(List.of("alpha"), onlyInTarget,
                "with no live snapshot every target table is 'only in target'");
    }

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
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}