package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptReadRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-10 slice 1 gate test: proves {@link ConceptQueryController#exportCsv} exports a 100k-row
 * concept as CSV by paging through {@link ConceptGateway#query} in bounded, {@link
 * ConceptQuery#MAX_LIMIT}-sized pages -- never a single fetch-all call -- which is what keeps a
 * 100k-row export from materializing the whole table in the JVM at once (the same failure mode
 * {@code ConceptQueryVolumeTest} pins for the plain paged-list endpoint).
 */
class ConceptQueryControllerExportCsvVolumeTest {

    private static final int ROWS = 100_000;

    @Test
    void exportsOneHundredThousandRowsInBoundedPagesWithoutFetchingAllAtOnce() throws SQLException {
        String url = "jdbc:h2:mem:lnch10-export-volume-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID NOT NULL, name VARCHAR(255), qty INT, "
                    + "tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
            statement.execute("INSERT INTO widgets (id, name, qty, tenant_id) "
                    + "SELECT RANDOM_UUID(), CONCAT('w-', X), X, 'tenant-a' FROM SYSTEM_RANGE(1, " + ROWS + ")");
        }

        CompiledConcept widget = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false),
                        new CompiledField("qty", "int", "Integer", false, true, false)
                )
        );
        CompiledModel model = new CompiledModel("lnch10.export.volume", "1.0.0", "1.0.0", Map.of(widget.getName(), widget));

        AtomicInteger queryCallCount = new AtomicInteger();
        ConceptGateway countingGateway = new CountingQueryConceptGateway(
                new DefaultConceptGateway(new com.finalexec.db.JdbcBusinessConceptStore(dataSource, model)),
                queryCallCount
        );

        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        Mockito.when(runtimeContextService.currentContext(Mockito.any()))
                .thenReturn(ExecutionContext.of("tenant-a", "test-actor"));

        ConceptQueryController controller = new ConceptQueryController(runtimeContextService, countingGateway);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/concepts/Widget/export.csv");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.exportCsv(request, response, "Widget");

        String csv = response.getContentAsString();
        String[] lines = csv.split("\r\n");
        // Header + ROWS data lines; MockHttpServletResponse's contentAsString drops a trailing
        // empty element for the final \r\n, so this is exactly right, not off-by-one.
        assertEquals(ROWS + 1, lines.length, "CSV must contain the header row plus every data row");
        assertEquals("id,name,qty", lines[0]);

        int expectedPageCount = (ROWS + ConceptQuery.MAX_LIMIT - 1) / ConceptQuery.MAX_LIMIT;
        assertEquals(expectedPageCount, queryCallCount.get(),
                "must page through MAX_LIMIT-sized pages, never fetch all 100k rows in one call");
        assertTrue(queryCallCount.get() > 1, "sanity: a single-page export would defeat the point of this test");

        assertEquals("text/csv;charset=UTF-8", response.getContentType());
        assertEquals("attachment; filename=\"Widget.csv\"", response.getHeader("Content-Disposition"));
    }

    /** Delegates everything but asserts every {@code query()} call's requested limit never
     * exceeds {@link ConceptQuery#MAX_LIMIT} -- the concrete, deterministic proof that the export
     * endpoint pages rather than fetching the whole table in one shot. */
    private static final class CountingQueryConceptGateway implements ConceptGateway {
        private final ConceptGateway delegate;
        private final AtomicInteger queryCallCount;

        private CountingQueryConceptGateway(ConceptGateway delegate, AtomicInteger queryCallCount) {
            this.delegate = delegate;
            this.queryCallCount = queryCallCount;
        }

        @Override
        public ConceptPage query(ConceptQueryRequest request, ExecutionContext context) {
            queryCallCount.incrementAndGet();
            assertTrue(
                    request.query().limit() <= ConceptQuery.MAX_LIMIT,
                    "export must never request more than MAX_LIMIT rows in a single page"
            );
            return delegate.query(request, context);
        }

        @Override
        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
            return delegate.read(request, context);
        }

        @Override
        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
            return delegate.list(request, context);
        }

        @Override
        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
            return delegate.save(request, context);
        }

        @Override
        public void delete(ConceptReadRequest request, ExecutionContext context) {
            delegate.delete(request, context);
        }

        @Override
        public List<com.npdev.kernel.concepts.ConceptGatewayTraceRecord> explain() {
            return delegate.explain();
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
