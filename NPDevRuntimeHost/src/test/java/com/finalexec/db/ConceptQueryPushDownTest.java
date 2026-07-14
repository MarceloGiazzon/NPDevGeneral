package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.ConceptStore;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-5: proves the {@link ConceptStore#query} push-down contract behaves identically across the
 * in-memory adapter (default {@link com.npdev.kernel.concepts.ConceptQueryEngine} evaluation) and the
 * JDBC adapter (native parameterized SQL with LIMIT/OFFSET). The contract is the point; both families
 * must filter, sort, page, count, and stay tenant-scoped the same way.
 */
class ConceptQueryPushDownTest {

    private static final String CONCEPT = "Widget";
    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    static Stream<Arguments> adapters() {
        return Stream.of(
                Arguments.of(Named.of("InMemory adapter", (Supplier<ConceptStore>) ConceptQueryPushDownTest::newInMemoryStore)),
                Arguments.of(Named.of("JDBC/H2 adapter", (Supplier<ConceptStore>) ConceptQueryPushDownTest::newJdbcStore))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void filtersSortsAndPagesTenantScoped(Supplier<ConceptStore> factory) {
        ConceptStore store = factory.get();
        // 25 widgets for tenant A (qty 1..25), plus noise in tenant B that must never appear.
        for (int i = 1; i <= 25; i++) {
            store.save(widget(TENANT_A, "w-" + String.format("%02d", i), i));
        }
        store.save(widget(TENANT_B, "b-1", 999));

        // Page 1: default 50-limit still tenant-scopes and counts only A's rows.
        ConceptPage all = store.query(TENANT_A, CONCEPT, ConceptQuery.firstPage());
        assertEquals(25, all.total(), "total counts all of tenant A's filtered rows");
        assertEquals(25, all.items().size());
        assertFalse(all.hasMore());
        assertFalse(all.items().toString().contains("b-1"), "tenant B's row must never leak");

        // Filter qty > 20 => 21..25 (5 rows), sorted qty descending.
        ConceptPage gt = store.query(TENANT_A, CONCEPT, new ConceptQuery(
                List.of(new ConceptQuery.Filter("qty", ConceptQuery.Operator.GT, 20)),
                List.of(ConceptQuery.Sort.desc("qty")), 0, 50));
        assertEquals(5, gt.total());
        assertEquals(25, ((Number) gt.items().get(0).data().get("qty")).intValue(), "desc sort puts 25 first");
        assertEquals(21, ((Number) gt.items().get(4).data().get("qty")).intValue());

        // Pagination window: limit 10, offset 10 over the 25 ascending => qty 11..20, more remain.
        ConceptPage page2 = store.query(TENANT_A, CONCEPT, new ConceptQuery(
                List.of(), List.of(ConceptQuery.Sort.asc("qty")), 10, 10));
        assertEquals(25, page2.total());
        assertEquals(10, page2.items().size());
        assertEquals(11, ((Number) page2.items().get(0).data().get("qty")).intValue());
        assertTrue(page2.hasMore(), "10..20 of 25 leaves more");

        ConceptPage lastPage = store.query(TENANT_A, CONCEPT, new ConceptQuery(
                List.of(), List.of(ConceptQuery.Sort.asc("qty")), 20, 10));
        assertEquals(5, lastPage.items().size());
        assertFalse(lastPage.hasMore(), "20..25 is the last page");

        // Equality + inequality filters.
        ConceptPage eq = store.query(TENANT_A, CONCEPT,
                new ConceptQuery(List.of(ConceptQuery.Filter.eq("name", "w-07")), List.of(), 0, 50));
        assertEquals(1, eq.total());
        assertEquals("w-07", eq.items().get(0).data().get("name"));

        ConceptPage neq = store.query(TENANT_A, CONCEPT, new ConceptQuery(
                List.of(new ConceptQuery.Filter("name", ConceptQuery.Operator.NEQ, "w-07")), List.of(), 0, 50));
        assertEquals(24, neq.total());
    }

    @Test
    void jdbcAdapterRejectsUnknownFilterFieldRatherThanConcatenatingIt() {
        // Injection-safety: the JDBC adapter compiles column names from the model whitelist only.
        ConceptStore store = newJdbcStore();
        store.save(widget(TENANT_A, "w-01", 1));
        assertThrows(IllegalArgumentException.class, () -> store.query(TENANT_A, CONCEPT, new ConceptQuery(
                List.of(new ConceptQuery.Filter("qty = 1 OR 1=1 --", ConceptQuery.Operator.EQ, 1)),
                List.of(), 0, 50)));
        assertThrows(IllegalArgumentException.class, () -> store.query(TENANT_A, CONCEPT, new ConceptQuery(
                List.of(), List.of(ConceptQuery.Sort.asc("name; DROP TABLE widgets")), 0, 50)));
    }

    @Test
    void limitIsCappedSoOnePageCannotRequestAnUnboundedScan() {
        assertEquals(ConceptQuery.MAX_LIMIT, new ConceptQuery(List.of(), List.of(), 0, 100_000).limit());
        assertEquals(ConceptQuery.DEFAULT_LIMIT, new ConceptQuery(List.of(), List.of(), 0, 0).limit());
    }

    // --- helpers -----------------------------------------------------------------------------

    private static ConceptRecord widget(String tenant, String name, int qty) {
        return new ConceptRecord(CONCEPT, UUID.randomUUID().toString(), tenant, Map.of("name", name, "qty", qty));
    }

    private static CompiledModel widgetModel() {
        CompiledConcept widget = new CompiledConcept(
                CONCEPT, CONCEPT, "widgets",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false),
                        new CompiledField("qty", "int", "Integer", false, true, false)
                )
        );
        return new CompiledModel("lnch5.query", "1.0.0", "1.0.0", Map.of(widget.getName(), widget));
    }

    private static ConceptStore newInMemoryStore() {
        return new InMemoryConceptStore(widgetModel());
    }

    private static ConceptStore newJdbcStore() {
        try {
            String url = "jdbc:h2:mem:lnch5-query-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
            DataSource dataSource = new SingleConnectionUrlDataSource(url);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE widgets (id UUID NOT NULL, name VARCHAR(255), qty INT, "
                        + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            }
            return new JdbcBusinessConceptStore(dataSource, widgetModel());
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to build H2-backed JDBC concept store", exception);
        }
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
