package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for tenant isolation in {@link JdbcBusinessConceptStore}. Pins two things that
 * were both real, separately-discovered bugs: (1) findById/findAll/deleteById must actually filter
 * by tenant_id in SQL, not just accept the parameter; (2) save() must write tenant_id from the
 * {@link ConceptRecord}'s own dedicated component, since the kernel-gateway write path
 * (DefaultConceptGateway.save -> store.save) never puts a "tenantId" entry into record.data() —
 * relying on data() alone (as every other column does) silently writes NULL.
 */
class JdbcBusinessConceptStoreTenantIsolationTest {

    private DataSource dataSource;
    private JdbcBusinessConceptStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id UUID NOT NULL, name VARCHAR(255), tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
        }
        CompiledConcept user = new CompiledConcept(
                "User", "User", "users",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(user.getName(), user));
        store = new JdbcBusinessConceptStore(dataSource, model);
    }

    @Test
    void saveWritesTenantIdFromRecordComponentEvenWhenDataMapOmitsIt() {
        UUID id = UUID.randomUUID();
        // Mirrors the kernel-gateway write path: data() carries only DSL fields, never a "tenantId" key.
        store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Alice")));

        Optional<ConceptRecord> found = store.findById("tenant-a", "User", id.toString());
        assertTrue(found.isPresent());
        assertEquals("Alice", found.get().data().get("name"));
    }

    @Test
    void findByIdDoesNotReturnAnotherTenantsRow() {
        UUID id = UUID.randomUUID();
        store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Alice")));

        assertTrue(store.findById("tenant-a", "User", id.toString()).isPresent());
        assertFalse(store.findById("tenant-b", "User", id.toString()).isPresent());
    }

    @Test
    void findAllOnlyReturnsCallingTenantsRows() {
        store.save(new ConceptRecord("User", UUID.randomUUID().toString(), "tenant-a", Map.of("name", "Alice")));
        store.save(new ConceptRecord("User", UUID.randomUUID().toString(), "tenant-b", Map.of("name", "Bob")));

        List<ConceptRecord> tenantARecords = store.findAll("tenant-a", "User");
        assertEquals(1, tenantARecords.size());
        assertEquals("Alice", tenantARecords.get(0).data().get("name"));

        List<ConceptRecord> tenantBRecords = store.findAll("tenant-b", "User");
        assertEquals(1, tenantBRecords.size());
        assertEquals("Bob", tenantBRecords.get(0).data().get("name"));
    }

    @Test
    void deleteByIdDoesNotDeleteAnotherTenantsRow() {
        UUID id = UUID.randomUUID();
        store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Alice")));

        store.deleteById("tenant-b", "User", id.toString());
        assertTrue(store.findById("tenant-a", "User", id.toString()).isPresent(), "cross-tenant delete must be a no-op");

        store.deleteById("tenant-a", "User", id.toString());
        assertFalse(store.findById("tenant-a", "User", id.toString()).isPresent(), "same-tenant delete must succeed");
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
