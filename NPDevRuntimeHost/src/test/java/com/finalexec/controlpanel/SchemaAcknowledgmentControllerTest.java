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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B8 (Wave 2 package 2.1, docs/ACCEPTED_BOUNDARIES.md): the first dedicated MockMvc coverage for
 * {@link SchemaAcknowledgmentController} -- every prior endpoint on this controller relied solely on
 * the assembled-sample-app boot smoke test for whatever incidental exercise it got (see STOR-18's own
 * note on {@code restoreSnapshotBatch}). Mirrors {@code SchemaImpactControllerTest}'s standalone-
 * MockMvc + mocked {@link RuntimeContextService} pattern.
 *
 * <p>No {@code schema-realization-manifest.json} exists on this test module's classpath (same
 * documented limitation {@code SchemaImpactControllerTest}/{@code SchemaImpactFacadeH2Test} note), so
 * a SUPERUSER call past the auth gate reaches {@code requireManifest()}'s no-manifest branch --
 * proving the auth gate and the manifest precondition, not {@code OwnershipAdoption}'s own logic
 * (covered directly and thoroughly by {@code OwnershipAdoptionTest} instead).
 */
class SchemaAcknowledgmentControllerTest {

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
    void nonSuperUserIsForbiddenForBothOwnershipEndpoints() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester").withRoles(Set.of("USER")));

        mockMvc.perform(get("/api/admin/schema-migration/ownership/preview"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/schema-migration/ownership/adopt"))
                .andExpect(status().isForbidden());
    }

    @Test
    void superUserWithNoManifestOnTheClasspathGetsServiceUnavailable() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester").withRoles(Set.of("SUPERUSER")));

        // Proves the auth gate passes and the manifest precondition is reached (and enforced) for
        // both endpoints -- OwnershipAdoption's own preview/apply logic is unit-tested directly in
        // OwnershipAdoptionTest, against a real manifest, which this controller-level test cannot
        // construct without a generated schema-realization-manifest.json on the classpath.
        mockMvc.perform(get("/api/admin/schema-migration/ownership/preview"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(post("/api/admin/schema-migration/ownership/adopt"))
                .andExpect(status().isServiceUnavailable());
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
