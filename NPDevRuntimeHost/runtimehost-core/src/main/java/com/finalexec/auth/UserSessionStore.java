package com.finalexec.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.runtime.support.IdentityRoleLookup;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SESS-1: JDBC access to {@code identity::UserSession} (identity pack 1.4.0) -- one row per sign-in,
 * named by the {@code sid} claim every token minted for that sign-in carries.
 *
 * <p>Every method takes the composed table from {@link IdentityPackTableNames#userSessionsTable()} and
 * treats {@code null} as "sessions are off in this app" (an identity pack older than 1.4.0, e.g. a
 * private copy -- REG-39): {@link #open} answers {@code null} and the token is minted without a
 * {@code sid}, exactly as before. Opening a session never fails a sign-in: any database problem is
 * logged and the sign-in goes ahead without one.</p>
 *
 * <p>Revocation takes effect through the shared check in {@link IdentityRoleLookup#isSessionRevoked}
 * (cached for 30 s per node); {@link #revoke} also evicts this node's cached verdict so the node that
 * handled the request reflects it at once.</p>
 */
public final class UserSessionStore {

    private static final Logger LOG = Logger.getLogger(UserSessionStore.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";
    private static final String SESSION_COOKIE = "npdev_jwt";
    private static final int MAX_USER_AGENT = 300;
    private static final int MAX_IP = 64;

    private UserSessionStore() {
    }

    /** One listed session: the session id is NEVER returned whole, only its last six characters. */
    public record Listed(String sessionId, String userAgent, String ipAddress, Instant createdAt,
                         Instant lastSeenAt) {
        public String shortId() {
            return sessionId.length() <= 6 ? sessionId : sessionId.substring(sessionId.length() - 6);
        }
    }

    /**
     * Opens a session for {@code username}'s sign-in and returns its id, or {@code null} when sessions
     * are off or the row could not be written (the sign-in then proceeds without a {@code sid}).
     */
    public static String open(DataSource dataSource, IdentityPackTableNames tables, String tenantId,
                              String username, String userAgent, String ipAddress) {
        if (dataSource == null || tables == null || tables.userSessionsTable() == null
                || username == null || username.isBlank()) {
            return null;
        }
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = userIdOf(connection, tables, tenantId, username);
            if (userId == null) {
                return null;
            }
            String sessionId = UUID.randomUUID().toString().replace("-", "");
            Timestamp now = Timestamp.from(Instant.now());
            String sql = "INSERT INTO " + tables.userSessionsTable()
                    + " (id, tenant_id, session_id, user_id, created_at, last_seen_at, user_agent, ip_address)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, tenantId);
                ps.setString(3, sessionId);
                ps.setObject(4, userId);
                ps.setTimestamp(5, now);
                ps.setTimestamp(6, now);
                ps.setString(7, clip(userAgent, MAX_USER_AGENT));
                ps.setString(8, clip(ipAddress, MAX_IP));
                ps.executeUpdate();
            }
            return sessionId;
        } catch (SQLException | RuntimeException failure) {
            LOG.log(Level.WARNING, "Could not open a user session; signing in without a session id: "
                    + failure.getMessage());
            return null;
        }
    }

    /** The caller's non-revoked sessions, newest sign-in first. */
    public static List<Listed> list(DataSource dataSource, IdentityPackTableNames tables, String tenantId,
                                    String username) throws SQLException {
        List<Listed> out = new ArrayList<>();
        if (dataSource == null || tables == null || tables.userSessionsTable() == null) {
            return out;
        }
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = userIdOf(connection, tables, tenantId, username);
            if (userId == null) {
                return out;
            }
            String sql = "SELECT session_id, user_agent, ip_address, created_at, last_seen_at FROM "
                    + tables.userSessionsTable()
                    + " WHERE user_id = ? AND tenant_id = ? AND revoked_at IS NULL ORDER BY created_at DESC";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, userId);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Timestamp created = rs.getTimestamp("created_at");
                        Timestamp seen = rs.getTimestamp("last_seen_at");
                        out.add(new Listed(rs.getString("session_id"), rs.getString("user_agent"),
                                rs.getString("ip_address"),
                                created == null ? null : created.toInstant(),
                                seen == null ? null : seen.toInstant()));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Revokes one of {@code username}'s OWN sessions; {@code false} when there is none with that id for
     * this user (someone else's session, an unknown id, or already revoked) -- the caller answers 404.
     */
    public static boolean revokeOwned(DataSource dataSource, IdentityPackTableNames tables, String tenantId,
                                      String username, String sessionId) throws SQLException {
        if (dataSource == null || tables == null || tables.userSessionsTable() == null
                || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = userIdOf(connection, tables, tenantId, username);
            if (userId == null) {
                return false;
            }
            String sql = "UPDATE " + tables.userSessionsTable() + " SET revoked_at = ?"
                    + " WHERE session_id = ? AND user_id = ? AND tenant_id = ? AND revoked_at IS NULL";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setTimestamp(1, Timestamp.from(Instant.now()));
                ps.setString(2, sessionId);
                ps.setObject(3, userId);
                ps.setString(4, tenantId);
                boolean revoked = ps.executeUpdate() > 0;
                if (revoked) {
                    IdentityRoleLookup.forgetSession(tenantId, sessionId);
                }
                return revoked;
            }
        }
    }

    /** Outcome of {@link #revokeOwnedByHandle}. */
    public enum HandleResult { REVOKED, NOT_FOUND, AMBIGUOUS }

    /**
     * Revokes one of {@code username}'s OWN sessions named by the short handle {@link Listed#shortId()}
     * the list shows (the full id is never sent to the browser). {@code NOT_FOUND} covers someone else's
     * session, an unknown handle and an already-revoked one -- the caller answers 404 for all three.
     */
    public static HandleResult revokeOwnedByHandle(DataSource dataSource, IdentityPackTableNames tables,
                                                   String tenantId, String username, String handle)
            throws SQLException {
        if (handle == null || handle.length() < 4 || handle.length() > 6) {
            return HandleResult.NOT_FOUND;
        }
        List<String> matches = new ArrayList<>();
        for (Listed listed : list(dataSource, tables, tenantId, username)) {
            if (listed.sessionId().endsWith(handle)) {
                matches.add(listed.sessionId());
            }
        }
        if (matches.isEmpty()) {
            return HandleResult.NOT_FOUND;
        }
        if (matches.size() > 1) {
            return HandleResult.AMBIGUOUS;
        }
        return revokeOwned(dataSource, tables, tenantId, username, matches.get(0))
                ? HandleResult.REVOKED : HandleResult.NOT_FOUND;
    }

    /** Logout: ends the session a token names. No user lookup -- the sender of the token holds the id. */
    public static void revokeBySessionId(DataSource dataSource, IdentityPackTableNames tables, String tenantId,
                                         String sessionId) {
        if (dataSource == null || tables == null || tables.userSessionsTable() == null
                || sessionId == null || sessionId.isBlank()) {
            return;
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("UPDATE " + tables.userSessionsTable()
                     + " SET revoked_at = ? WHERE session_id = ? AND tenant_id = ? AND revoked_at IS NULL")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, sessionId);
            ps.setString(3, tenant);
            ps.executeUpdate();
            IdentityRoleLookup.forgetSession(tenant, sessionId);
        } catch (SQLException | RuntimeException failure) {
            LOG.log(Level.WARNING, "Could not end the user session on sign-out: " + failure.getMessage());
        }
    }

    /**
     * Revokes every session of {@code username} except {@code keepSessionId} (sign out of all other
     * devices, and password change). Best effort -- the caller has bumped token_version already, which
     * is what actually ends the other devices; this keeps the session list honest.
     */
    public static void revokeAllExcept(DataSource dataSource, IdentityPackTableNames tables, String tenantId,
                                       String username, String keepSessionId) {
        if (dataSource == null || tables == null || tables.userSessionsTable() == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = userIdOf(connection, tables, tenantId, username);
            if (userId == null) {
                return;
            }
            List<String> others = new ArrayList<>();
            String select = "SELECT session_id FROM " + tables.userSessionsTable()
                    + " WHERE user_id = ? AND tenant_id = ? AND revoked_at IS NULL";
            try (PreparedStatement ps = connection.prepareStatement(select)) {
                ps.setObject(1, userId);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String sid = rs.getString(1);
                        if (!sid.equals(keepSessionId)) {
                            others.add(sid);
                        }
                    }
                }
            }
            String update = "UPDATE " + tables.userSessionsTable() + " SET revoked_at = ?"
                    + " WHERE session_id = ? AND tenant_id = ? AND revoked_at IS NULL";
            Timestamp now = Timestamp.from(Instant.now());
            for (String sid : others) {
                try (PreparedStatement ps = connection.prepareStatement(update)) {
                    ps.setTimestamp(1, now);
                    ps.setString(2, sid);
                    ps.setString(3, tenantId);
                    ps.executeUpdate();
                }
                IdentityRoleLookup.forgetSession(tenantId, sid);
            }
        } catch (SQLException | RuntimeException failure) {
            LOG.log(Level.WARNING, "Could not revoke the other user sessions: " + failure.getMessage());
        }
    }

    private static UUID userIdOf(Connection connection, IdentityPackTableNames tables, String tenantId,
                                 String username) throws SQLException {
        String sql = "SELECT id FROM " + tables.usersTable() + " WHERE username = ? AND tenant_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? UUID.fromString(String.valueOf(rs.getObject(1))) : null;
            }
        }
    }

    // ----------------------------------------------------------------- reading the caller's sid

    /** The {@code sid} claim of the authenticated request ({@code null}: no session id, e.g. an old token). */
    public static String sessionIdOf(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object claims = request.getAttribute(CLAIMS_ATTRIBUTE);
        if (claims instanceof Map<?, ?> map && map.get("sid") != null) {
            String sid = String.valueOf(map.get("sid")).trim();
            return sid.isEmpty() ? null : sid;
        }
        return null;
    }

    /**
     * The session a logout request names, read from the token itself (bearer header or the session
     * cookie) WITHOUT verifying it: logout is exempt from authentication so it works with an expired
     * token, and the worst a forged token can do is end a session whose 128-bit id its sender already
     * holds. Returns {@code {tenantId, sid}} or {@code null}.
     */
    public static String[] sessionFromToken(HttpServletRequest request) {
        String token = null;
        String header = request == null ? null : request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = header.substring(7).trim();
        }
        if ((token == null || token.isEmpty()) && request != null && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (SESSION_COOKIE.equals(cookie.getName())) {
                    token = cookie.getValue();
                }
            }
        }
        if (token == null || token.isEmpty()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1].getBytes(StandardCharsets.UTF_8)));
            String sid = payload.path("sid").asText("");
            if (sid.isBlank()) {
                return null;
            }
            return new String[] {payload.path("tenant_id").asText(""), sid};
        } catch (Exception malformed) {
            return null;
        }
    }

    /** User agent and client address of the request in flight, for the session row (null-safe). */
    public static String[] clientOf(HttpServletRequest request) {
        if (request == null) {
            return new String[] {null, null};
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isBlank()
                ? (forwarded.contains(",") ? forwarded.substring(0, forwarded.indexOf(',')) : forwarded).trim()
                : request.getRemoteAddr();
        return new String[] {request.getHeader("User-Agent"), ip};
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
