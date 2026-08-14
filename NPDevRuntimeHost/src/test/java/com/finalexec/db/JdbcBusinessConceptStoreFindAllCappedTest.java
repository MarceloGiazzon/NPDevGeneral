package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptListSlice;
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
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RUN-1 (R8a): live proof of {@link JdbcBusinessConceptStore#findAllCapped}'s LIMIT-pushdown
 * boundary against a REAL H2 datasource -- {@code dialect.paginated(...)} fetches
 * {@code maxRows + 1} rows in one query so truncation can be told apart from "exactly maxRows rows
 * total" without a separate {@code COUNT(*)}. Correct by inspection before this test existed;
 * flagged in review as untested, since the R8a PR that introduced this method shipped with zero
 * test files.
 */
class JdbcBusinessConceptStoreFindAllCappedTest {

    private DataSource dataSource;
    private JdbcBusinessConceptStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE products (id UUID NOT NULL, name VARCHAR(255), tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
        }
        CompiledConcept product = new CompiledConcept(
                "Product", "Product", "products",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(product.getName(), product));
        store = new JdbcBusinessConceptStore(dataSource, model);
    }

    private void seed(int count) {
        for (int i = 0; i < count; i++) {
            store.save(new ConceptRecord("Product", UUID.randomUUID().toString(), "tenant-a",
                    Map.of("name", "Product " + i)));
        }
    }

    @Test
    void exactlyAtTheCapIsNotReportedAsTruncated() {
        seed(3);

        ConceptListSlice<ConceptRecord> slice = store.findAllCapped("tenant-a", "Product", 3);

        assertEquals(3, slice.records().size());
        assertFalse(slice.truncated(), "exactly maxRows rows total must not be reported as truncated");
    }

    @Test
    void oneRowOverTheCapIsTruncatedAndBoundedToMaxRows() {
        seed(4);

        ConceptListSlice<ConceptRecord> slice = store.findAllCapped("tenant-a", "Product", 3);

        assertEquals(3, slice.records().size(), "the response must never exceed maxRows");
        assertTrue(slice.truncated(), "maxRows + 1 rows total must be reported as truncated");
    }

    @Test
    void underTheCapReturnsEverythingUntruncated() {
        seed(2);

        ConceptListSlice<ConceptRecord> slice = store.findAllCapped("tenant-a", "Product", 3);

        assertEquals(2, slice.records().size());
        assertFalse(slice.truncated());
    }

    @Test
    void anotherTenantsRowsNeverCountTowardThisTenantsCapOrResult() {
        seed(2);
        store.save(new ConceptRecord("Product", UUID.randomUUID().toString(), "tenant-b", Map.of("name", "Other tenant")));

        ConceptListSlice<ConceptRecord> slice = store.findAllCapped("tenant-a", "Product", 3);

        assertEquals(2, slice.records().size());
        assertFalse(slice.truncated());
        assertTrue(slice.records().stream().allMatch(record -> "tenant-a".equals(record.tenantId())));
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
