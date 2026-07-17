package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-17: verifies {@link JdbcBusinessConceptStore} actually participates in an ambient Spring
 * transaction rather than auto-committing on its own connection -- the narrow correctness bug the
 * doc's item 3 calls out ("single-request CRUD ... must be genuinely transactional on JDBC").
 * Before this fix, {@code save()} called {@code dataSource.getConnection()} directly, which hands
 * back a brand-new physical connection uncoordinated with whatever {@code @Transactional} Spring
 * method is running -- so a generated service's kernel-gateway write and its separate JPA
 * {@code persistence.save(entity)} call were two independently auto-committing writes, not one
 * atomic unit. This test proves the fix at the mechanism level: a write inside a transaction that
 * later rolls back must not be visible afterward; a write with no ambient transaction (today's
 * unchanged default for every non-Spring caller, e.g. every other hermetic test in this file) must
 * still auto-commit exactly as before.
 */
class JdbcBusinessConceptStoreTransactionalWriteTest {

    private DataSource dataSource;
    private JdbcBusinessConceptStore store;
    private DataSourceTransactionManager transactionManager;

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
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @Test
    void writeInsideARolledBackTransactionIsNotVisibleAfterward() {
        UUID id = UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Alice")));
            status.setRollbackOnly();
        });

        assertFalse(
                store.findById("tenant-a", "User", id.toString()).isPresent(),
                "a write made inside a transaction that rolled back must not have been committed -- "
                        + "proves the store's connection actually joined the ambient transaction "
                        + "instead of auto-committing on its own separate connection"
        );
    }

    @Test
    void writeInsideACommittedTransactionIsVisibleAfterward() {
        UUID id = UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status ->
                store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Bob"))));

        assertTrue(store.findById("tenant-a", "User", id.toString()).isPresent());
    }

    @Test
    void writeWithNoAmbientTransactionStillAutoCommitsAsBefore() {
        UUID id = UUID.randomUUID();

        store.save(new ConceptRecord("User", id.toString(), "tenant-a", Map.of("name", "Carol")));

        assertTrue(
                store.findById("tenant-a", "User", id.toString()).isPresent(),
                "the default, non-Spring-transaction call shape (every other hermetic test, every "
                        + "non-generated caller) must keep auto-committing exactly as before this fix"
        );
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
