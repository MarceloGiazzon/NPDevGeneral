package com.finalexec.db;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S4 (roadmap B27, ADR-0011 D1): proves a {@code groupBy} join hop ({@code "warehouse.region"})
 * produces IDENTICAL grouped results across the in-memory adapter (Java-side pre-materialized join,
 * {@link InMemoryConceptStore#aggregate}) and the JDBC/H2 adapter (a real SQL {@code JOIN ... GROUP
 * BY}, {@link JdbcBusinessConceptStore#aggregate}) -- the session DoD's own "in-memory and JDBC
 * agree on the same query" requirement, and {@link #jdbcEmitsARealJoinAndGroupByNotARowScan()}
 * separately proves the SQL text itself (S1 O1's own standard: captured SQL, never row counts).
 *
 * <p>Fixture: {@code Warehouse(id, region)} and {@code ShipmentEvent(id, warehouse -> Warehouse,
 * unitsShipped)}. One event has a NULL warehouse and one points at a warehouse id that doesn't
 * exist for this tenant -- both must be excluded from the aggregate under INNER JOIN semantics,
 * identically on both engines (proves the two "excluded, not null-grouped" edge cases agree, not
 * just the happy path).
 */
class ConceptAggregateGroupByJoinPushDownTest {

    private static final String TENANT = "tenant-a";
    private static final UUID EAST_ID = UUID.randomUUID();
    private static final UUID WEST_ID = UUID.randomUUID();
    private static final UUID DANGLING_WAREHOUSE_ID = UUID.randomUUID();

    static Stream<Arguments> adapters() {
        return Stream.of(
                Arguments.of(Named.of("InMemory adapter", (Supplier<ConceptStore>) ConceptAggregateGroupByJoinPushDownTest::newInMemoryStore)),
                Arguments.of(Named.of("JDBC/H2 adapter", (Supplier<ConceptStore>) ConceptAggregateGroupByJoinPushDownTest::newJdbcStore))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void groupByJoinThroughAReferenceFieldAgreesAcrossEngines(Supplier<ConceptStore> factory) {
        ConceptStore store = seeded(factory.get());

        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(),
                List.of(new ConceptAggregateQuery.GroupByField("warehouse.region", null)),
                List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "unitsShipped")),
                List.of(), List.of(), null);
        ConceptAggregateResult result = store.aggregate(TENANT, "ShipmentEvent", query);

        List<Map<String, Object>> rows = new java.util.ArrayList<>(result.rows());
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("warehouse.region"))));

        assertEquals(2, rows.size(), "the null-warehouse and dangling-warehouse events must be "
                + "excluded (INNER JOIN semantics), leaving exactly the two real regions: " + rows);
        assertEquals("east", rows.get(0).get("warehouse.region"));
        assertEquals(30L, ((Number) rows.get(0).get("total")).longValue(),
                "east = 10 + 20, the dangling/null events excluded");
        assertEquals("west", rows.get(1).get("warehouse.region"));
        assertEquals(5L, ((Number) rows.get(1).get("total")).longValue());
    }

    /** S1 O1's own standard: captured SQL, never row counts. Asserts the JDBC engine's DEBUG log
     *  actually contains a real {@code JOIN} and {@code GROUP BY}, not just that the row values
     *  happen to come out right (which a JVM-side filter could also produce). */
    @Test
    void jdbcEmitsARealJoinAndGroupByNotARowScan() {
        ch.qos.logback.classic.Logger storeLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JdbcBusinessConceptStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        storeLogger.addAppender(appender);
        ch.qos.logback.classic.Level previousLevel = storeLogger.getLevel();
        storeLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            ConceptStore store = seeded(newJdbcStore());
            ConceptAggregateQuery query = new ConceptAggregateQuery(
                    List.of(),
                    List.of(new ConceptAggregateQuery.GroupByField("warehouse.region", null)),
                    List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "unitsShipped")),
                    List.of(), List.of(), null);
            store.aggregate(TENANT, "ShipmentEvent", query);
        } finally {
            storeLogger.setLevel(previousLevel);
            storeLogger.detachAppender(appender);
        }

        String capturedSql = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("npdev.aggregate.sql"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no npdev.aggregate.sql DEBUG log line was captured"));

        assertTrue(capturedSql.contains(" JOIN warehouses AS npdev_join0"), "captured SQL: " + capturedSql);
        assertTrue(capturedSql.contains(" ON npdev_base.warehouse = npdev_join0.id"), "captured SQL: " + capturedSql);
        assertTrue(capturedSql.contains(" GROUP BY npdev_join0.region"), "captured SQL: " + capturedSql);
    }

    private static ConceptStore seeded(ConceptStore store) {
        store.save(warehouse(EAST_ID, "east"));
        store.save(warehouse(WEST_ID, "west"));
        store.save(shipmentEvent(EAST_ID, 10));
        store.save(shipmentEvent(EAST_ID, 20));
        store.save(shipmentEvent(WEST_ID, 5));
        store.save(shipmentEvent(null, 999));                    // null reference -- must be excluded
        store.save(shipmentEvent(DANGLING_WAREHOUSE_ID, 999));   // dangling reference -- must be excluded
        return store;
    }

    private static ConceptRecord warehouse(UUID id, String region) {
        return new ConceptRecord("Warehouse", id.toString(), TENANT, Map.of("region", region));
    }

    // ---- S8 W1.1 (roadmap deferred item #1): the SAME cross-engine parity proof, one hop longer ----

    private static final UUID BRAZIL_ID = UUID.randomUUID();
    private static final UUID USA_ID = UUID.randomUUID();
    private static final UUID DANGLING_COUNTRY_ID = UUID.randomUUID();

    /**
     * S8 W1.1: {@code Warehouse} now ALSO has a {@code country -> Country} reference, giving a real
     * two-hop chain {@code "warehouse.country.name"} (ShipmentEvent -&gt; Warehouse -&gt; Country).
     * Mirrors the single-hop test's own edge cases one hop further out: one warehouse has a NULL
     * country and one points at a country id that doesn't exist for this tenant -- both must be
     * excluded under INNER JOIN semantics, identically on both engines.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("adaptersTwoHop")
    void groupByTwoHopJoinAgreesAcrossEngines(Supplier<ConceptStore> factory) {
        ConceptStore store = seededTwoHop(factory.get());

        ConceptAggregateQuery query = new ConceptAggregateQuery(
                List.of(),
                List.of(new ConceptAggregateQuery.GroupByField("warehouse.country.name", null)),
                List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "unitsShipped")),
                List.of(), List.of(), null);
        ConceptAggregateResult result = store.aggregate(TENANT, "ShipmentEvent", query);

        List<Map<String, Object>> rows = new java.util.ArrayList<>(result.rows());
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("warehouse.country.name"))));

        assertEquals(2, rows.size(), "the null-country and dangling-country warehouses' events must be "
                + "excluded (INNER JOIN semantics, at the SECOND hop), leaving exactly the two real "
                + "countries: " + rows);
        assertEquals("brazil", rows.get(0).get("warehouse.country.name"));
        assertEquals(30L, ((Number) rows.get(0).get("total")).longValue(),
                "brazil = 10 + 20, the dangling/null-country events excluded");
        assertEquals("usa", rows.get(1).get("warehouse.country.name"));
        assertEquals(5L, ((Number) rows.get(1).get("total")).longValue());
    }

    /** S1 O1's own standard, one hop further: proves the JDBC engine emits TWO chained real
     *  {@code JOIN} clauses for a two-hop path, not two independent joins nor a single flattened one. */
    @Test
    void jdbcEmitsChainedJoinsForATwoHopPath() {
        ch.qos.logback.classic.Logger storeLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JdbcBusinessConceptStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        storeLogger.addAppender(appender);
        ch.qos.logback.classic.Level previousLevel = storeLogger.getLevel();
        storeLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            ConceptStore store = seededTwoHop(newJdbcStoreTwoHop());
            ConceptAggregateQuery query = new ConceptAggregateQuery(
                    List.of(),
                    List.of(new ConceptAggregateQuery.GroupByField("warehouse.country.name", null)),
                    List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "unitsShipped")),
                    List.of(), List.of(), null);
            store.aggregate(TENANT, "ShipmentEvent", query);
        } finally {
            storeLogger.setLevel(previousLevel);
            storeLogger.detachAppender(appender);
        }

        String capturedSql = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("npdev.aggregate.sql"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no npdev.aggregate.sql DEBUG log line was captured"));

        assertTrue(capturedSql.contains(" JOIN warehouses AS npdev_join0"), "captured SQL: " + capturedSql);
        assertTrue(capturedSql.contains(" ON npdev_base.warehouse = npdev_join0.id"), "captured SQL: " + capturedSql);
        assertTrue(capturedSql.contains(" JOIN countries AS npdev_join1"), "captured SQL: " + capturedSql);
        assertTrue(capturedSql.contains(" ON npdev_join0.country = npdev_join1.id"), "captured SQL: " + capturedSql);
        assertTrue(capturedSql.contains(" GROUP BY npdev_join1.name"), "captured SQL: " + capturedSql);
    }

    private static ConceptStore seededTwoHop(ConceptStore store) {
        store.save(country(BRAZIL_ID, "brazil"));
        store.save(country(USA_ID, "usa"));
        store.save(warehouseWithCountry(EAST_ID, BRAZIL_ID));
        store.save(warehouseWithCountry(WEST_ID, USA_ID));
        store.save(shipmentEvent(EAST_ID, 10));
        store.save(shipmentEvent(EAST_ID, 20));
        store.save(shipmentEvent(WEST_ID, 5));
        UUID warehouseWithNullCountry = UUID.randomUUID();
        store.save(warehouseWithCountry(warehouseWithNullCountry, null));
        store.save(shipmentEvent(warehouseWithNullCountry, 999));               // null country -- excluded
        UUID warehouseWithDanglingCountry = UUID.randomUUID();
        store.save(warehouseWithCountry(warehouseWithDanglingCountry, DANGLING_COUNTRY_ID));
        store.save(shipmentEvent(warehouseWithDanglingCountry, 999));           // dangling country -- excluded
        return store;
    }

    private static ConceptRecord country(UUID id, String name) {
        return new ConceptRecord("Country", id.toString(), TENANT, Map.of("name", name));
    }

    private static ConceptRecord warehouseWithCountry(UUID id, UUID countryId) {
        Map<String, Object> data = countryId == null ? Map.of("region", "n/a")
                : Map.of("region", "n/a", "country", countryId.toString());
        return new ConceptRecord("Warehouse", id.toString(), TENANT, data);
    }

    private static CompiledModel twoHopJoinModel() {
        CompiledConcept country = new CompiledConcept(
                "Country", "Country", "countries",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false)
                )
        );
        CompiledConcept warehouse = new CompiledConcept(
                "Warehouse", "Warehouse", "warehouses",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("region", "string", "String", false, true, false),
                        new CompiledField("country", "reference", "String", false, false, false,
                                List.of(), "Country")
                )
        );
        CompiledConcept shipmentEvent = new CompiledConcept(
                "ShipmentEvent", "ShipmentEvent", "shipment_events",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("warehouse", "reference", "String", false, false, false,
                                List.of(), "Warehouse"),
                        new CompiledField("unitsShipped", "int", "Integer", false, true, false)
                )
        );
        return new CompiledModel("s8.w1_1.groupbyjoin.twohop", "1.0.0", "1.0.0",
                Map.of(country.getName(), country, warehouse.getName(), warehouse,
                        shipmentEvent.getName(), shipmentEvent));
    }

    private static ConceptStore newInMemoryStoreTwoHop() {
        return new InMemoryConceptStore(twoHopJoinModel());
    }

    private static ConceptStore newJdbcStoreTwoHop() {
        try {
            String url = "jdbc:h2:mem:s8-w1_1-groupby-join-twohop-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
            DataSource dataSource = new SingleConnectionUrlDataSource(url);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE countries (id UUID NOT NULL, name VARCHAR(255), "
                        + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
                statement.execute("CREATE TABLE warehouses (id UUID NOT NULL, region VARCHAR(255), country UUID, "
                        + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
                statement.execute("CREATE TABLE shipment_events (id UUID NOT NULL, warehouse UUID, "
                        + "units_shipped INT, tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            }
            return new JdbcBusinessConceptStore(dataSource, twoHopJoinModel());
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to build H2-backed JDBC concept store", exception);
        }
    }

    static Stream<Arguments> adaptersTwoHop() {
        return Stream.of(
                Arguments.of(Named.of("InMemory adapter", (Supplier<ConceptStore>) ConceptAggregateGroupByJoinPushDownTest::newInMemoryStoreTwoHop)),
                Arguments.of(Named.of("JDBC/H2 adapter", (Supplier<ConceptStore>) ConceptAggregateGroupByJoinPushDownTest::newJdbcStoreTwoHop))
        );
    }

    private static ConceptRecord shipmentEvent(UUID warehouseId, int unitsShipped) {
        Map<String, Object> data = warehouseId == null
                ? Map.of("unitsShipped", unitsShipped)
                : Map.of("warehouse", warehouseId.toString(), "unitsShipped", unitsShipped);
        return new ConceptRecord("ShipmentEvent", UUID.randomUUID().toString(), TENANT, data);
    }

    private static CompiledModel joinModel() {
        CompiledConcept warehouse = new CompiledConcept(
                "Warehouse", "Warehouse", "warehouses",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("region", "string", "String", false, true, false)
                )
        );
        CompiledConcept shipmentEvent = new CompiledConcept(
                "ShipmentEvent", "ShipmentEvent", "shipment_events",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("warehouse", "reference", "String", false, false, false,
                                List.of(), "Warehouse"),
                        new CompiledField("unitsShipped", "int", "Integer", false, true, false)
                )
        );
        return new CompiledModel("s4.groupbyjoin", "1.0.0", "1.0.0",
                Map.of(warehouse.getName(), warehouse, shipmentEvent.getName(), shipmentEvent));
    }

    private static ConceptStore newInMemoryStore() {
        return new InMemoryConceptStore(joinModel());
    }

    private static ConceptStore newJdbcStore() {
        try {
            String url = "jdbc:h2:mem:s4-groupby-join-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
            DataSource dataSource = new SingleConnectionUrlDataSource(url);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE warehouses (id UUID NOT NULL, region VARCHAR(255), "
                        + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
                statement.execute("CREATE TABLE shipment_events (id UUID NOT NULL, warehouse UUID, "
                        + "units_shipped INT, tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            }
            return new JdbcBusinessConceptStore(dataSource, joinModel());
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
