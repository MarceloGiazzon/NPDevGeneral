package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REG-341: a filter on a {@code datetime} column whose value is a plain date ({@code createdAt >= today()}, which the
 * predicate compiler resolves to {@code "2026-11-18"}, or {@code ?where=createdAt:gte:2026-11-18}) used to throw in
 * {@code coerceValue} (neither an offset nor a zone-less date-time), so the experience's server-side
 * {@code count('Ride', createdAt >= today())} answered null. A plain date now means the START of that day in UTC.
 */
class JdbcBusinessConceptStoreDatetimeFilterTest {

    private static final String TENANT = "tenant-a";

    @Test
    void aPlainDateBoundSelectsRowsFromTheStartOfThatUtcDay() {
        JdbcBusinessConceptStore store = newStore();
        save(store, "before", "2026-11-17T23:59:59Z");
        save(store, "midnight", "2026-11-18T00:00:00Z");
        save(store, "noon", "2026-11-18T12:00:00Z");
        save(store, "tomorrow", "2026-11-19T00:00:01Z");

        assertEquals(List.of("midnight", "noon", "tomorrow"),
                names(store, new ConceptQuery.Filter("seenAt", ConceptQuery.Operator.GTE, "2026-11-18")));
        assertEquals(List.of("before"),
                names(store, new ConceptQuery.Filter("seenAt", ConceptQuery.Operator.LT, "2026-11-18")));
        assertEquals(List.of("tomorrow"),
                names(store, new ConceptQuery.Filter("seenAt", ConceptQuery.Operator.GTE, "2026-11-19")));
    }

    @Test
    void anInstantBoundStillWorksAsBefore() {
        JdbcBusinessConceptStore store = newStore();
        save(store, "a", "2026-11-18T10:00:00Z");
        save(store, "b", "2026-11-18T14:00:00Z");
        assertEquals(List.of("b"),
                names(store, new ConceptQuery.Filter("seenAt", ConceptQuery.Operator.GTE, "2026-11-18T12:00:00Z")));
    }

    private static List<String> names(JdbcBusinessConceptStore store, ConceptQuery.Filter filter) {
        ConceptPage page = store.query(TENANT, "Sighting",
                new ConceptQuery(List.of(filter), List.of(ConceptQuery.Sort.asc("seenAt")), 0, 50));
        return page.items().stream().map(r -> String.valueOf(r.data().get("label"))).toList();
    }

    private static void save(JdbcBusinessConceptStore store, String label, String seenAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("label", label);
        data.put("seenAt", seenAt);
        store.save(new ConceptRecord("Sighting", UUID.randomUUID().toString(), TENANT, data));
    }

    private static JdbcBusinessConceptStore newStore() {
        CompiledConcept sighting = new CompiledConcept(
                "Sighting", "Sighting", "sightings",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("label", "string", "String", false, true, false),
                        new CompiledField("seenAt", "datetime", "java.time.OffsetDateTime", false, false, false)
                ));
        CompiledModel model = new CompiledModel("reg341.datetime", "1.0.0", "1.0.0", Map.of(sighting.getName(), sighting));
        try {
            String url = "jdbc:h2:mem:reg341-datetime-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
            DataSource dataSource = new UrlDataSource(url);
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE sightings (id UUID NOT NULL, label VARCHAR(255), "
                        + "seen_at TIMESTAMP WITH TIME ZONE, tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            }
            return new JdbcBusinessConceptStore(dataSource, model);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to build H2-backed JDBC concept store", exception);
        }
    }

    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
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
