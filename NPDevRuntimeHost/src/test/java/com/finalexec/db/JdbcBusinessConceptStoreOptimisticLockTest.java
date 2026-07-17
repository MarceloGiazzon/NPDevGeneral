package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptStoreOptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * LNCH-16: {@link JdbcBusinessConceptStore}'s half of "InMemory adapter mirrors with a CAS on the
 * map entry" -- proves the real compare-and-increment SQL path (UPDATE ... WHERE row_version = ?)
 * against a table that has the row_version column, and the pre-existing unconditional-upsert
 * behavior on one that doesn't (backward compatibility with tables generated before this feature).
 */
class JdbcBusinessConceptStoreOptimisticLockTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
    }

    private JdbcBusinessConceptStore versionedStore() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id UUID NOT NULL, name VARCHAR(255), "
                    + "tenant_id VARCHAR(120) NOT NULL, row_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (id))");
        }
        return new JdbcBusinessConceptStore(dataSource, userModel());
    }

    private JdbcBusinessConceptStore unversionedStore() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id UUID NOT NULL, name VARCHAR(255), "
                    + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
        }
        return new JdbcBusinessConceptStore(dataSource, userModel());
    }

    private static CompiledModel userModel() {
        CompiledConcept user = new CompiledConcept(
                "User", "User", "users",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false)
                )
        );
        return new CompiledModel("test", "1.0.0", "1.0.0", Map.of(user.getName(), user));
    }

    @Test
    void createStartsAtRowVersionZero() throws SQLException {
        JdbcBusinessConceptStore store = versionedStore();
        UUID id = UUID.randomUUID();

        store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Alice")));

        assertEquals(0L, store.findById("tenant-a", "User", id.toString()).orElseThrow().rowVersion());
    }

    @Test
    void interleavedUpdatesRejectLoserWithWinnersCurrentState() throws SQLException {
        JdbcBusinessConceptStore store = versionedStore();
        UUID id = UUID.randomUUID();
        store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Alice")));
        long readVersion = store.findById("tenant-a", "User", id.toString()).orElseThrow().rowVersion();

        ConceptRecord winnerWrite = store.save(
                new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Winner"), readVersion));
        assertEquals(readVersion + 1, winnerWrite.rowVersion());

        ConceptStoreOptimisticLockException conflict = assertThrows(
                ConceptStoreOptimisticLockException.class,
                () -> store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Loser"), readVersion))
        );
        assertTrue(conflict.currentRecord().isPresent());
        assertEquals("Winner", conflict.currentRecord().orElseThrow().data().get("name"));
        assertEquals(readVersion + 1, conflict.currentRecord().orElseThrow().rowVersion());

        assertEquals("Winner", store.findById("tenant-a", "User", id.toString()).orElseThrow().data().get("name"));
    }

    @Test
    void conflictAgainstADeletedRowReportsEmptyCurrentRecord() throws SQLException {
        JdbcBusinessConceptStore store = versionedStore();
        UUID id = UUID.randomUUID();
        store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Alice")));
        long readVersion = store.findById("tenant-a", "User", id.toString()).orElseThrow().rowVersion();
        store.deleteById("tenant-a", "User", id.toString());

        ConceptStoreOptimisticLockException conflict = assertThrows(
                ConceptStoreOptimisticLockException.class,
                () -> store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Ghost"), readVersion))
        );
        assertFalse(conflict.currentRecord().isPresent());
    }

    @Test
    void tablesWithoutRowVersionColumnKeepUnconditionalUpsertBehavior() throws SQLException {
        JdbcBusinessConceptStore store = unversionedStore();
        UUID id = UUID.randomUUID();

        store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Alice")));
        Optional<ConceptRecord> afterCreate = store.findById("tenant-a", "User", id.toString());
        assertTrue(afterCreate.isPresent());
        assertEquals(null, afterCreate.orElseThrow().rowVersion());

        // An unconditional write (rowVersion == null on the way in) still succeeds unchanged.
        ConceptRecord updated = store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Bob")));
        assertEquals("Bob", updated.data().get("name"));
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
