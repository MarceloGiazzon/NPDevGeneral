package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * REG-293 (X0 rule): a sign-out whose server-side session revocation fails answers 503
 * session_revoke_failed -- with the cookie still cleared -- instead of a 204 that let the device
 * believe it was signed out while its token stayed valid; and a blank tenant is refused, not
 * defaulted to "default" (which revoked nothing and looked successful).
 */
class LogoutControllerRevocationFailureTest {

    private static final IdentityPackTableNames TABLES = new IdentityPackTableNames("identity_users",
            "identity_roles", "identity_user_roles", "identity_user_role_permissions", "identity_user_sessions");

    @Test
    void aFailedRevocationAnswers503SessionRevokeFailedAndStillClearsTheCookie() throws Exception {
        DataSource down = mock(DataSource.class);
        when(down.getConnection()).thenThrow(new SQLException("connection refused", "08001"));
        LogoutController controller = new LogoutController(down, new ModelHolder(identityModel()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + signedToken("dev", "sid-0123456789abcdef"));

        ResponseEntity<?> response = controller.logout(request);

        assertEquals(503, response.getStatusCode().value());
        assertEquals("session_revoke_failed", ((Map<?, ?>) response.getBody()).get("error"));
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie, "the browser cookie is cleared even when the server-side revocation failed");
        assertTrue(cookie.startsWith("npdev_jwt=;") && cookie.contains("Max-Age=0"), cookie);
    }

    @Test
    void aBlankTenantIsRefusedNotDefaulted() {
        DataSource dataSource = mock(DataSource.class);

        assertThrows(IllegalArgumentException.class,
                () -> UserSessionStore.revokeBySessionId(dataSource, TABLES, " ", "sid-0123456789abcdef"));
        verifyNoInteractions(dataSource);
    }

    @Test
    void aTokenWithABlankTenantAnswers503RatherThanAQuiet204() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        LogoutController controller = new LogoutController(dataSource, new ModelHolder(identityModel()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + unsignedToken("{\"sid\":\"sid-0123456789abcdef\"}"));

        ResponseEntity<?> response = controller.logout(request);

        assertEquals(503, response.getStatusCode().value());
        verifyNoInteractions(dataSource);
    }

    private static String signedToken(String tenant, String sid) throws Exception {
        String pem;
        try (var in = new DefaultResourceLoader().getResource("classpath:npdev/security/test-jwt-private.pem")
                .getInputStream()) {
            pem = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new JwtSigner(new ObjectMapper(), JwtSigner.loadPrivateKey(pem), "https://issuer.npdev.test",
                "npdev-runtime-beta", 600L).sign(tenant, "ada", List.of("USER"), 0, sid);
    }

    private static String unsignedToken(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".x";
    }

    private static CompiledModel identityModel() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        concepts.put("identity::UserSession",
                new CompiledConcept("UserSession", "UserSession", "identity_user_sessions", List.of()));
        return new CompiledModel("test", "1.0.0", concepts);
    }
}
