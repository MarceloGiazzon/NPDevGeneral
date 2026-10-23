package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptStoreUniqueViolationException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-295: a create carrying a caller-supplied id that already exists under ANOTHER tenant must be
 * refused with a generic unique violation (409 {@code unique_integrity_failed}) and must never
 * rewrite or re-home that tenant's row. Every engine's upsert is keyed on the id alone, so before
 * the fix the second save overwrote tenant B's row and moved it into tenant A -- on both the
 * unversioned path and the {@code row_version} path ({@code currentRowVersion} is tenant-filtered).
 */
class JdbcBusinessConceptStoreCrossTenantCreateTest {

    private static final String CONCEPT = "Item";

    @Test
    void createWithIdOwnedByAnotherTenantIsRefusedOnTheVersionedPath() throws SQLException {
        assertCrossTenantCreateRefused(true);
    }

    @Test
    void createWithIdOwnedByAnotherTenantIsRefusedOnTheUnversionedPath() throws SQLException {
        assertCrossTenantCreateRefused(false);
    }

    @Test
    void createWithIdOfAnotherTenantsSoftDeletedRowIsStillRefused() throws SQLException {
        DataSource ds = dataSource(true);
        JdbcBusinessConceptStore store = new JdbcBusinessConceptStore(ds, model());
        String id = UUID.randomUUID().toString();
        store.save(new ConceptRecord(CONCEPT, id, "tenant-b", Map.of("name", "Bob")));
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE items SET deleted_at = CURRENT_TIMESTAMP");
        }

        assertThrows(ConceptStoreUniqueViolationException.class,
                () -> store.save(new ConceptRecord(CONCEPT, id, "tenant-a", Map.of("name", "Mallory"))));
        assertEquals("tenant-b", rawTenant(ds, id));
        assertEquals("Bob", rawName(ds, id));
    }

    @Test
    void sameTenantResaveAndFreshIdCreateStillWorkOnBothPaths() throws SQLException {
        for (boolean versioned : new boolean[] {true, false}) {
            DataSource ds = dataSource(versioned);
            JdbcBusinessConceptStore store = new JdbcBusinessConceptStore(ds, model());
            String id = UUID.randomUUID().toString();
            store.save(new ConceptRecord(CONCEPT, id, "tenant-b", Map.of("name", "Bob")));
            store.save(new ConceptRecord(CONCEPT, id, "tenant-b", Map.of("name", "Bobby")));
            assertEquals("Bobby", store.findById("tenant-b", CONCEPT, id).orElseThrow().data().get("name"),
                    "same-tenant re-save (update) must still work, versioned=" + versioned);

            String fresh = UUID.randomUUID().toString();
            store.save(new ConceptRecord(CONCEPT, fresh, "tenant-a", Map.of("name", "Alice")));
            assertEquals("Alice", store.findById("tenant-a", CONCEPT, fresh).orElseThrow().data().get("name"),
                    "a fresh id under tenant-a must still insert, versioned=" + versioned);
            assertEquals("tenant-a", rawTenant(ds, fresh));
        }
    }

    private static void assertCrossTenantCreateRefused(boolean versioned) throws SQLException {
        DataSource ds = dataSource(versioned);
        JdbcBusinessConceptStore store = new JdbcBusinessConceptStore(ds, model());
        String id = UUID.randomUUID().toString();
        store.save(new ConceptRecord(CONCEPT, id, "tenant-b", Map.of("name", "Bob")));

        ConceptStoreUniqueViolationException refusal = assertThrows(ConceptStoreUniqueViolationException.class,
                () -> store.save(new ConceptRecord(CONCEPT, id, "tenant-a", Map.of("name", "Mallory"))));
        assertFalse(refusal.getMessage().contains("tenant-b"), "the refusal must not name the other tenant");
        assertFalse(refusal.getMessage().contains("Bob"), "the refusal must not leak the other tenant's data");

        Optional<ConceptRecord> owner = store.findById("tenant-b", CONCEPT, id);
        assertTrue(owner.isPresent(), "tenant-b's row must survive");
        assertEquals("Bob", owner.get().data().get("name"));
        assertTrue(store.findById("tenant-a", CONCEPT, id).isEmpty(), "the row must not be re-homed into tenant-a");
        assertEquals("tenant-b", rawTenant(ds, id));
        assertEquals("Bob", rawName(ds, id));
    }

    private static String rawTenant(DataSource ds, String id) throws SQLException {
        return rawColumn(ds, id, "tenant_id");
    }

    private static String rawName(DataSource ds, String id) throws SQLException {
        return rawColumn(ds, id, "name");
    }

    private static String rawColumn(DataSource ds, String id, String column) throws SQLException {
        try (Connection connection = ds.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + column + " FROM items WHERE id = ?")) {
            statement.setObject(1, UUID.fromString(id));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next(), "row " + id + " must exist");
                String value = resultSet.getString(1);
                assertFalse(resultSet.next());
                return value;
            }
        }
    }

    private static CompiledModel model() {
        CompiledConcept item = new CompiledConcept(
                CONCEPT, CONCEPT, "items",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false)
                ));
        return new CompiledModel("reg295.cross-tenant-create", "1.0.0", "1.0.0", Map.of(item.getName(), item));
    }

    private static DataSource dataSource(boolean versioned) throws SQLException {
        String url = "jdbc:h2:mem:reg295-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE items (id UUID NOT NULL, name VARCHAR(255), "
                    + "tenant_id VARCHAR(120) NOT NULL, "
                    + (versioned ? "row_version BIGINT NOT NULL DEFAULT 0, deleted_at TIMESTAMP, " : "")
                    + "PRIMARY KEY (id))");
        }
        return dataSource;
    }

    /** Copied from {@code JdbcBusinessConceptStorePredicateV2Test} -- a fresh
     *  physical connection per {@code getConnection()} call, kept alive across them by the H2 URL's
     *  own {@code DB_CLOSE_DELAY=-1} rather than by pinning one JDBC {@link Connection}. */
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
