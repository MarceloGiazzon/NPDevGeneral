package com.finalexec.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1.2: proves {@link WorkspaceMenuSeeder}'s new default {@code reconcile} mode against a real H2
 * database, without a Spring context -- {@code WorkspaceMenuSeeder} is plain-constructible, and the
 * live end-to-end proof (a real generate/boot/edit/regenerate/reboot cycle) already lives in
 * {@code NPDevSamples/probes/path-a-navigation-reprojection} / {@code check-navigation-reprojection.py}.
 * These tests exist to cover the reconcile machinery's own lines (JaCoCo had ZERO coverage on this
 * class before W1.2 -- no test file existed at all) and to pin the exact row-level outcomes the
 * probe only checks in aggregate.
 */
class WorkspaceMenuSeederTest {

    private static final String TENANT = "dev";
    private static final String MENU_TABLE = "workspace_v1_menus";

    @Test
    void reconcileInsertsAllDeclaredRowsIntoAnEmptyTable() throws Exception {
        DataSource dataSource = newDatabase();
        WorkspaceMenuSeeder seeder = newSeeder(dataSource, seedRowsV1(), "reconcile");

        seeder.run(null);

        assertEquals(3, countRows(dataSource, "1=1"));
        Map<String, String> ops = row(dataSource, "GROUP:Ops");
        assertEquals("Operations", ops.get("label"));
        assertEquals("generated", ops.get("seed_origin"));
        assertTrue(ops.get("seed_fingerprint") != null && !ops.get("seed_fingerprint").isBlank());

        Map<String, String> task = row(dataSource, "BUSINESS:Task");
        assertEquals("Tasks", task.get("label"));
        assertEquals(ops.get("id"), parentMenuIdOf(dataSource, "BUSINESS:Task"));
    }

    @Test
    void reconcileUpdatesPreservesOverrideRemovesInsertsAndLeavesAnUnrelatedRowAlone() throws Exception {
        DataSource dataSource = newDatabase();
        newSeeder(dataSource, seedRowsV1(), "reconcile").run(null);

        // Simulate a generic-CRUD edit on the row the v2 seed is about to change too.
        updateLabelBySeedKey(dataSource, "BUSINESS:Task", "Tasks (user edited)");

        newSeeder(dataSource, seedRowsV2(), "reconcile").run(null);

        Map<String, String> ops = row(dataSource, "GROUP:Ops");
        assertEquals("Operations", ops.get("label"));
        assertEquals("generated", ops.get("seed_origin"));

        Map<String, String> task = row(dataSource, "BUSINESS:Task");
        assertEquals("Tasks (renamed by model)", task.get("label"));
        assertEquals("generated", task.get("seed_origin"));

        assertEquals(0, countRows(dataSource, "seed_key = 'PAGE:legacy.html'"));

        Map<String, String> added = row(dataSource, "PAGE:new.html");
        assertEquals("New Page", added.get("label"));
        assertEquals("generated", added.get("seed_origin"));

        List<Map<String, String>> overrides = rowsWhere(dataSource, "override_of = 'BUSINESS:Task'");
        assertEquals(1, overrides.size());
        Map<String, String> override = overrides.get(0);
        assertTrue(override.get("label").contains("Tasks (user edited)"));
        assertEquals("user", override.get("seed_origin"));
        assertNull(override.get("seed_key"));
        assertFalse(Boolean.parseBoolean(override.get("visible")));

        // Started at 3 (Ops, Task, legacy.html); legacy.html removed, new.html and the override clone
        // added, Task and Ops stayed the same row each: 3 - 1 + 1 + 1 = 4.
        assertEquals(4, countRows(dataSource, "1=1"));
    }

    @Test
    void reconcileNeverTouchesAUserCreatedRow() throws Exception {
        DataSource dataSource = newDatabase();
        insertRawRow(dataSource, "User's own link", "UserPage", "PAGE", null, "user", null);

        newSeeder(dataSource, seedRowsV1(), "reconcile").run(null);

        assertEquals(4, countRows(dataSource, "1=1")); // the 3 declared rows + the untouched user row
        assertEquals(1, countRows(dataSource, "target = 'UserPage' AND seed_origin = 'user'"));
    }

