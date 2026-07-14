package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-5 volume gate: with 100k rows in one tenant, the JDBC push-down must return a bounded page
 * (LIMIT/OFFSET) and a COUNT(*) total without materializing the whole table -- the failure mode the
 * old fetch-all-then-filter path would hit. If the store streamed all 100k rows into the JVM per
 * page, this would be slow/OOM; asserting a 50-row page over a 100k table is the proof it does not.
 */
class ConceptQueryVolumeTest {

    private static final int ROWS = 100_000;

    @Test
    void pagingAndFilteringOneHundredThousandRowsReturnsABoundedPage() throws SQLException {
        String url = "jdbc:h2:mem:lnch5-volume-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID NOT NULL, name VARCHAR(255), qty INT, "
                    + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            // Bulk-seed 100k rows for tenant-a in one statement (qty = 1..100000), plus noise in tenant-b.
            statement.execute("INSERT INTO widgets (id, name, qty, tenant_id) "
                    + "SELECT RANDOM_UUID(), CONCAT('w-', X), X, 'tenant-a' FROM SYSTEM_RANGE(1, " + ROWS + ")");
            statement.execute("INSERT INTO widgets (id, name, qty, tenant_id) "
                    + "SELECT RANDOM_UUID(), CONCAT('b-', X), X, 'tenant-b' FROM SYSTEM_RANGE(1, 1000)");
        }

        CompiledConcept widget = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false),
                        new CompiledField("qty", "int", "Integer", false, true, false)
                )
        );
        CompiledModel model = new CompiledModel("lnch5.volume", "1.0.0", "1.0.0", Map.of(widget.getName(), widget));
        JdbcBusinessConceptStore store = new JdbcBusinessConceptStore(dataSource, model);

        Instant start = Instant.now();
        ConceptPage page = store.query("tenant-a", "Widget", new ConceptQuery(
                List.of(), List.of(ConceptQuery.Sort.asc("qty")), 0, 50));
        Duration elapsed = Duration.between(start, Instant.now());

        assertEquals(50, page.items().size(), "one page is bounded regardless of table size");
        assertEquals(ROWS, page.total(), "COUNT(*) reflects the whole tenant, tenant-b excluded");
        assertTrue(page.hasMore());
        assertEquals(1, ((Number) page.items().get(0).data().get("qty")).intValue(), "asc sort starts at 1");
        // A page over 100k rows must not take multiple seconds -- if it materialized every row it would.
        assertTrue(elapsed.toMillis() < 3000, "page query over 100k rows took " + elapsed.toMillis() + "ms");

        // A pushed-down filter narrows to the tail without scanning into the JVM.
        ConceptPage tail = store.query("tenant-a", "Widget", new ConceptQuery(
                List.of(new ConceptQuery.Filter("qty", ConceptQuery.Operator.GT, 99_990)),
                List.of(ConceptQuery.Sort.asc("qty")), 0, 50));
        assertEquals(10, tail.total());
        assertEquals(10, tail.items().size());
        assertEquals(99_991, ((Number) tail.items().get(0).data().get("qty")).intValue());
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
