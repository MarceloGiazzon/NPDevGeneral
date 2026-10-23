package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REG-293 (X0 rule): a database failure while checking credentials is not a wrong password -- login
 * answers 503 login_unavailable and records no REG-20 throttle failure, so an outage neither tells a
 * user their password is wrong nor locks them out. Named apart from the template tree's
 * {@code LoginControllerTest} (assembled-app only) to avoid a duplicate FQCN.
 */
class LoginControllerDatabaseFailureTest {

    private static final String TENANT = "dev";

    @Test
    void aDatabaseFailureAnswersLoginUnavailableAndNeverCountsAgainstTheThrottle() throws Exception {
        DataSource down = mock(DataSource.class);
        when(down.getConnection()).thenThrow(new SQLException("connection refused", "08001"));
        LoginController controller = new LoginController(down, new ObjectMapper(), new DefaultResourceLoader(),
                identityModel(), "usuarios", "user_id", "senha_hash",
                "classpath:npdev/security/test-jwt-private.pem", "https://issuer.npdev.test", "npdev-runtime-beta", 600L);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr("10.0.0.9");

        // Well past every throttle ceiling: none of these may turn into 401 or 429.
        for (int attempt = 0; attempt < 25; attempt++) {
            ResponseEntity<Map<String, Object>> response =
                    controller.login(new LoginController.LoginRequest("ada", "right-password", TENANT), http);
            assertEquals(503, response.getStatusCode().value(), "attempt " + attempt + ": " + response.getBody());
            assertEquals("login_unavailable", response.getBody().get("error"));
        }

        java.lang.reflect.Field field = LoginController.class.getDeclaredField("throttle");
        field.setAccessible(true);
        LoginThrottle throttle = (LoginThrottle) field.get(controller);
        assertFalse(throttle.isLocked(TENANT, "ada", "10.0.0.9"), "an outage must not lock the user out");
    }

    @Test
    void aRuntimeFailureAlsoAnswersLoginUnavailable() throws Exception {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new IllegalStateException("pool closed"));
        LoginController controller = new LoginController(broken, new ObjectMapper(), new DefaultResourceLoader(),
                identityModel(), "usuarios", "user_id", "senha_hash",
                "classpath:npdev/security/test-jwt-private.pem", "https://issuer.npdev.test", "npdev-runtime-beta", 600L);

        ResponseEntity<Map<String, Object>> response = controller.login(
                new LoginController.LoginRequest("ada", "pw", TENANT), new MockHttpServletRequest());

        assertEquals(503, response.getStatusCode().value());
        assertEquals("login_unavailable", response.getBody().get("error"));
    }

    private static CompiledModel identityModel() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        return new CompiledModel("test", "1.0.0", concepts);
    }
}