    @Test
    void reconcileIsANoOpOnASecondBootWithAnUnchangedSeed() throws Exception {
        DataSource dataSource = newDatabase();
        newSeeder(dataSource, seedRowsV1(), "reconcile").run(null);
        String firstFingerprint = row(dataSource, "BUSINESS:Task").get("seed_fingerprint");

        newSeeder(dataSource, seedRowsV1(), "reconcile").run(null);

        assertEquals(3, countRows(dataSource, "1=1"));
        assertEquals(firstFingerprint, row(dataSource, "BUSINESS:Task").get("seed_fingerprint"));
    }

    @Test
    void insertIfEmptyModeStillStampsProvenanceAndNeverReRunsOnANonEmptyTable() throws Exception {
        DataSource dataSource = newDatabase();
        newSeeder(dataSource, seedRowsV1(), "insert-if-empty").run(null);
        assertEquals(3, countRows(dataSource, "1=1"));
        assertEquals("generated", row(dataSource, "BUSINESS:Task").get("seed_origin"));

        // A model change is declared, but insert-if-empty must ignore it -- this is the OLD, still
        // opt-in-available default behavior W1.2 replaced, kept working for an app that depends on it.
        newSeeder(dataSource, seedRowsV2(), "insert-if-empty").run(null);
        assertEquals(3, countRows(dataSource, "1=1"));
        assertEquals("Tasks", row(dataSource, "BUSINESS:Task").get("label"));
    }

