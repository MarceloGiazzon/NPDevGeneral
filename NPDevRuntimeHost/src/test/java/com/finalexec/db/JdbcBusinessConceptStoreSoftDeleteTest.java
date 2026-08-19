package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptListSlice;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.4 (Roadmap Collection 2026-08-18, RUN-20): live proof of {@link JdbcBusinessConceptStore}'s
 * soft-delete behavior against a REAL H2 datasource -- {@link JdbcBusinessConceptStore#deleteById}
 * flips {@code deleted_at} instead of physically removing the row for a concept declaring {@code
 * softDelete: true}; {@link JdbcBusinessConceptStore#findAll}/{@code findAllCapped}/{@code query}/
 * {@link JdbcBusinessConceptStore#existsUnique} all exclude a deleted row; {@link
 * JdbcBusinessConceptStore#restore} clears the flag; and a unique value freed by a soft delete can
 * be reused. {@link #nonSoftDeleteConceptStillPhysicallyDeletes()} is the backward-compatibility
 * proof the roadmap item's own DoD asked for: a concept that does NOT declare {@code softDelete}
 * behaves exactly as it did before this feature existed.
 *
 * <p>The manual {@code CREATE TABLE} below deliberately carries NO unique constraint on {@code code}
 * (matching {@code JdbcBusinessConceptStoreExistsUniqueTest}'s own precedent) -- this class proves the
 * STORE's own JVM-level mechanics; the DB-level filtered/plain-index DDL choice per engine capability
 * is {@code SchemaRealizationEmitter}'s concern, covered by its own generator-module tests and by the
 * live proof against a real generated app (soft-delete-r54-proof, AppGen/apps, outside this repo).
 */
class JdbcBusinessConceptStoreSoftDeleteTest {

    private static final String TENANT_A = "tenant-a";
    private static final String SOFT_DELETE_CONCEPT = "Supplier";
    private static final String PHYSICAL_DELETE_CONCEPT = "Widget";

    private DataSource dataSource;
    private JdbcBusinessConceptStore softDeleteStore;
    private JdbcBusinessConceptStore physicalDeleteStore;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE suppliers (id UUID NOT NULL, code VARCHAR(255), "
                    + "display_name VARCHAR(255), tenant_id VARCHAR(120) NOT NULL, "
                    + "deleted_at TIMESTAMP WITH TIME ZONE, PRIMARY KEY (id))");
            statement.execute("CREATE TABLE widgets (id UUID NOT NULL, sku VARCHAR(255), "
                    + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
        }

        CompiledConcept supplier = new CompiledConcept(
                "Supplier", "Supplier", "suppliers",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("code", "string", "String", false, false, true),
                        new CompiledField("displayName", "string", "String", false, false, false)
                ),
                List.of(), List.of(), null, null, null, null, List.of(), null, null, null, null,
                /* softDelete */ true
        );
        CompiledConcept widget = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("sku", "string", "String", false, false, true)
                )
        );
        CompiledModel model = new CompiledModel(
                "r5.4.softDelete", "1.0.0", "1.0.0",
                Map.of(supplier.getName(), supplier, widget.getName(), widget));
        softDeleteStore = new JdbcBusinessConceptStore(dataSource, model);
        physicalDeleteStore = softDeleteStore;
    }

    private String seedSupplier(Map<String, Object> data) {
        String id = UUID.randomUUID().toString();
        softDeleteStore.save(new ConceptRecord(SOFT_DELETE_CONCEPT, id, TENANT_A, data));
        return id;
    }

    /** Reads deleted_at directly off the live row -- bypassing the store entirely -- so the test can
     *  tell "physically removed" apart from "present with deleted_at set" without relying on the very
     *  store methods under test to report it (they deliberately never expose deleted_at at all). */
    private Optional<java.sql.Timestamp> rawDeletedAt(String table, String id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT deleted_at FROM " + table + " WHERE id = '" + id + "'")) {
            if (!rs.next()) {
                return Optional.empty();
            }
            java.sql.Timestamp value = rs.getTimestamp(1);
            return Optional.ofNullable(value);
        }
    }

    private int rawRowCount(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void deleteFlipsDeletedAtInsteadOfPhysicallyRemovingTheRow() throws SQLException {
        String id = seedSupplier(Map.of("code", "ACME", "displayName", "Acme Corp"));

        softDeleteStore.deleteById(TENANT_A, SOFT_DELETE_CONCEPT, id);

        assertEquals(1, rawRowCount("suppliers"), "the row must still physically exist");
        assertTrue(rawDeletedAt("suppliers", id).isPresent(), "deleted_at must now be set");
    }

    @Test
    void nonSoftDeleteConceptStillPhysicallyDeletes() throws SQLException {
        // Backward compatibility, the roadmap item's own DoD wording: "a concept without softDelete
        // must behave exactly as today -- prove it."
        String id = UUID.randomUUID().toString();
        physicalDeleteStore.save(new ConceptRecord(PHYSICAL_DELETE_CONCEPT, id, TENANT_A, Map.of("sku", "W-1")));

        physicalDeleteStore.deleteById(TENANT_A, PHYSICAL_DELETE_CONCEPT, id);

        assertEquals(0, rawRowCount("widgets"), "a non-soft-delete concept's row must be physically removed");
    }

    @Test
    void deletedRowIsExcludedFromFindByIdFindAllFindAllCappedAndQuery() {
        String liveId = seedSupplier(Map.of("code", "LIVE-1", "displayName", "Still here"));
        String deletedId = seedSupplier(Map.of("code", "GONE-1", "displayName", "Soon gone"));
        softDeleteStore.deleteById(TENANT_A, SOFT_DELETE_CONCEPT, deletedId);

        assertTrue(softDeleteStore.findById(TENANT_A, SOFT_DELETE_CONCEPT, deletedId).isEmpty(),
                "findById must treat a soft-deleted row as not found");
        assertTrue(softDeleteStore.findById(TENANT_A, SOFT_DELETE_CONCEPT, liveId).isPresent());

        List<ConceptRecord> all = softDeleteStore.findAll(TENANT_A, SOFT_DELETE_CONCEPT);
        assertEquals(1, all.size(), "findAll must exclude the deleted row");
        assertEquals(liveId, all.get(0).id());

        ConceptListSlice<ConceptRecord> capped = softDeleteStore.findAllCapped(TENANT_A, SOFT_DELETE_CONCEPT, 10);
        assertEquals(1, capped.records().size(), "findAllCapped must exclude the deleted row");
        assertFalse(capped.truncated());

        ConceptPage page = softDeleteStore.query(TENANT_A, SOFT_DELETE_CONCEPT, ConceptQuery.firstPage());
        assertEquals(1, page.total(), "the grid/page query must exclude the deleted row from both the page and the total");
    }

    @Test
    void aUniqueValueFreedBySoftDeleteCanBeReusedImmediately() {
        String id = seedSupplier(Map.of("code", "ACME", "displayName", "Acme Corp"));
        assertTrue(softDeleteStore.existsUnique(TENANT_A, SOFT_DELETE_CONCEPT, List.of("code"), List.of("ACME"), null),
                "sanity check: the live row collides before any delete");

        softDeleteStore.deleteById(TENANT_A, SOFT_DELETE_CONCEPT, id);

        assertFalse(softDeleteStore.existsUnique(TENANT_A, SOFT_DELETE_CONCEPT, List.of("code"), List.of("ACME"), null),
                "R5.4: a soft-deleted row's unique value must no longer collide -- it is free to reuse");

        // The reuse itself: a brand-new row claiming the freed value must save without conflict.
        String reusedId = seedSupplier(Map.of("code", "ACME", "displayName", "New Acme (unrelated)"));
        List<ConceptRecord> all = softDeleteStore.findAll(TENANT_A, SOFT_DELETE_CONCEPT);
        assertEquals(1, all.size());
        assertEquals(reusedId, all.get(0).id());
        assertEquals("ACME", all.get(0).data().get("code"));
    }

    @Test
    void uniquenessStillAppliesAmongLiveRows() {
        seedSupplier(Map.of("code", "INITECH", "displayName", "Initech"));

        assertTrue(softDeleteStore.existsUnique(TENANT_A, SOFT_DELETE_CONCEPT, List.of("code"), List.of("INITECH"), null),
                "a value held by a LIVE row must still collide -- soft delete only frees a DELETED row's value");
    }

    @Test
    void restoreClearsDeletedAtAndTheRowReappearsWithItsOriginalData() {
        String id = seedSupplier(Map.of("code", "GLOBEX", "displayName", "Globex Corp"));
        softDeleteStore.deleteById(TENANT_A, SOFT_DELETE_CONCEPT, id);
        assertTrue(softDeleteStore.findAll(TENANT_A, SOFT_DELETE_CONCEPT).isEmpty());

        boolean restored = softDeleteStore.restore(TENANT_A, SOFT_DELETE_CONCEPT, id);

        assertTrue(restored);
        List<ConceptRecord> all = softDeleteStore.findAll(TENANT_A, SOFT_DELETE_CONCEPT);
        assertEquals(1, all.size(), "the restored row must be visible again");
        assertEquals("GLOBEX", all.get(0).data().get("code"), "restore must not touch the row's own data");
        assertEquals("Globex Corp", all.get(0).data().get("displayName"));
    }

    @Test
    void restoringARowThatWasNeverDeletedIsANoOp() {
        String id = seedSupplier(Map.of("code", "STILL-LIVE", "displayName", "Never deleted"));

        boolean restored = softDeleteStore.restore(TENANT_A, SOFT_DELETE_CONCEPT, id);

        assertFalse(restored, "restoring an already-live row must report false, not silently succeed");
        assertEquals(1, softDeleteStore.findAll(TENANT_A, SOFT_DELETE_CONCEPT).size());
    }

    @Test
    void restoringAConceptWithNoSoftDeleteIsANoOp() {
        String id = UUID.randomUUID().toString();
        physicalDeleteStore.save(new ConceptRecord(PHYSICAL_DELETE_CONCEPT, id, TENANT_A, Map.of("sku", "W-2")));

        boolean restored = physicalDeleteStore.restore(TENANT_A, PHYSICAL_DELETE_CONCEPT, id);

        assertFalse(restored, "restore is meaningless for a concept that never soft-deletes");
    }

    @Test
    void deletingAnAlreadyDeletedRowIsAHarmlessNoOp() throws SQLException {
        String id = seedSupplier(Map.of("code", "DOUBLE-DEL", "displayName", "Deleted twice"));
        softDeleteStore.deleteById(TENANT_A, SOFT_DELETE_CONCEPT, id);
        Optional<java.sql.Timestamp> firstStamp = rawDeletedAt("suppliers", id);
        assertTrue(firstStamp.isPresent());

        softDeleteStore.deleteById(TENANT_A, SOFT_DELETE_CONCEPT, id);

        assertEquals(1, rawRowCount("suppliers"), "a second delete must not remove the row");
        assertEquals(firstStamp, rawDeletedAt("suppliers", id),
                "the guarded UPDATE (deleted_at IS NULL) must make a second delete a true no-op, not re-stamp the timestamp");
    }

    @Test
    void deletedAtNeverLeaksIntoConceptRecordData() {
        String id = seedSupplier(Map.of("code", "NO-LEAK", "displayName", "Check data() map"));

        Optional<ConceptRecord> record = softDeleteStore.findById(TENANT_A, SOFT_DELETE_CONCEPT, id);

        assertTrue(record.isPresent());
        assertFalse(record.get().data().containsKey("deletedAt"),
                "deletedAt must never appear in data() -- the generated JPA entity has no matching field, "
                        + "and entityFromRecord's strict Jackson conversion would throw on an unknown property");
        assertFalse(record.get().data().containsKey("deleted_at"));
    }

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
