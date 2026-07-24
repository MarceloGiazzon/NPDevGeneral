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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SER-P6.5. Mirrors {@code SupportedCoreControllerBlackBoxStandaloneTest}'s mocking pattern (a
 * mocked {@link RuntimeContextService} feeding {@link ExecutionContext} role sets into a
 * standalone-{@link MockMvc} controller) since {@code SchemaAcknowledgmentController} — the pattern
 * this controller copies — has no dedicated test of its own to copy directly.
 */
class SchemaImpactControllerTest {

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

        SchemaImpactController controller = new SchemaImpactController(dataSourceProvider, runtimeContextService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void nonSuperUserIsForbidden() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester").withRoles(Set.of("USER")));

        mockMvc.perform(get("/api/admin/schema-migration/impact"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/schema-migration/impact/view"))
                .andExpect(status().isForbidden());
    }

    @Test
    void superUserGetsTheJsonReport() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester").withRoles(Set.of("SUPERUSER")));

        // No manifest on this test's classpath -> the facade's no-manifest branch -> NO_CHANGES, but
        // the point of this test is the auth-pass + wiring, not the diff content (that is
        // SchemaImpactFacadeH2Test's job).
        mockMvc.perform(get("/api/admin/schema-migration/impact"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"verdict\"")));
    }

    @Test
    void superUserGetsTheHtmlView() throws Exception {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester").withRoles(Set.of("SUPERUSER")));

        mockMvc.perform(get("/api/admin/schema-migration/impact/view"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Schema impact report")));
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