    private static DataSource newDatabase() throws SQLException {
        String url = "jdbc:h2:mem:workspace-menu-seeder-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + MENU_TABLE + " ("
                    + "id UUID NOT NULL, label VARCHAR(120), target VARCHAR(400), kind VARCHAR(20), "
                    + "parent_menu_id VARCHAR(64), required_role VARCHAR(80), ordinal INT, visible BOOLEAN, "
                    + "tenant_id VARCHAR(120) NOT NULL, seed_key VARCHAR(300), seed_origin VARCHAR(20), "
                    + "seed_fingerprint VARCHAR(64), override_of VARCHAR(300), PRIMARY KEY (id))");
        }
        return dataSource;
    }

    private static WorkspaceMenuSeeder newSeeder(DataSource dataSource, String pagesSeedJson, String mode) {
        return new WorkspaceMenuSeeder(dataSource, resourceLoaderServing(pagesSeedJson), new ObjectMapper(),
                new ModelHolder(model()), TENANT, mode);
    }

    private static String seedRowsV1() {
        return "["
                + "{\"key\":\"GROUP:Ops\",\"parentKey\":null,\"label\":\"Operations\",\"target\":\"\",\"kind\":\"GROUP\",\"ordinal\":10,\"requiredRole\":null,\"visible\":true},"
                + "{\"key\":\"BUSINESS:Task\",\"parentKey\":\"GROUP:Ops\",\"label\":\"Tasks\",\"target\":\"Task\",\"kind\":\"BUSINESS\",\"ordinal\":20,\"requiredRole\":null,\"visible\":true},"
                + "{\"key\":\"PAGE:legacy.html\",\"parentKey\":null,\"label\":\"Legacy Page\",\"target\":\"legacy.html\",\"kind\":\"PAGE\",\"ordinal\":30,\"requiredRole\":null,\"visible\":true}"
                + "]";
    }

    private static String seedRowsV2() {
        return "["
                + "{\"key\":\"GROUP:Ops\",\"parentKey\":null,\"label\":\"Operations\",\"target\":\"\",\"kind\":\"GROUP\",\"ordinal\":10,\"requiredRole\":null,\"visible\":true},"
                + "{\"key\":\"BUSINESS:Task\",\"parentKey\":\"GROUP:Ops\",\"label\":\"Tasks (renamed by model)\",\"target\":\"Task\",\"kind\":\"BUSINESS\",\"ordinal\":20,\"requiredRole\":null,\"visible\":true},"
                + "{\"key\":\"PAGE:new.html\",\"parentKey\":null,\"label\":\"New Page\",\"target\":\"new.html\",\"kind\":\"PAGE\",\"ordinal\":40,\"requiredRole\":null,\"visible\":true}"
                + "]";
    }

    private static ResourceLoader resourceLoaderServing(String pagesSeedJson) {
        return new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                if (location.endsWith("workspace-menu-seed.json")) {
                    return new ByteArrayResource("[]".getBytes(StandardCharsets.UTF_8));
                }
                if (location.endsWith("workspace-menu-pages-seed.json")) {
                    return new ByteArrayResource(pagesSeedJson.getBytes(StandardCharsets.UTF_8));
                }
                return super.getResource(location);
            }
        };
    }

    private static CompiledModel model() {
        CompiledConcept menu = new CompiledConcept("workspace::Menu", "Menu", MENU_TABLE,
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false)));
        return new CompiledModel("workspace-menu-seeder-test", "1.0.0", "1.0.0", Map.of(menu.getName(), menu));
    }

    private static void insertRawRow(DataSource dataSource, String label, String target, String kind,
                                      String seedKey, String seedOrigin, String overrideOf) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO " + MENU_TABLE + " (id, label, target, kind, ordinal, visible, tenant_id, "
                             + "seed_key, seed_origin, override_of) VALUES (?, ?, ?, ?, 0, TRUE, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, label);
            ps.setString(3, target);
            ps.setString(4, kind);
            ps.setString(5, TENANT);
            ps.setString(6, seedKey);
            ps.setString(7, seedOrigin);
            ps.setString(8, overrideOf);
            ps.executeUpdate();
        }
    }

    private static void updateLabelBySeedKey(DataSource dataSource, String seedKey, String newLabel) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE " + MENU_TABLE + " SET label = ? WHERE seed_key = ?")) {
            ps.setString(1, newLabel);
            ps.setString(2, seedKey);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static int countRows(DataSource dataSource, String whereClause) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + MENU_TABLE + " WHERE " + whereClause)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Map<String, String> row(DataSource dataSource, String seedKey) throws SQLException {
        List<Map<String, String>> rows = rowsWhere(dataSource, "seed_key = '" + seedKey + "'");
        assertEquals(1, rows.size(), "expected exactly one row for seedKey=" + seedKey);
        return rows.get(0);
    }

    private static String parentMenuIdOf(DataSource dataSource, String seedKey) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT parent_menu_id FROM " + MENU_TABLE + " WHERE seed_key = ?")) {
            ps.setString(1, seedKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static List<Map<String, String>> rowsWhere(DataSource dataSource, String whereClause) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT id, label, target, kind, seed_key, seed_origin, seed_fingerprint, override_of, visible "
                             + "FROM " + MENU_TABLE + " WHERE " + whereClause)) {
            List<Map<String, String>> out = new java.util.ArrayList<>();
            while (rs.next()) {
                // LinkedHashMap, not Map.of/Map.entry -- several columns (seed_key, override_of, ...)
                // are genuinely NULL for some rows, and Map.entry rejects a null value outright.
                Map<String, String> columns = new java.util.LinkedHashMap<>();
                Object id = rs.getObject("id");
                columns.put("id", id == null ? null : id.toString());
                columns.put("label", rs.getString("label"));
                columns.put("target", rs.getString("target"));
                columns.put("kind", rs.getString("kind"));
                columns.put("seed_key", rs.getString("seed_key"));
                columns.put("seed_origin", rs.getString("seed_origin"));
                columns.put("seed_fingerprint", rs.getString("seed_fingerprint"));
                columns.put("override_of", rs.getString("override_of"));
                columns.put("visible", String.valueOf(rs.getBoolean("visible")));
                out.add(columns);
            }
            return out;
        }
    }

    /** Same minimal single-URL {@link DataSource} {@code JdbcBusinessConceptStorePredicateV2Test} uses. */
    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
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
