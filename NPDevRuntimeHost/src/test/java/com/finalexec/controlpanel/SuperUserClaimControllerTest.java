package com.finalexec.controlpanel;

import com.finalexec.npdev.service.CredentialRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-8 (docs/ACCEPTED_BOUNDARIES.md B17): mirrors {@link SchemaImpactControllerTest}'s standalone-
 * {@link MockMvc} pattern -- no full Spring context, a real H2-backed {@link
 * CredentialRegistryService} (the security-relevant behavior lives in its SQL, not worth mocking
 * away), and the {@code SUPER_USER_AUTHENTICATED_ATTRIBUTE} request attribute {@link
 * SuperUserCredentialAuthFilter} would normally set, simulated directly the way a filter test would.
 */
class SuperUserClaimControllerTest {

    private DataSource dataSource;
    private CredentialRegistryService credentialRegistryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE npdev_api_credential (credential_id VARCHAR(64) PRIMARY KEY, "
                    + "key_hash VARCHAR(64), tenant_id VARCHAR(120), actor_id VARCHAR(120), "
                    + "roles VARCHAR(255), status VARCHAR(32), created_at_ms BIGINT)");
        }
        credentialRegistryService = new CredentialRegistryService(new FixedProvider(dataSource));
        SuperUserClaimController controller = new SuperUserClaimController(credentialRegistryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void claimIssuesANamedCredentialAndRevokesExactlyTheOneThatAuthenticated() throws Exception {
        Map<String, Object> bootstrap = credentialRegistryService.issue("__system__", "bootstrap", Set.of("SUPERUSER"));
        String bootstrapKey = (String) bootstrap.get("apiKey");

        mockMvc.perform(post("/api/admin/superuser/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"alice\"}")
                        .header(SuperUserCredentialAuthFilter.HEADER_NAME, bootstrapKey)
                        .requestAttr(SuperUserCredentialAuthFilter.SUPER_USER_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey", notNullValue()))
                .andExpect(jsonPath("$.actorId").value("alice"))
                .andExpect(jsonPath("$.bootstrapCredentialRevoked").value(true));

        assertTrue(credentialRegistryService.resolve(bootstrapKey).isEmpty(),
                "the bootstrap credential must be revoked after a successful claim");
    }

    @Test
    void claimLeavesAnUnrelatedActiveSuperuserCredentialUntouched() throws Exception {
        Map<String, Object> bootstrap = credentialRegistryService.issue("__system__", "bootstrap", Set.of("SUPERUSER"));
        Map<String, Object> unrelated = credentialRegistryService.issue("__system__", "someone-else", Set.of("SUPERUSER"));
        String bootstrapKey = (String) bootstrap.get("apiKey");
        String unrelatedKey = (String) unrelated.get("apiKey");

        mockMvc.perform(post("/api/admin/superuser/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"alice\"}")
                        .header(SuperUserCredentialAuthFilter.HEADER_NAME, bootstrapKey)
                        .requestAttr(SuperUserCredentialAuthFilter.SUPER_USER_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE))
                .andExpect(status().isOk());

        assertTrue(credentialRegistryService.resolve(unrelatedKey).isPresent(),
                "a claim must only ever revoke the credential that authenticated IT, never another active one");
    }

    @Test
    void claimWithoutTheLiveSuperKeyMarkerIsForbidden() throws Exception {
        // No SUPER_USER_AUTHENTICATED_ATTRIBUTE set -- simulates a business JWT that happens to
        // carry a SUPERUSER role, which REG-22 says must NOT be able to mint/revoke credentials.
        mockMvc.perform(post("/api/admin/superuser/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"alice\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.boundaryId").value("B17"));
        assertTrue(credentialRegistryService.list().isEmpty(), "a forbidden claim must issue nothing");
    }

    @Test
    void claimWithoutAnActorIdIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/superuser/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr(SuperUserCredentialAuthFilter.SUPER_USER_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE))
                .andExpect(status().isBadRequest());
    }

    private record FixedProvider(DataSource dataSource) implements ObjectProvider<DataSource> {
        @Override public DataSource getObject(Object... args) { return dataSource; }
        @Override public DataSource getObject() { return dataSource; }
        @Override public DataSource getIfAvailable() { return dataSource; }
        @Override public DataSource getIfAvailable(Supplier<DataSource> defaultSupplier) { return dataSource; }
        @Override public DataSource getIfUnique() { return dataSource; }
        @Override public DataSource getIfUnique(Supplier<DataSource> defaultSupplier) { return dataSource; }
    }

    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
