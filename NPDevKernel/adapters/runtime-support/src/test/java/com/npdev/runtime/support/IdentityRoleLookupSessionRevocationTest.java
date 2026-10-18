package com.npdev.runtime.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SESS-1: {@link IdentityRoleLookup#isSessionRevoked} -- a token's {@code sid} is refused once its
 * identity::UserSession row is revoked, the verdict is cached for {@link IdentityRoleLookup#SESSION_CACHE_MILLIS},
 * {@code last_seen_at} is refreshed at most every {@link IdentityRoleLookup#SESSION_TOUCH_MILLIS}, and every
 * way the lookup cannot answer degrades to "not revoked" rather than a sign-in outage.
 */
class IdentityRoleLookupSessionRevocationTest {

    private static final String TENANT = "tenantx";
    private static final IdentityPackTableNames TABLES = new IdentityPackTableNames(
            "identity_users", "identity_roles", "identity_user_roles", "identity_user_role_permissions",
            "identity_user_sessions");

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        IdentityRoleLookup.clearSessionCache();
        dataSource = new UrlDataSource("jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE identity_user_sessions (session_id VARCHAR(64), tenant_id VARCHAR(120), "
                    + "revoked_at TIMESTAMP, last_seen_at TIMESTAMP)");
        }
    }

    @AfterEach
    void tearDown() {
        IdentityRoleLookup.clearSessionCache();
    }

    private void insertSession(String sid, String tenant, Timestamp revokedAt, Timestamp lastSeenAt) throws Exception {
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement("INSERT INTO identity_user_sessions VALUES (?, ?, ?, ?)")) {
            ps.setString(1, sid);
            ps.setString(2, tenant);
            ps.setTimestamp(3, revokedAt);
            ps.setTimestamp(4, lastSeenAt);
            ps.executeUpdate();
        }
    }

    private Timestamp lastSeen(String sid) throws Exception {
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement("SELECT last_seen_at FROM identity_user_sessions WHERE session_id = ?")) {
            ps.setString(1, sid);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getTimestamp(1);
            }
        }
    }

    private void revoke(String sid) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE identity_user_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE session_id = '" + sid + "'");
        }
    }

    @Test
    void aTokenWithoutASidOrWithoutTheSessionsFeatureIsNeverRevoked() {
        assertFalse(IdentityRoleLookup.isSessionRevoked(null, dataSource, TABLES, TENANT));
        assertFalse(IdentityRoleLookup.isSessionRevoked("  ", dataSource, TABLES, TENANT));
        assertFalse(IdentityRoleLookup.isSessionRevoked("sid", null, TABLES, TENANT));
        assertFalse(IdentityRoleLookup.isSessionRevoked("sid", dataSource, null, TENANT));
        assertFalse(IdentityRoleLookup.isSessionRevoked("sid", dataSource,
                new IdentityPackTableNames("u", "r", "ur", "urp"), TENANT), "an older identity pack means sessions are off");
    }

    @Test
    void aRevokedSessionIsRefusedAndALiveOrUnknownOneIsNot() throws Exception {
        insertSession("revoked", TENANT, new Timestamp(System.currentTimeMillis()), new Timestamp(System.currentTimeMillis()));
        insertSession("live", TENANT, null, new Timestamp(System.currentTimeMillis()));

        assertTrue(IdentityRoleLookup.isSessionRevoked("revoked", dataSource, TABLES, TENANT));
        assertFalse(IdentityRoleLookup.isSessionRevoked("live", dataSource, TABLES, TENANT));
        assertFalse(IdentityRoleLookup.isSessionRevoked("never-issued", dataSource, TABLES, TENANT));
    }

    @Test
    void aSessionOfAnotherTenantIsNotVisible() throws Exception {
        insertSession("theirs", "othertenant", new Timestamp(System.currentTimeMillis()), null);

        assertFalse(IdentityRoleLookup.isSessionRevoked("theirs", dataSource, TABLES, TENANT),
                "the lookup is tenant-scoped, so another tenant's revoked row says nothing about this one");
    }

    @Test
    void aBlankTenantMeansTheDefaultTenant() throws Exception {
        insertSession("dflt", "default", new Timestamp(System.currentTimeMillis()), null);

        assertTrue(IdentityRoleLookup.isSessionRevoked("dflt", dataSource, TABLES, null));
        IdentityRoleLookup.forgetSession(" ", "dflt");
        assertTrue(IdentityRoleLookup.isSessionRevoked("dflt", dataSource, TABLES, " "));
    }

    @Test
    void theVerdictIsCachedUntilTheSessionIsForgotten() throws Exception {
        insertSession("cached", TENANT, null, new Timestamp(System.currentTimeMillis()));
        assertFalse(IdentityRoleLookup.isSessionRevoked("cached", dataSource, TABLES, TENANT));

        revoke("cached");
        assertFalse(IdentityRoleLookup.isSessionRevoked("cached", dataSource, TABLES, TENANT),
                "within the cache window another node's revoke is not seen yet (documented upper bound)");

        IdentityRoleLookup.forgetSession(TENANT, "cached");
        assertTrue(IdentityRoleLookup.isSessionRevoked("cached", dataSource, TABLES, TENANT),
                "forgetSession makes a revoke made on THIS node show at once");
    }

    @Test
    void lastSeenIsRefreshedWhenStaleAndLeftAloneWhenFresh() throws Exception {
        Timestamp stale = new Timestamp(System.currentTimeMillis() - IdentityRoleLookup.SESSION_TOUCH_MILLIS - 60_000L);
        Timestamp fresh = new Timestamp(System.currentTimeMillis() - 1_000L);
        insertSession("stale", TENANT, null, stale);
        insertSession("fresh", TENANT, null, fresh);
        insertSession("never", TENANT, null, null);

        IdentityRoleLookup.isSessionRevoked("stale", dataSource, TABLES, TENANT);
        IdentityRoleLookup.isSessionRevoked("fresh", dataSource, TABLES, TENANT);
        IdentityRoleLookup.isSessionRevoked("never", dataSource, TABLES, TENANT);

        assertTrue(lastSeen("stale").after(stale), "a stale last_seen_at is refreshed");
        assertEquals(fresh, lastSeen("fresh"), "a fresh last_seen_at is not rewritten on every request");
        assertTrue(lastSeen("never") != null, "a session never seen gets its first last_seen_at");
    }

    @Test
    void aSessionTableThatCannotBeReadDegradesToNotRevoked() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE identity_user_sessions");
        }

        assertFalse(IdentityRoleLookup.isSessionRevoked("any", dataSource, TABLES, TENANT),
                "a stale pack or transient DB error must not lock everyone out");
    }

    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
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
