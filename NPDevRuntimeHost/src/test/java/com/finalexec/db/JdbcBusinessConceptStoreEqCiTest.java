package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RUN-28 (2026-08-25 remediation plan W2.2): live proof of {@link ConceptQuery.Operator#EQ_CI}'s SQL
 * rendering against a REAL H2 datasource -- pushed down through {@link JdbcBusinessConceptStore#query},
 * the exact path {@code listBy*} reference finders now use instead of fetching every row and
 * filtering in Java. Mirrors {@code JdbcBusinessConceptStoreExistsUniqueTest}'s semantics-parity
 * shape, because EQ_CI is the SAME comparison rule ({@code ConceptStore#uniqueValuesCollide}'s
 * String-branch), applied through {@code query} instead of {@code existsUnique}.
 */
class JdbcBusinessConceptStoreEqCiTest {

    private static final String TENANT_A = "tenant-a";
    private static final String CONCEPT = "Widget";

    private DataSource dataSource;
    private JdbcBusinessConceptStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID NOT NULL, sku VARCHAR(255), qty INT, "
                    + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
        }
        CompiledConcept widget = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("sku", "string", "String", false, false, true),
                        new CompiledField("qty", "int", "Integer", false, false, false)
                )
        );
        CompiledModel model = new CompiledModel("run28.eqci", "1.0.0", "1.0.0", Map.of(widget.getName(), widget));
        store = new JdbcBusinessConceptStore(dataSource, model);
    }

    private String seed(String tenantId, Map<String, Object> data) {
        String id = UUID.randomUUID().toString();
        store.save(new ConceptRecord(CONCEPT, id, tenantId, data));
        return id;
    }

    private List<String> queryByField(String field, Object value) {
        ConceptQuery query = new ConceptQuery(
                List.of(ConceptQuery.Filter.eqCaseInsensitive(field, value)),
                List.of(ConceptQuery.Sort.asc("sku")), 0, ConceptQuery.MAX_LIMIT);
        ConceptPage page = store.query(TENANT_A, CONCEPT, query);
        return page.items().stream().map(r -> String.valueOf(r.data().get("sku"))).toList();
    }

    @Test
    void exactCaseMatchesThroughSql() {
        seed(TENANT_A, Map.of("sku", "ABC-1"));

        assertEquals(List.of("ABC-1"), queryByField("sku", "ABC-1"));
    }

    @Test
    void differentCaseAndWhitespaceStillMatchThroughSql() {
        seed(TENANT_A, Map.of("sku", "ABC-1"));

        assertEquals(List.of("ABC-1"), queryByField("sku", "  abc-1  "),
                "the same trim/case-insensitive rule uniqueValuesCollide applies must hold through SQL too");
    }

    @Test
    void nonMatchingValueReturnsNoRows() {
        seed(TENANT_A, Map.of("sku", "ABC-1"));

        assertTrue(queryByField("sku", "ABC-2").isEmpty());
    }

    @Test
    void aNumericColumnStillCompilesAndMatchesByTextRepresentation() {
        // EQ_CI's contract (unlike existsUnique's conditional dslType branch) is "always compare the
        // field's textual representation" -- the listBy* caller's value is always a URL path segment
        // (a String), regardless of the target field's declared type.
        seed(TENANT_A, Map.of("sku", "Q-1", "qty", 42));

        ConceptQuery query = new ConceptQuery(
                List.of(ConceptQuery.Filter.eqCaseInsensitive("qty", "42")),
                List.of(), 0, ConceptQuery.MAX_LIMIT);
        ConceptPage page = store.query(TENANT_A, CONCEPT, query);
        assertEquals(1, page.total());
    }

    @Test
    void anotherTenantsMatchingRowIsNeverReturned() {
        seed("tenant-b", Map.of("sku", "ABC-1"));

        assertTrue(queryByField("sku", "ABC-1").isEmpty());
    }

    @Test
    void multipleCaseVariantsAllMatchTheSameFilter() {
        seed(TENANT_A, Map.of("sku", "ABC-1"));
        seed(TENANT_A, Map.of("sku", "abc-1"));
        seed(TENANT_A, Map.of("sku", "AbC-1"));

        assertEquals(3, queryByField("sku", "abc-1").size(),
                "every case variant of the same text collides under EQ_CI, matching uniqueValuesCollide's own rule");
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
