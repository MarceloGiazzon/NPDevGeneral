package com.finalexec.api;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B10 (STOR-29, {@code ALL_HITTABLE_LIFT_PLAN_2026-09-05.md} package P6): {@link
 * SchemaPromotionController}'s own coverage, following {@code MetadataHotSwapControllerStandaloneTest}'s
 * exact standalone-MockMvc shape. The full realize/apply/verify arc itself is {@link
 * com.finalexec.db.PromotionArc}'s own concern ({@code PromoteMainTest} covers it against real H2) --
 * this class proves the controller's own wiring: the SUPERUSER gate, request-shape validation, and
 * the InMemory-mode refusal, none of which need a real generated app's schema-realization manifest
 * on the classpath (this bare template repo's own test run has none, matching {@code
 * SchemaVerifyMainTest}'s documented reasoning for the same split).
 */
class SchemaPromotionControllerStandaloneTest {

    private MockMvc mockMvc;
    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private final ExecutionContext executionContext = Mockito.mock(ExecutionContext.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<DataSource> dataSourceProvider = Mockito.mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        when(runtimeContextService.currentContext(any())).thenReturn(executionContext);
        SchemaPromotionController controller = new SchemaPromotionController(dataSourceProvider, runtimeContextService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void promoteRequiresSuperUser() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(false);

        mockMvc.perform(post("/api/v1/admin/schema/promote")
                        .contentType("application/json")
                        .content("{\"targetJdbcUrl\":\"jdbc:h2:mem:somewhere\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void promoteRefusesAMissingTargetJdbcUrlBeforeTouchingAnyDataSource() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);

        mockMvc.perform(post("/api/v1/admin/schema/promote")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // The SUPERUSER gate and the request-shape check both ran without ever asking the provider
        // for a DataSource -- proves the validation-before-side-effects ordering, not just the
        // outcome.
        Mockito.verifyNoInteractions(dataSourceProvider);
    }

    @Test
    void promoteRefusesInMemoryModeWhenNoDataSourceIsAvailable() throws Exception {
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);
        when(dataSourceProvider.getIfAvailable()).thenReturn(null);

        mockMvc.perform(post("/api/v1/admin/schema/promote")
                        .contentType("application/json")
                        .content("{\"targetJdbcUrl\":\"jdbc:h2:mem:somewhere\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void promoteRefusesWhenNoSchemaRealizationManifestIsOnTheClasspath() throws Exception {
        // This bare template repo's own test run carries no npdev/db/schema-realization-manifest.json
        // (that resource is emitted per-app by the generator) -- SchemaLifecycleExecutor.loadManifest()
        // returns null here exactly like it does for every other *Main/*Controller test in this class's
        // family (see SchemaVerifyMainTest's own documented split for the same reason).
        when(executionContext.hasRole("SUPERUSER")).thenReturn(true);
        DataSource dataSource = freshH2();
        when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);

        mockMvc.perform(post("/api/v1/admin/schema/promote")
                        .contentType("application/json")
                        .content("{\"targetJdbcUrl\":\"jdbc:h2:mem:somewhere\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    private static DataSource freshH2() throws SQLException {
        String url = "jdbc:h2:mem:" + SchemaPromotionControllerStandaloneTest.class.getSimpleName()
                + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        DataSource dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            statement.execute();
        }
        return dataSource;
    }

    /** Minimal {@link DataSource} over {@link DriverManager} -- matches every sibling test's own copy. */
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
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getLogger(getClass().getName());
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
