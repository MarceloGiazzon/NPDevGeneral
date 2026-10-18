package com.finalexec.auth;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.runtime.support.IdentityRoleLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SESS-2: "Where you're signed in" -- own sessions only, this device marked, one revocable at a time. */
class UserSessionsControllerTest {

    private static final String TENANT = "dev";

    private DataSource dataSource;
    private UserSessionsController controller;
    private com.npdev.dsl.v1.compiled.IdentityPackTableNames tables;

    @BeforeEach
    void setUp() throws Exception {
        IdentityRoleLookup.clearSessionCache();
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new UrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_users (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "username VARCHAR(120), token_version INT)");
            s.execute("CREATE TABLE identity_user_sessions (id UUID PRIMARY KEY, tenant_id VARCHAR(120), "
                    + "session_id VARCHAR(64) UNIQUE, user_id UUID, created_at TIMESTAMP, last_seen_at TIMESTAMP, "
                    + "user_agent VARCHAR(300), ip_address VARCHAR(64), revoked_at TIMESTAMP)");
            s.execute("INSERT INTO identity_users VALUES (UUID '11111111-1111-1111-1111-111111111111', '" + TENANT + "', 'ada', 0)");
            s.execute("INSERT INTO identity_users VALUES (UUID '22222222-2222-2222-2222-222222222222', '" + TENANT + "', 'bob', 0)");
        }
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", new CompiledConcept("User", "User", "identity_users", List.of()));
        concepts.put("identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()));
        concepts.put("identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()));
        concepts.put("identity::UserRolePermission",
                new CompiledConcept("UserRolePermission", "UserRolePermission", "identity_user_role_permissions", List.of()));
        concepts.put("identity::UserSession",
                new CompiledConcept("UserSession", "UserSession", "identity_user_sessions", List.of()));
        CompiledModel model = new CompiledModel("test", "1.0.0", concepts);
        controller = new UserSessionsController(dataSource, new ModelHolder(model));
        tables = com.npdev.dsl.v1.compiled.IdentityPackTableNames.tryResolve(model).orElseThrow();
    }

    private static MockHttpServletRequest as(String user, String sid) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user);
        claims.put("tenant_id", TENANT);
        if (sid != null) {
            claims.put("sid", sid);
        }
        request.setAttribute("npdev.auth.claims", claims);
        return request;
    }

    private String open(String user, String agent) {
        return UserSessionStore.open(dataSource, tables, TENANT, user, agent, "10.0.0.9");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sessionsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("sessions");
    }

    @Test
    void listsOnlyTheCallersOwnSessionsAndMarksTheCurrentOne() {
        String here = open("ada", "Firefox/Windows");
        open("ada", "Phone");
        open("bob", "Bob's laptop");

        var response = controller.list(as("ada", here));

        assertEquals(200, response.getStatusCode().value());
        List<Map<String, Object>> sessions = sessionsOf(response.getBody());
        assertEquals(2, sessions.size(), "bob's session is not ada's to see");
        long current = sessions.stream().filter(s -> Boolean.TRUE.equals(s.get("current"))).count();
        assertEquals(1, current);
        Map<String, Object> mine = sessions.stream().filter(s -> Boolean.TRUE.equals(s.get("current"))).findFirst().orElseThrow();
        assertEquals("Firefox/Windows", mine.get("userAgent"));
        assertEquals(here.substring(here.length() - 6), mine.get("id"), "only the last six characters are sent");
        assertFalse(response.getBody().toString().contains(here), "the full session id never leaves the server");
    }

    @Test
    void revokingAnotherDeviceEndsOnlyThatDevice() {
        String laptop = open("ada", "Laptop");
        String phone = open("ada", "Phone");

        var response = controller.revoke(as("ada", laptop), phone.substring(phone.length() - 6));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(IdentityRoleLookup.isSessionRevoked(phone, dataSource, tables, TENANT));
        assertFalse(IdentityRoleLookup.isSessionRevoked(laptop, dataSource, tables, TENANT));
        assertEquals(1, sessionsOf(controller.list(as("ada", laptop)).getBody()).size());
    }

    @Test
    void aUserCannotRevokeSomeoneElsesSession() {
        String adas = open("ada", "Laptop");
        String bobs = open("bob", "Laptop");

        var response = controller.revoke(as("bob", bobs), adas.substring(adas.length() - 6));

        assertEquals(404, response.getStatusCode().value(), "someone else's id reads as not found");
        assertFalse(IdentityRoleLookup.isSessionRevoked(adas, dataSource, tables, TENANT));
    }

    @Test
    void theCurrentSessionIsNotRevokedFromTheList() {
        String here = open("ada", "Laptop");

        var response = controller.revoke(as("ada", here), here.substring(here.length() - 6));

        assertEquals(409, response.getStatusCode().value());
        assertFalse(IdentityRoleLookup.isSessionRevoked(here, dataSource, tables, TENANT));
    }

    @Test
    void anUnknownHandleAndAMissingIdentityAreRefusedCleanly() {
        open("ada", "Laptop");

        assertEquals(404, controller.revoke(as("ada", null), "zzzzzz").getStatusCode().value());
        assertEquals(404, controller.revoke(as("ada", null), "ab").getStatusCode().value(), "too short to name a session");
        assertEquals(401, controller.list(new MockHttpServletRequest()).getStatusCode().value());
    }

    @Test
    void anAppWithoutTheSessionsTableListsNothingInsteadOfFailing() {
        CompiledModel old = new CompiledModel("test", "1.0.0", Map.of(
                "identity::User", new CompiledConcept("User", "User", "identity_users", List.of()),
                "identity::Role", new CompiledConcept("Role", "Role", "identity_roles", List.of()),
                "identity::UserRole", new CompiledConcept("UserRole", "UserRole", "identity_user_roles", List.of()),
                "identity::UserRolePermission", new CompiledConcept("UserRolePermission", "UserRolePermission",
                        "identity_user_role_permissions", List.of())));
        UserSessionsController legacy = new UserSessionsController(dataSource, new ModelHolder(old));

        var response = legacy.list(as("ada", null));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(sessionsOf(response.getBody()).isEmpty());
        assertEquals(false, response.getBody().get("enabled"));
    }

    private record UrlDataSource(String url) implements DataSource {
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("t"); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("no"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
