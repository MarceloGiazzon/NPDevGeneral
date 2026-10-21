package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
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

/**
 * LOAD-4: two cost cuts in {@link JdbcBusinessConceptStore#query} found by profiling a 100k-row app,
 * each of which must leave every observable result exactly as it was.
 *
 * <ul>
 *   <li>{@code EQ_CI} on a reference/uuid column holding a canonical UUID compares natively (so the
 *       column's index serves a {@code /by/customerId/{id}} finder) instead of
 *       {@code LOWER(CAST(col AS TEXT))}, which no index can serve.</li>
 *   <li>The page is read first, and a short page answers {@code total} without a {@code COUNT(*)}.</li>
 * </ul>
 */
class JdbcBusinessConceptStoreQueryCostTest {

    private static final String TENANT = "tenant-a";

    private JdbcBusinessConceptStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID NOT NULL, sku VARCHAR(255), "
                    + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            statement.execute("CREATE TABLE gadgets (id UUID NOT NULL, owner_id UUID, "
                    + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
        }
        CompiledConcept widget = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("sku", "string", "String", false, false, true)
                )
        );
        CompiledConcept gadget = new CompiledConcept(
                "Gadget", "Gadget", "gadgets",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("ownerId", "reference", "java.util.UUID", false, false, false)
                )
        );
        CompiledModel model = new CompiledModel("load4.querycost", "1.0.0", "1.0.0",
                Map.of(widget.getName(), widget, gadget.getName(), gadget));
        store = new JdbcBusinessConceptStore(dataSource, model);
    }

    private void saveGadget(UUID owner) {
        store.save(new ConceptRecord("Gadget", UUID.randomUUID().toString(), TENANT, Map.of("ownerId", owner.toString())));
    }

    private long gadgetsOwnedBy(Object value) {
        ConceptQuery query = new ConceptQuery(
                List.of(ConceptQuery.Filter.eqCaseInsensitive("ownerId", value)), List.of(), 0, ConceptQuery.MAX_LIMIT);
        return store.query(TENANT, "Gadget", query).items().size();
    }

    @Test
    void referenceColumnMatchesACanonicalUuidInAnyCaseWithSurroundingWhitespace() {
        UUID owner = UUID.randomUUID();
        saveGadget(owner);
        saveGadget(UUID.randomUUID());

        assertEquals(1, gadgetsOwnedBy(owner.toString()));
        assertEquals(1, gadgetsOwnedBy("  " + owner.toString().toUpperCase() + "  "));
        assertEquals(1, gadgetsOwnedBy(owner));
    }

    @Test
    void referenceColumnGivenSomethingThatIsNotACanonicalUuidMatchesNothingAndDoesNotThrow() {
        saveGadget(UUID.randomUUID());

        assertEquals(0, gadgetsOwnedBy("not-a-uuid"));
        assertEquals(0, gadgetsOwnedBy("1-1-1-1-1"));   // UUID.fromString accepts this; the text form never matched it
        assertEquals(0, gadgetsOwnedBy(""));
    }

    private int ownerGroups(List<ConceptQuery.Sort> sorts, Integer limit) {
        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(),
                List.of(new ConceptAggregateQuery.GroupByField("ownerId", null)),
                List.of(new ConceptAggregateQuery.AggregateFunction("gadgets", "count", null)),
                List.of(), sorts, limit);
        return store.aggregate(TENANT, "Gadget", query).rows().size();
    }

    @Test
    void aGroupedQueryWithALimitReturnsAtMostThatManyGroupsSortedOrNot() {
        for (int i = 0; i < 5; i++) {
            saveGadget(UUID.randomUUID());
        }

        assertEquals(5, ownerGroups(List.of(), null), "no limit: every group");
        assertEquals(2, ownerGroups(List.of(), 2), "limit, no sort: the driver stops at the limit");
        assertEquals(5, ownerGroups(List.of(), 10), "limit above the group count: every group");
        assertEquals(2, ownerGroups(List.of(ConceptQuery.Sort.asc("ownerId")), 2), "limit with a sort: ranked in Java, then cut");
    }

    private ConceptPage widgetPage(int offset, int limit) {
        return store.query(TENANT, "Widget", new ConceptQuery(
                List.of(), List.of(ConceptQuery.Sort.asc("sku")), offset, limit));
    }

    private void seedWidgets(String... skus) {
        for (String sku : skus) {
            store.save(new ConceptRecord("Widget", UUID.randomUUID().toString(), TENANT, Map.of("sku", sku)));
        }
    }

    @Test
    void totalIsExactWhetherOrNotTheCountQueryIsNeeded() {
        seedWidgets("A", "B", "C");

        assertEquals(3, widgetPage(0, 10).total(), "short first page: total is its size");
        assertEquals(3, widgetPage(0, 2).total(), "full page: still counted");
        assertEquals(3, widgetPage(0, 3).total(), "exactly full page: still counted");
        assertEquals(3, widgetPage(2, 10).total(), "short later page: offset + size");
        assertEquals(3, widgetPage(5, 10).total(), "empty page past the end must not report the offset as the total");
        assertEquals(1, widgetPage(2, 10).items().size());
    }

    @Test
    void anEmptyResultFromTheStartHasTotalZero() {
        assertEquals(0, widgetPage(0, 10).total());
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
