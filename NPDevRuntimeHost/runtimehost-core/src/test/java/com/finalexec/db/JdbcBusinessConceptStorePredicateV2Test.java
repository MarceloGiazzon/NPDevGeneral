package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
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
 * R4.3 (Roadmap Wave 1): proves {@code QueryPredicateGrammar}'s v2 grammar -- OR-groups, {@code in},
 * {@code contains}/{@code startsWith}, {@code is null}/{@code is not null}, and a reference-path
 * left side -- executes as REAL, parameterized SQL through {@link JdbcBusinessConceptStore} against
 * a real H2 database, through the governed store contract ({@link com.npdev.kernel.ports.ConceptStore
 * ConceptStore}#query/#aggregate) the exact same way {@code ConceptQueryPushDownTest} already proves
 * the v1 (AND-only) grammar.
 *
 * <p>This is the "acceptable substitute" R4.3's own done-when names for a live packaged-app REST
 * proof ("a corpus query combining OR-groups, a reference path, and a date comparison returns
 * correct rows on a live H2 app"): an H2-backed {@code JdbcBusinessConceptStore} integration test
 * executing real SQL, seeding rows and asserting the exact row set -- chosen because booting a full
 * generated FinalApp is outside what a single move can drive end-to-end here, and this class's own
 * {@code query}/{@code aggregate} methods are exactly where R4.3's SQL rendering had to land.
 *
 * <p>The model: {@code Customer(id, name, country)} and {@code Order(id, code, customerId->Customer,
 * placedOn)}, {@code customerId} a declared reference field -- so a predicate naming
 * {@code "customerId.country"} exercises the SAME {@code registerJoinChain} join machinery a
 * {@code groupBy} join already used, reused rather than re-derived (R4.3's own instruction).
 */
class JdbcBusinessConceptStorePredicateV2Test {

    private static final String TENANT = "tenant-a";

    @Test
    void orGroupsWithAReferencePathJoinAndADateComparisonReturnExactlyTheMatchingRows() {
        JdbcBusinessConceptStore store = newStore();
        String acme = saveCustomer(store, "Acme Corp", "US");
        String globex = saveCustomer(store, "Globex", "UK");
        String initech = saveCustomer(store, "Initech", "US");

        saveOrder(store, "ORD-1", acme, "2026-01-10");    // US + early -> matches group 1
        saveOrder(store, "ORD-2", globex, "2026-06-15");  // UK + late -> matches group 2 (date only)
        saveOrder(store, "ORD-3", initech, "2026-06-20"); // US + late -> matches group 2 (date only)
        saveOrder(store, "ORD-4", acme, "2026-03-01");    // US + mid -> matches NEITHER group

        // where: (customerId.country == 'US' && placedOn < '2026-02-01') || placedOn >= '2026-06-10'
        List<List<ConceptQuery.Filter>> groups = List.of(
                List.of(
                        new ConceptQuery.Filter("customerId.country", ConceptQuery.Operator.EQ, "US"),
                        new ConceptQuery.Filter("placedOn", ConceptQuery.Operator.LT, "2026-02-01")
                ),
                List.of(
                        new ConceptQuery.Filter("placedOn", ConceptQuery.Operator.GTE, "2026-06-10")
                )
        );
        ConceptQuery query = new ConceptQuery(
                List.of(ConceptQuery.Filter.orGroups(groups)),
                List.of(ConceptQuery.Sort.asc("code")), 0, 50);

        ConceptPage page = store.query(TENANT, "Order", query);
        List<String> codes = page.items().stream().map(r -> String.valueOf(r.data().get("code"))).toList();
        assertEquals(3, page.total(), "expected ORD-1 (US+early), ORD-2 and ORD-3 (late) -- ORD-4 matches neither group");
        assertEquals(List.of("ORD-1", "ORD-2", "ORD-3"), codes);
    }

    @Test
    void inOperatorMatchesExactlyTheDeclaredSet() {
        JdbcBusinessConceptStore store = newStore();
        saveCustomer(store, "Acme Corp", "US");
        saveCustomer(store, "Globex", "UK");
        saveCustomer(store, "Umbrella", "JP");

        ConceptPage page = store.query(TENANT, "Customer", new ConceptQuery(
                List.of(ConceptQuery.Filter.in("country", List.of("US", "JP"))),
                List.of(ConceptQuery.Sort.asc("name")), 0, 50));
        assertEquals(2, page.total());
        assertEquals(List.of("Acme Corp", "Umbrella"),
                page.items().stream().map(r -> String.valueOf(r.data().get("name"))).toList());
    }

    @Test
    void startsWithIsACaseInsensitivePrefixMatch() {
        JdbcBusinessConceptStore store = newStore();
        saveCustomer(store, "Acme Corp", "US");
        saveCustomer(store, "Ackerman LLC", "US");
        saveCustomer(store, "Globex", "UK");

        ConceptPage page = store.query(TENANT, "Customer", new ConceptQuery(
                List.of(ConceptQuery.Filter.startsWith("name", "AC")),
                List.of(ConceptQuery.Sort.asc("name")), 0, 50));
        assertEquals(2, page.total(), "case-insensitive prefix match on 'AC' -- Acme and Ackerman, not Globex");
        assertEquals(List.of("Ackerman LLC", "Acme Corp"),
                page.items().stream().map(r -> String.valueOf(r.data().get("name"))).toList());
    }

    @Test
    void isNullAndIsNotNullDistinguishAMissingReference() {
        JdbcBusinessConceptStore store = newStore();
        String acme = saveCustomer(store, "Acme Corp", "US");
        saveOrder(store, "ORD-1", acme, "2026-01-10");
        saveOrderWithNoCustomer(store, "ORD-2", "2026-01-11");

        ConceptPage withCustomer = store.query(TENANT, "Order", new ConceptQuery(
                List.of(ConceptQuery.Filter.isNotNull("customerId")), List.of(), 0, 50));
        assertEquals(1, withCustomer.total());
        assertEquals("ORD-1", withCustomer.items().get(0).data().get("code"));

        ConceptPage withoutCustomer = store.query(TENANT, "Order", new ConceptQuery(
                List.of(ConceptQuery.Filter.isNull("customerId")), List.of(), 0, 50));
        assertEquals(1, withoutCustomer.total());
        assertEquals("ORD-2", withoutCustomer.items().get(0).data().get("code"));
    }

    @Test
    void aValueContainingAQuoteIsBoundAsAParameterNotSplicedIntoSql() {
        // Injection-safety proof for the R4.3 defect fix: CONTAINS/STARTS_WITH/IN render their
        // pattern/placeholders via SqlDialect and bind every value as a PreparedStatement parameter.
        // If a value were ever concatenated into the SQL text instead, an embedded quote would either
        // break the statement (a SQL syntax error) or -- worse -- a classic "' OR '1'='1" payload
        // would silently widen the match to every row. Neither happens when it's bound.
        JdbcBusinessConceptStore store = newStore();
        saveCustomer(store, "O'Malley & Sons", "US");
        saveCustomer(store, "Other Co", "US");

        ConceptPage literalMatch = store.query(TENANT, "Customer", new ConceptQuery(
                List.of(new ConceptQuery.Filter("name", ConceptQuery.Operator.CONTAINS, "O'Malley")),
                List.of(), 0, 50));
        assertEquals(1, literalMatch.total(), "an embedded quote in the search term must still match literally");
        assertEquals("O'Malley & Sons", literalMatch.items().get(0).data().get("name"));

        ConceptPage injectionShaped = store.query(TENANT, "Customer", new ConceptQuery(
                List.of(new ConceptQuery.Filter("name", ConceptQuery.Operator.CONTAINS, "' OR '1'='1")),
                List.of(), 0, 50));
        assertEquals(0, injectionShaped.total(),
                "a classic injection-shaped search term must match zero rows (literal, bound) -- "
                        + "not the whole table (which is what a spliced string would return)");

        ConceptPage inClauseInjectionShaped = store.query(TENANT, "Customer", new ConceptQuery(
                List.of(ConceptQuery.Filter.in("name", List.of("Other Co') OR ('1'='1"))),
                List.of(), 0, 50));
        assertEquals(0, inClauseInjectionShaped.total(),
                "an IN-list value containing quotes/parens must be bound literally, matching nothing");
    }

    @Test
    void aggregateRendersAnInFilterTogetherWithAReferencePathGroupByThroughOneDedupedJoin() {
        JdbcBusinessConceptStore store = newStore();
        String acme = saveCustomer(store, "Acme Corp", "US");
        String globex = saveCustomer(store, "Globex", "UK");
        String initech = saveCustomer(store, "Initech", "US");
        saveOrder(store, "ORD-1", acme, "2026-01-10");
        saveOrder(store, "ORD-2", acme, "2026-01-11");
        saveOrder(store, "ORD-3", globex, "2026-01-12");
        saveOrder(store, "ORD-4", initech, "2026-01-13");

        ConceptAggregateQuery aggregateQuery = new ConceptAggregateQuery(
                List.of(ConceptQuery.Filter.in("customerId.country", List.of("US"))),
                List.of(new ConceptAggregateQuery.GroupByField("customerId.country", null)),
                List.of(new ConceptAggregateQuery.AggregateFunction("total", "count", null)),
                List.of(), List.of(), null);

        ConceptAggregateResult result = store.aggregate(TENANT, "Order", aggregateQuery);
        assertEquals(1, result.rows().size(), "only the US group survives the IN filter (which is itself a joined field)");
        Map<String, Object> row = result.rows().get(0);
        assertEquals("US", row.get("customerId.country"));
        assertEquals(3L, ((Number) row.get("total")).longValue());
    }

    // --- helpers -----------------------------------------------------------------------------

    private static String saveCustomer(JdbcBusinessConceptStore store, String name, String country) {
        String id = UUID.randomUUID().toString();
        store.save(new ConceptRecord("Customer", id, TENANT, Map.of("name", name, "country", country)));
        return id;
    }

    private static void saveOrder(JdbcBusinessConceptStore store, String code, String customerId, String placedOn) {
        String id = UUID.randomUUID().toString();
        store.save(new ConceptRecord("Order", id, TENANT,
                map("code", code, "customerId", customerId, "placedOn", placedOn)));
    }

    private static void saveOrderWithNoCustomer(JdbcBusinessConceptStore store, String code, String placedOn) {
        String id = UUID.randomUUID().toString();
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("code", code);
        data.put("placedOn", placedOn);
        store.save(new ConceptRecord("Order", id, TENANT, data));
    }

    private static Map<String, Object> map(Object... kv) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    private static CompiledModel model() {
        CompiledConcept customer = new CompiledConcept(
                "Customer", "Customer", "customers",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false),
                        new CompiledField("country", "string", "String", false, true, false)
                ));
        CompiledConcept order = new CompiledConcept(
                "Order", "Order", "orders",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("code", "string", "String", false, true, false),
                        new CompiledField("customerId", "reference", "String", false, false, false,
                                List.of(), "Customer"),
                        new CompiledField("placedOn", "date", "java.time.LocalDate", false, false, false)
                ));
        return new CompiledModel("r4.3.predicate-v2", "1.0.0", "1.0.0",
                Map.of(customer.getName(), customer, order.getName(), order));
    }

    private static JdbcBusinessConceptStore newStore() {
        try {
            String url = "jdbc:h2:mem:r43-predicate-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
            DataSource dataSource = new SingleConnectionUrlDataSource(url);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE customers (id UUID NOT NULL, name VARCHAR(255), "
                        + "country VARCHAR(255), tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
                statement.execute("CREATE TABLE orders (id UUID NOT NULL, code VARCHAR(255), "
                        + "customer_id UUID, placed_on DATE, tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            }
            return new JdbcBusinessConceptStore(dataSource, model());
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to build H2-backed JDBC concept store", exception);
        }
    }

    /** Same minimal single-URL {@link DataSource} {@code ConceptQueryPushDownTest} uses -- a fresh
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
