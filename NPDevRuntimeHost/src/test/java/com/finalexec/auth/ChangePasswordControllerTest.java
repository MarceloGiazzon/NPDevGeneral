package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WMS-9 N6: hermetic H2-backed proof of the logged-in self-service "change my password" flow --
 * mirrors {@code PasswordResetControllerTest}'s hermetic pattern (no Spring context, real H2 schema).
 * The current caller is resolved via a mocked {@link AuthenticatedContextResolver} fed claims stashed
 * on the request attribute {@code npdev.auth.claims} (a plain {@code requestAttr}, matching how the
 * real JWT/api-key auth filter populates it) rather than from the request body -- this class lives in
 * runtimehost-core and cannot depend on the generated RuntimeContextService the top-level module's
 * AggregateApiControllerTest mocks instead, see ChangePasswordController's own javadoc.
 */
class ChangePasswordControllerTest {

    private static final String TENANT = "tenantx";
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DataSource dataSource;
    private final AuthenticatedContextResolver authenticatedContextResolver = Mockito.mock(AuthenticatedContextResolver.class);

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, username VARCHAR(120), "
                    + "active BOOLEAN, tenant_id VARCHAR(120), token_version INT)");
            s.execute("CREATE TABLE usuarios (id UUID PRIMARY KEY, user_id UUID, senha_hash VARCHAR(200), "
                    + "tenant_id VARCHAR(120))");
            s.execute("INSERT INTO identity_users VALUES ('" + USER_ID + "','ada',TRUE,'" + TENANT + "',0)");
            s.execute("INSERT INTO usuarios VALUES (RANDOM_UUID(), '" + USER_ID + "', '"
                    + PasswordHasher.hash("old-password") + "', '" + TENANT + "')");
        }
        when(authenticatedContextResolver.resolveFromPrincipal(any(), any())).thenReturn(ExecutionContext.of(TENANT, "ada"));
    }

    private MockMvc mockMvc() throws Exception {
        return MockMvcBuilders.standaloneSetup(new ChangePasswordController(
                dataSource, authenticatedContextResolver, new ObjectMapper(), new DefaultResourceLoader(),
                new ModelHolder(identityModel()),
                "usuarios", "user_id", "senha_hash",
                "classpath:npdev/security/test-jwt-private.pem",
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L
        )).build();
    }

    // Same hand-built-CompiledModel pattern LoginControllerTest uses (REG-177: table names resolve
    // from the model, not a hardcoded literal). Role/UserRole/UserRolePermission tables never
    // physically exist in this test's H2 schema, but that's fine -- IdentityRoleLookup.rolesFor
    // fails open (empty roles) on a real "table not found" SQLException.
    private static CompiledModel identityModel() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        return new CompiledModel("test", "1.0.0", concepts);
    }

    @Test
    void correctOldPasswordChangesHashBumpsTokenVersionAndReturnsAFreshToken() throws Exception {
        mockMvc().perform(post("/api/auth/change-password")
                        .requestAttr("npdev.auth.claims", Map.of("sub", "ada"))
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"old-password\",\"newPassword\":\"brand-new-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.token").exists());

        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT senha_hash FROM usuarios WHERE user_id = ?")) {
                ps.setObject(1, USER_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertTrue(PasswordHasher.verify("brand-new-password", rs.getString(1)));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT token_version FROM identity_users WHERE id = ?")) {
                ps.setObject(1, USER_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "a successful change must bump token_version exactly once");
                }
            }
        }
    }

    @Test
    void wrongOldPasswordIsRejectedAndHashIsUnchanged() throws Exception {
        mockMvc().perform(post("/api/auth/change-password")
                        .requestAttr("npdev.auth.claims", Map.of("sub", "ada"))
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"totally-wrong\",\"newPassword\":\"brand-new-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_old_password"));

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT senha_hash FROM usuarios WHERE user_id = ?")) {
            ps.setObject(1, USER_ID);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(PasswordHasher.verify("old-password", rs.getString(1)),
                        "a rejected old-password check must leave the stored hash untouched");
            }
        }
    }

    @Test
    void tooShortNewPasswordIsRejectedAsInvalidRequest() throws Exception {
        mockMvc().perform(post("/api/auth/change-password")
                        .requestAttr("npdev.auth.claims", Map.of("sub", "ada"))
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"old-password\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void freshTokenDiffersFromOneMintedBeforeTheChange() throws Exception {
        JwtSigner signer = new JwtSigner(new ObjectMapper(),
                JwtSigner.loadPrivateKey(LoginController.readKeyFile(
                        new DefaultResourceLoader(), "classpath:npdev/security/test-jwt-private.pem")),
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L);
        String tokenBefore = signer.sign(TENANT, "ada", java.util.Set.of(), 0);

        String responseJson = mockMvc().perform(post("/api/auth/change-password")
                        .requestAttr("npdev.auth.claims", Map.of("sub", "ada"))
                        .contentType("application/json")
                        .content("{\"oldPassword\":\"old-password\",\"newPassword\":\"brand-new-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(responseJson.contains("\"token\""));
        String tokenAfter = new ObjectMapper().readTree(responseJson).get("token").asText();
        assertNotEquals(tokenBefore, tokenAfter,
                "the response token must be freshly minted with the bumped token_version, not the pre-change one");
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
