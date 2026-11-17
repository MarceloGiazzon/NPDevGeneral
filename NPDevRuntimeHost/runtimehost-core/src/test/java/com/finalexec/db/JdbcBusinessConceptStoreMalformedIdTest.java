package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptStoreOptimisticLockException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every by-id read or write of {@link JdbcBusinessConceptStore} answers a malformed id against a UUID id column
 * with "not found" / "nothing changed" -- never a driver conversion error. Before the shared guard only
 * {@code findById} had it; {@code findByIdForUpdate} (reached by the gateway's update and delete),
 * {@code deleteById} and {@code restore} threw an {@link IllegalStateException} wrapping the driver's error.
 */
class JdbcBusinessConceptStoreMalformedIdTest {

    private static final String CONCEPT = "Item";
    private static final String TENANT = "tenant-a";
    private static final String MALFORMED = "not-a-uuid";

    @Test
    void everyByIdMethodTreatsAMalformedIdAsNotFound() throws SQLException {
        JdbcBusinessConceptStore store = store();
        assertTrue(store.findById(TENANT, CONCEPT, MALFORMED).isEmpty(), "findById");
        assertTrue(store.findByIdForUpdate(TENANT, CONCEPT, MALFORMED).isEmpty(), "findByIdForUpdate");
        assertDoesNotThrow(() -> store.deleteById(TENANT, CONCEPT, MALFORMED), "deleteById");
        assertFalse(store.restore(TENANT, CONCEPT, MALFORMED), "restore");
    }

    @Test
    void aCompareAndSwapDeleteOfAMalformedIdReportsTheLostRaceNotADriverError() throws SQLException {
        JdbcBusinessConceptStore store = store();
        assertThrows(ConceptStoreOptimisticLockException.class,
                () -> store.deleteById(TENANT, CONCEPT, MALFORMED, 1L));
    }

    @Test
    void aWellFormedIdStillReadsLocksAndDeletes() throws SQLException {
        JdbcBusinessConceptStore store = store();
        String id = UUID.randomUUID().toString();
        store.save(new ConceptRecord(CONCEPT, id, TENANT, Map.of("name", "Alice")));
        assertEquals("Alice", store.findByIdForUpdate(TENANT, CONCEPT, id).orElseThrow().data().get("name"));
        store.deleteById(TENANT, CONCEPT, id);
        assertTrue(store.findById(TENANT, CONCEPT, id).isEmpty());
        assertTrue(store.findByIdForUpdate(TENANT, CONCEPT, UUID.randomUUID().toString()).isEmpty());
    }

    private static JdbcBusinessConceptStore store() throws SQLException {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:malformed-id-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = ds.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE items (id UUID NOT NULL, name VARCHAR(255), "
                    + "tenant_id VARCHAR(120) NOT NULL, row_version BIGINT NOT NULL DEFAULT 0, deleted_at TIMESTAMP, "
                    + "PRIMARY KEY (id))");
        }
        CompiledConcept item = new CompiledConcept(
                CONCEPT, CONCEPT, "items",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false)));
        CompiledModel model = new CompiledModel("malformed-id", "1.0.0", "1.0.0", Map.of(item.getName(), item));
        return new JdbcBusinessConceptStore(ds, model);
    }
}
