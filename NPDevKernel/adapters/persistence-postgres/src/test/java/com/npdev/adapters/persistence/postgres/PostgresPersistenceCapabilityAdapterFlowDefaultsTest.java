package com.npdev.adapters.persistence.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.concepts.LiveConceptGatewaySemanticPolicy;
import com.npdev.kernel.ports.PersistenceCapabilityContract;
import com.npdev.kernel.ports.SequenceAllocator;
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENG-6: a flow-compiled createConcept/updateConcept step dispatches straight to
 * {@link PostgresPersistenceCapabilityAdapter#save(Object, Object)}. With the live gateway policy
 * wired, a CREATE gets the same defaulting pass as the CRUD API (literal defaults, sequences,
 * lifecycle initial state) and an UPDATE never re-defaults an untouched field.
 */
class PostgresPersistenceCapabilityAdapterFlowDefaultsTest {

    private static final String MODEL = "{"
            + "\"dslVersion\":\"1.0.0\",\"namespace\":\"flowdefaults.test\",\"version\":\"1.0\","
            + "\"sequences\":[{\"name\":\"ticketCode\",\"format\":\"TK-{seq:4}\",\"scope\":\"tenant\"}],"
            + "\"concepts\":["
            + "{\"name\":\"Order\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"string\",\"id\":true,\"required\":true},"
            + "{\"name\":\"status\",\"type\":\"string\"},"
            + "{\"name\":\"priority\",\"type\":\"string\",\"default\":\"normal\"},"
            + "{\"name\":\"note\",\"type\":\"string\"}],"
            + "\"lifecycle\":{\"statusField\":\"status\","
            + "\"states\":[{\"value\":\"NEW\",\"label\":\"New\",\"initial\":true},"
            + "{\"value\":\"DONE\",\"label\":\"Done\",\"terminal\":true}],"
            + "\"transitions\":[{\"from\":\"NEW\",\"to\":\"DONE\",\"event\":\"finish\",\"actionLabel\":\"Finish\"}]}},"
            + "{\"name\":\"Ticket\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"string\",\"id\":true,\"required\":true},"
            + "{\"name\":\"code\",\"type\":\"string\",\"defaultExpression\":\"nextNumber('ticketCode')\"},"
            + "{\"name\":\"title\",\"type\":\"string\"}]},"
            + "{\"name\":\"Line\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"string\",\"id\":true,\"required\":true},"
            + "{\"name\":\"sku\",\"type\":\"string\",\"required\":true},"
            + "{\"name\":\"quantity\",\"type\":\"integer\"},"
            + "{\"name\":\"version\",\"type\":\"integer\"}]}"
            + "]}";

    private SqlDialect previousDialect;
    private DataSource dataSource;
    private PostgresPersistenceCapabilityAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        previousDialect = SqlDialects.active();
        SqlDialects.setActive(H2Dialect.INSTANCE);
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:flowdefaults" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id VARCHAR(64) PRIMARY KEY, status VARCHAR(32),"
                    + " priority VARCHAR(32), note VARCHAR(200))");
            statement.execute("CREATE TABLE tickets (id VARCHAR(64) PRIMARY KEY, code VARCHAR(32),"
                    + " title VARCHAR(200))");
            statement.execute("CREATE TABLE lines (id VARCHAR(64) PRIMARY KEY, sku VARCHAR(64),"
                    + " quantity INT, version BIGINT)");
        }
        CompiledModel model = new ModelCompiler().compile(
                new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        adapter = new PostgresPersistenceCapabilityAdapter(dataSource, model, H2Dialect.INSTANCE);
        adapter.setSemanticPolicy(new LiveConceptGatewaySemanticPolicy(() -> model, SequenceAllocator.inMemory()));
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
        SqlDialects.setActive(previousDialect);
    }

    @Test
    void createWithoutStatusOrPriorityGetsLifecycleInitialStateAndLiteralDefault() throws SQLException {
        Map<?, ?> saved = (Map<?, ?>) adapter.save("Order", Map.of("note", "first"));

        assertEquals("NEW", saved.get("status"));
        assertEquals("normal", saved.get("priority"));
        assertEquals("NEW", column("orders", "status", saved.get("id")));
        assertEquals("normal", column("orders", "priority", saved.get("id")));
    }

    @Test
    void updateOfAnExistingRowDoesNotReResetUntouchedFieldsToTheirDefaults() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO orders (id, status, priority, note) VALUES ('o-1', 'NEW', 'high', 'old')");
        }

        // A partial updateConcept payload: priority is not in it, so it must keep its stored value.
        adapter.save("Order", Map.of("id", "o-1", "status", "NEW", "note", "x"));

        assertEquals("x", column("orders", "note", "o-1"));
        assertEquals("high", column("orders", "priority", "o-1"),
                "an update must not re-apply the literal default over an untouched field");
    }

    @Test
    void createWithAnExplicitIdAndNoStoredRowStillGetsDefaults() throws SQLException {
        adapter.save("Order", Map.of("id", "o-explicit", "note", "n"));

        assertEquals("NEW", column("orders", "status", "o-explicit"));
        assertEquals("normal", column("orders", "priority", "o-explicit"));
    }

    @Test
    void defaultExpressionNextNumberSequenceYieldsConsecutiveCodes() throws SQLException {
        Map<?, ?> first = (Map<?, ?>) adapter.save("Ticket", Map.of("title", "a"));
        Map<?, ?> second = (Map<?, ?>) adapter.save("Ticket", Map.of("title", "b"));

        assertEquals("TK-0001", first.get("code"));
        assertEquals("TK-0002", second.get("code"));
        assertNotEquals(first.get("code"), second.get("code"));
        assertEquals("TK-0002", column("tickets", "code", second.get("id")));
        assertTrue(String.valueOf(first.get("id")).length() > 0);
    }

    @Test
    void withoutASemanticPolicyTheLegacyLiteralDefaultStillAppliesOnCreate() throws SQLException {
        adapter.setSemanticPolicy(null);

        Map<?, ?> saved = (Map<?, ?>) adapter.save("Order", Map.of("note", "legacy"));

        assertEquals("normal", saved.get("priority"));
    }

    private static Map<String, Object> updateMarked(Object... keyValues) {
        Map<String, Object> entity = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            entity.put((String) keyValues[i], keyValues[i + 1]);
        }
        entity.put(PersistenceCapabilityContract.WRITE_MODE_KEY, PersistenceCapabilityContract.WRITE_MODE_UPDATE);
        return entity;
    }

    @Test
    void updateModeSaveOfAnIdWithNoRowIsRefusedAndCreatesNothing() throws SQLException {
        // REG-264: the REST PUT answers 404; a flow updateConcept must not upsert the id into existence.
        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> adapter.save("Line", updateMarked("id", "ghost", "quantity", 5)));

        assertTrue(refused.getMessage().contains("PERSISTENCE_RECORD_NOT_FOUND"), refused.getMessage());
        assertEquals(0L, count("lines"));
    }

    @Test
    void updateModeSaveMergesIntoTheStoredRowAndMovesItToTheNextVersion() throws SQLException {
        // REG-263: the untouched columns survive and the version advances, as the REST PUT does.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO lines (id, sku, quantity, version) VALUES ('l-1', 'SKU-A', 2, 0)");
        }

        Map<?, ?> saved = (Map<?, ?>) adapter.save("Line", updateMarked("id", "l-1", "quantity", 7));

        assertEquals("SKU-A", column("lines", "sku", "l-1"));
        assertEquals(7, ((Number) column("lines", "quantity", "l-1")).intValue());
        assertEquals(1L, ((Number) column("lines", "version", "l-1")).longValue());
        assertTrue(!saved.containsKey(PersistenceCapabilityContract.WRITE_MODE_KEY), "the marker is never persisted or echoed");
    }

    @Test
    void updateModeSaveKeepsAVersionTheCallerNamedItself() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO lines (id, sku, quantity, version) VALUES ('l-2', 'SKU-B', 2, 4)");
        }

        adapter.save("Line", updateMarked("id", "l-2", "quantity", 3, "version", 9L));

        assertEquals(9L, ((Number) column("lines", "version", "l-2")).longValue());
    }

    @Test
    void anOrdinarySaveOfAnExistingRowDoesNotTouchItsVersion() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO lines (id, sku, quantity, version) VALUES ('l-3', 'SKU-C', 2, 4)");
        }

        adapter.save("Line", Map.of("id", "l-3", "quantity", 8));

        assertEquals(4L, ((Number) column("lines", "version", "l-3")).longValue());
        assertEquals("SKU-C", column("lines", "sku", "l-3"));
    }

    private long count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Object column(String table, String column, Object id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT " + column + " FROM " + table + " WHERE id = '" + id + "'")) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }
}
