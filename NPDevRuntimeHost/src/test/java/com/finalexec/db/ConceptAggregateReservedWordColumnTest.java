package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.Test;

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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REG-304: the JDBC aggregate path spliced column names raw, so on H2 a rollup such as
 * {@code max(ShelfSlot.row)} rendered {@code MAX(npdev_base.row)} and failed ("expected identifier"):
 * {@code row} is reserved. Every identifier the aggregate SQL names -- the aggregated column, a direct
 * groupBy column, and a groupBy join's foreign-key and target columns -- now goes through the dialect.
 * Columns here are H2 reserved words on purpose: {@code row}, {@code value}, {@code key}.
 */
class ConceptAggregateReservedWordColumnTest {

    private static final String TENANT = "tenant-a";

    @Test
    void aggregatesOverReservedWordColumnsOnH2() throws SQLException {
        ConceptStore store = newJdbcStore();
        UUID layoutA = UUID.randomUUID();
        UUID layoutB = UUID.randomUUID();
        store.save(new ConceptRecord("ShelfLayout", layoutA.toString(), TENANT, Map.of("value", "A")));
        store.save(new ConceptRecord("ShelfLayout", layoutB.toString(), TENANT, Map.of("value", "B")));
        for (Object[] slot : new Object[][] {{layoutA, 1}, {layoutA, 4}, {layoutB, 7}}) {
            store.save(new ConceptRecord("ShelfSlot", UUID.randomUUID().toString(), TENANT,
                    Map.of("key", slot[0].toString(), "row", slot[1])));
        }

        ConceptAggregateResult overall = store.aggregate(TENANT, "ShelfSlot", new ConceptAggregateQuery(
                List.of(), List.of(),
                List.of(new ConceptAggregateQuery.AggregateFunction("highest", "max", "row"),
                        new ConceptAggregateQuery.AggregateFunction("lowest", "min", "row"),
                        new ConceptAggregateQuery.AggregateFunction("total", "sum", "row")),
                List.of(), List.of(), null));
        assertEquals(7L, ((Number) overall.rows().get(0).get("highest")).longValue());
        assertEquals(1L, ((Number) overall.rows().get(0).get("lowest")).longValue());
        assertEquals(12L, ((Number) overall.rows().get(0).get("total")).longValue());

        ConceptAggregateResult byRow = store.aggregate(TENANT, "ShelfSlot", new ConceptAggregateQuery(
                List.of(), List.of(new ConceptAggregateQuery.GroupByField("row", null)),
                List.of(new ConceptAggregateQuery.AggregateFunction("n", "count", null)),
                List.of(), List.of(), null));
        assertEquals(3, byRow.rows().size(), "a direct groupBy on a reserved-word column: " + byRow.rows());

        ConceptAggregateResult byLayout = store.aggregate(TENANT, "ShelfSlot", new ConceptAggregateQuery(
                List.of(), List.of(new ConceptAggregateQuery.GroupByField("key.value", null)),
                List.of(new ConceptAggregateQuery.AggregateFunction("highest", "max", "row")),
                List.of(), List.of(), null));
        List<Map<String, Object>> rows = new java.util.ArrayList<>(byLayout.rows());
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("key.value"))));
        assertEquals(2, rows.size(), "a groupBy join through a reserved-word reference to a reserved-word column: " + rows);
        assertEquals(4L, ((Number) rows.get(0).get("highest")).longValue());
        assertEquals(7L, ((Number) rows.get(1).get("highest")).longValue());
    }

    private static CompiledModel model() {
        CompiledConcept layout = new CompiledConcept(
                "ShelfLayout", "ShelfLayout", "shelf_layouts",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("value", "string", "String", false, true, false)));
        CompiledConcept slot = new CompiledConcept(
                "ShelfSlot", "ShelfSlot", "shelf_slots",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("key", "reference", "String", false, false, false, List.of(), "ShelfLayout"),
                        new CompiledField("row", "int", "Integer", false, true, false)));
        return new CompiledModel("reg304.reserved", "1.0.0", "1.0.0",
                Map.of(layout.getName(), layout, slot.getName(), slot));
    }

    private static ConceptStore newJdbcStore() throws SQLException {
        String url = "jdbc:h2:mem:reg304-reserved-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE shelf_layouts (id UUID NOT NULL, \"value\" VARCHAR(255), "
                    + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            statement.execute("CREATE TABLE shelf_slots (id UUID NOT NULL, \"key\" UUID, \"row\" INT, "
                    + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
        }
        // pinned, as a booted app pins its engine: SqlDialects.active() in a bare test is not H2
        return new JdbcBusinessConceptStore(dataSource, model(), SqlDialects.forName("h2"));
    }

    /** Minimal {@link DataSource} wrapping {@link DriverManager}; avoids an H2-specific compile-time dependency. */
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
