package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.runtime.support.IdentityRoleLookup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SESS-1: a session row per sign-in, a {@code sid} claim in the token, and a revoked session refused
 * (the other devices keep working) -- hermetic on H2 with the real signer.
 */
class UserSessionStoreTest {

    private static final String TENANT = "dev";
    private static final String ADA = "11111111-1111-1111-1111-111111111111";
    private static final String BOB = "22222222-2222-2222-2222-222222222222";

    private DataSource dataSource;
    private IdentityPackTableNames tables;

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
            s.execute("INSERT INTO identity_users VALUES (UUID '" + ADA + "', '" + TENANT + "', 'ada', 0)");
            s.execute("INSERT INTO identity_users VALUES (UUID '" + BOB + "', '" + TENANT + "', 'bob', 0)");
        }
        tables = new IdentityPackTableNames("identity_users", "identity_roles", "identity_user_roles",
                "identity_user_role_permissions", "identity_user_sessions");
    }

    private String open(String user, String agent) {
        return UserSessionStore.open(dataSource, tables, TENANT, user, agent, "10.0.0.1");
    }

    @Test
    void signingInOpensASessionRowAndTheTokenCarriesItsId() throws Exception {
        String sid = open("ada", "Firefox on Windows");

        assertNotNull(sid);
        List<UserSessionStore.Listed> listed = UserSessionStore.list(dataSource, tables, TENANT, "ada");
        assertEquals(1, listed.size());
        assertEquals("Firefox on Windows", listed.get(0).userAgent());
        assertEquals("10.0.0.1", listed.get(0).ipAddress());
        assertEquals(sid.substring(sid.length() - 6), listed.get(0).shortId(), "only the last six characters are shown");

        JwtSigner signer = new JwtSigner(new ObjectMapper(), JwtSigner.loadPrivateKey(testPrivatePem()),
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L);
        String token = signer.sign(TENANT, "ada", List.of("USER"), 0, sid);
        assertEquals(sid, claims(token).get("sid"));
        assertNull(claims(signer.sign(TENANT, "ada", List.of("USER"), 0)).get("sid"),
                "the old overload still mints a token without a session id");
    }

    @Test
    void aRevokedSessionIsRefusedWhileTheOtherDeviceKeepsWorking() throws Exception {
        String laptop = open("ada", "Laptop");
        String phone = open("ada", "Phone");

        assertFalse(IdentityRoleLookup.isSessionRevoked(phone, dataSource, tables, TENANT));
        assertTrue(UserSessionStore.revokeOwned(dataSource, tables, TENANT, "ada", phone));

        assertTrue(IdentityRoleLookup.isSessionRevoked(phone, dataSource, tables, TENANT), "the lost phone is out");
        assertFalse(IdentityRoleLookup.isSessionRevoked(laptop, dataSource, tables, TENANT), "the laptop is untouched");
        assertEquals(1, UserSessionStore.list(dataSource, tables, TENANT, "ada").size());
    }

    @Test
    void aUserCannotRevokeAnotherUsersSession() throws Exception {
        String adas = open("ada", "Laptop");

        assertFalse(UserSessionStore.revokeOwned(dataSource, tables, TENANT, "bob", adas));
        assertFalse(IdentityRoleLookup.isSessionRevoked(adas, dataSource, tables, TENANT));
        assertEquals(1, UserSessionStore.list(dataSource, tables, TENANT, "ada").size());
    }

    @Test
    void aRevocationMadeBehindTheCachesBackIsSeenOnceTheVerdictExpires() throws Exception {
        String sid = open("ada", "Laptop");
        assertFalse(IdentityRoleLookup.isSessionRevoked(sid, dataSource, tables, TENANT));   // cached "ok"

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE identity_user_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE session_id = '" + sid + "'");
        }
        assertFalse(IdentityRoleLookup.isSessionRevoked(sid, dataSource, tables, TENANT),
                "within the 30 s window the cached verdict stands (the documented delay)");
        IdentityRoleLookup.clearSessionCache();   // stands in for the 30 s passing
        assertTrue(IdentityRoleLookup.isSessionRevoked(sid, dataSource, tables, TENANT));
    }

    @Test
    void revokeOthersEndsEverySessionButTheCurrentOne() throws Exception {
        String current = open("ada", "Here");
        open("ada", "There");
        open("ada", "Elsewhere");
        String bobs = open("bob", "Bob's");

        UserSessionStore.revokeAllExcept(dataSource, tables, TENANT, "ada", current);

        List<UserSessionStore.Listed> left = UserSessionStore.list(dataSource, tables, TENANT, "ada");
        assertEquals(1, left.size());
        assertEquals("Here", left.get(0).userAgent());
        assertEquals(1, UserSessionStore.list(dataSource, tables, TENANT, "bob").size(), "bob is not affected");
        assertFalse(IdentityRoleLookup.isSessionRevoked(bobs, dataSource, tables, TENANT));
    }

    @Test
    void logoutEndsTheSessionTheTokenNames() throws Exception {
        String sid = open("ada", "Laptop");
        JwtSigner signer = new JwtSigner(new ObjectMapper(), JwtSigner.loadPrivateKey(testPrivatePem()),
                "https://issuer.npdev.test", "npdev-runtime-beta", 28800L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + signer.sign(TENANT, "ada", List.of("USER"), 0, sid));

        String[] named = UserSessionStore.sessionFromToken(request);
        UserSessionStore.revokeBySessionId(dataSource, tables, named[0], named[1]);

        assertEquals(sid, named[1]);
        assertTrue(IdentityRoleLookup.isSessionRevoked(sid, dataSource, tables, TENANT));
        assertEquals(0, UserSessionStore.list(dataSource, tables, TENANT, "ada").size());
    }

    @Test
    void anAppWithoutTheSessionsTableSignsInAsBefore() {
        IdentityPackTableNames old = new IdentityPackTableNames("identity_users", "identity_roles",
                "identity_user_roles", "identity_user_role_permissions");

        assertNull(UserSessionStore.open(dataSource, old, TENANT, "ada", "x", "y"), "sessions are simply off");
        assertFalse(IdentityRoleLookup.isSessionRevoked("any", dataSource, old, TENANT));
    }

    @Test
    void aMissingTableOrAnUnknownOrAbsentSessionIdNeverBlocksARequest() throws Exception {
        IdentityPackTableNames dangling = new IdentityPackTableNames("identity_users", "identity_roles",
                "identity_user_roles", "identity_user_role_permissions", "no_such_sessions_table");
        assertNull(UserSessionStore.open(dataSource, dangling, TENANT, "ada", "x", "y"), "a write problem is not a sign-in failure");
        assertFalse(IdentityRoleLookup.isSessionRevoked("abc", dataSource, dangling, TENANT));
        assertFalse(IdentityRoleLookup.isSessionRevoked("never-opened", dataSource, tables, TENANT));
        assertFalse(IdentityRoleLookup.isSessionRevoked(null, dataSource, tables, TENANT), "an old token has no sid");
    }

    private static Map<?, ?> claims(String jwt) throws Exception {
        return new ObjectMapper().readValue(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), Map.class);
    }

    private static String testPrivatePem() throws Exception {
        try (var in = new org.springframework.core.io.DefaultResourceLoader()
                .getResource("classpath:npdev/security/test-jwt-private.pem").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
