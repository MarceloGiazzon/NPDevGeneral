package com.finalexec.controlpanel;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * STOR-31 (boundary B3, POSTURAL_LIFT_PLAN_2026-09-07.md package P3): the controller wiring for the
 * surplus preview/drop endpoints -- SUPERUSER gate, InMemory/no-manifest preconditions, and request
 * shape. The schema-realization manifest is a FIXED classpath resource and none exists on this test
 * module's classpath (same documented limitation as {@code SchemaImpactControllerTest}/
 * {@code SchemaImpactFacadeH2Test}), so the token/plan/execute logic the endpoints forward to is
 * exercised end-to-end over a REAL H2 database and a real manifest in
 * {@code SurplusDropFlowH2Test} -- the plan's three scenario assertions (preview issues a token; a
 * stale token is refused; a non-FOREIGN name is refused and nothing is dropped) live there, for the
 * exact reason {@code SchemaAcknowledgmentControllerTest}'s javadoc names: a manifest resource on
 * this classpath would poison every other test in the module that relies on its absence.
 */
class SchemaAcknowledgmentControllerSurplusTest {

    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private DataSource dataSource;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY)");
        }

        ObjectProvider<DataSource> dataSourceProvider = Mockito.mock(ObjectProvider.class);
        when(dataSourceProvider.getIfAvailable()).thenReturn(dataSource);

        SchemaAcknowledgmentController controller =
                new SchemaAcknowledgmentController(dataSourceProvider, runtimeContextService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void nonSuperUserIsForbiddenForBothSurplusEndpoints() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester").withRoles(Set.of("USER")));

        mockMvc.perform(get("/api/admin/schema-migration/surplus/preview"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/schema-migration/surplus/drop")
                        .contentType("application/json").content("{\"constraints\":[\"x\"],\"ackToken\":\"t\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void superUserWithoutAPhysicalManifestGetsAnEmptyPreviewAndA503Drop() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester").withRoles(Set.of("SUPERUSER")));

        // No schema-realization-manifest.json on this classpath: preview is the honest empty answer
        // (there is nothing to classify), the drop refuses with a physical-database precondition.
        mockMvc.perform(get("/api/admin/schema-migration/surplus/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surplus").isEmpty())
                .andExpect(jsonPath("$.dropToken").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(post("/api/admin/schema-migration/surplus/drop")
                        .contentType("application/json").content("{\"constraints\":[\"x\"],\"ackToken\":\"t\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void dropRequiresBothItemizedConstraintsAndAToken() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester").withRoles(Set.of("SUPERUSER")));

        mockMvc.perform(post("/api/admin/schema-migration/surplus/drop")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_required_field"));
        mockMvc.perform(post("/api/admin/schema-migration/surplus/drop")
                        .contentType("application/json").content("{\"constraints\":[\"x\"],\"ackToken\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_required_field"));
    }

    /** Minimal {@link DataSource} over {@link DriverManager} (no H2-specific compile dependency). */
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
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}