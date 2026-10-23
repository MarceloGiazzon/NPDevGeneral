package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.IdentityPackTableNames;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Resolves the roles assigned to an actor in the built-in identity pack, tenant-scoped.
 *
 * <p>Shared so that BOTH context-resolution paths apply identity-backed roles uniformly: the
 * RuntimeHost {@code IdentityAwareContextResolver} (admin / business-UI controllers) and
 * {@link GeneratedCrudRuntimeSupport}'s own per-request context (generated business CRUD permission
 * checks). Returns an empty set -- meaning "no identity backing, keep the caller's claim-roles" --
 * when the actor is unknown, the user is inactive, the tenant doesn't match, or the identity tables
 * are absent ({@code internal.tables=false}). Never throws.</p>
 *
 * <p>REG-177: table names are now caller-supplied (an {@link IdentityPackTableNames}, resolved ONCE
 * by each caller from its own already-available {@code CompiledModel}) instead of hardcoded literals
 * ({@code identity_users}/{@code identity_roles}/{@code identity_user_roles}) -- the generator's
 * schema-realization SQL creates these under pack-versioned names (e.g. {@code identity_v1_users}),
 * the same defect shape REG-160/REG-170 already fixed elsewhere. This class cannot resolve the
 * table names itself: it is a {@code NPDevKernel} adapter and has no {@code CompiledModel} of its
 * own to query, only whatever the caller passes in.</p>
 */
public final class IdentityRoleLookup {

    private static final Logger LOG = Logger.getLogger(IdentityRoleLookup.class.getName());

    private static final String ROLE_QUERY_TEMPLATE = """
            SELECT r.name
            FROM %s u
            JOIN %s ur ON ur.user_id = u.id
            JOIN %s r ON r.id = ur.role_id
            WHERE u.username = ? AND u.tenant_id = ? AND u.active = TRUE
              AND ur.tenant_id = ? AND r.tenant_id = ?
            """;

    private static final String TOKEN_VERSION_QUERY_TEMPLATE = """
            SELECT token_version FROM %s WHERE username = ? AND tenant_id = ? AND active = TRUE
            """;

    private IdentityRoleLookup() {
    }

    /**
     * LNCH-4: the actor's current {@code token_version} from the identity pack -- the revocation
     * counter a minted JWT's {@code tv} claim is checked against on every request (see
     * {@code IdentityAwareContextResolver} / {@code GeneratedCrudRuntimeSupport}). Incrementing this
     * column (password reset, an explicit "revoke sessions" admin action) invalidates every token
     * minted before the increment, without a growing denylist table to prune.
     *
     * <p>Returns {@code 0} (never blocks) when the actor is unknown, inactive, the identity tables are
     * absent, or the column itself is NULL (pre-migration rows) -- a caller with a token minted before
     * this feature existed (no {@code tv} claim) is never affected; a caller with a {@code tv} claim
     * only fails the comparison once the stored version has genuinely been bumped past it.</p>
     */
    public static int tokenVersion(DataSource dataSource, IdentityPackTableNames tables, String tenantId, String actorId) {
        if (dataSource == null || tables == null || actorId == null || actorId.isBlank()) {
            return 0;
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        String query = TOKEN_VERSION_QUERY_TEMPLATE.formatted(tables.usersTable());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, actorId);
            statement.setString(2, tenant);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return 0;
                }
                int version = resultSet.getInt(1);
                return resultSet.wasNull() ? 0 : version;
            }
        } catch (SQLException exception) {
            // REG-39: this method's "never throws" contract stays (it's on the hot per-request
            // claim-to-context path, both here and in GeneratedCrudRuntimeSupport, so making it throw
            // would ripple into every caller) -- but a schema mismatch (stale built-in-pack copy
            // missing token_version) must not fail SILENTLY the way it did for WmsOffice. Log it
            // loudly; StartupValidator already fails the app at boot for this specific case, so
            // reaching here at all means that check was bypassed or the drift is in a column it
            // doesn't yet cover.
            if (isSchemaMismatch(exception)) {
                LOG.severe("token_version lookup failed: identity pack schema mismatch (see "
                        + "docs/CONFIGURATION.md#identity-pack-freshness-checked-at-boot) -- "
                        + exception.getMessage());
            }
            return 0;
        }
    }

    private static boolean isSchemaMismatch(SQLException exception) {
        String state = exception.getSQLState();
        return state != null && state.startsWith("42");
    }

    /** REG-23: config property (JVM system property, bridged from Spring by RuntimeHost at boot) — an
     * ISO-8601 instant after which legacy tokens with NO {@code tv} claim are rejected. Unset/blank =
     * today's lenient behavior. */
    public static final String REJECT_TVLESS_AFTER_PROPERTY = "npdev.auth.jwt.reject-tokens-without-tv-after";

    /** REG-291: the only {@code tv} claim shape {@link #isTokenRevoked} accepts -- an int that cannot overflow. */
    private static final java.util.regex.Pattern TOKEN_VERSION_CLAIM = java.util.regex.Pattern.compile("-?\\d{1,9}");

    /**
     * REG-23: THE single revocation decision point, called by BOTH claim->context paths (RuntimeHost
     * {@code IdentityAwareContextResolver} and {@link GeneratedCrudRuntimeSupport}) so they can never
     * diverge. A token WITH a {@code tv} claim is revoked when its version no longer matches the stored
     * {@code token_version}. A token WITHOUT a {@code tv} claim (legacy, pre-{@code tv}) is NOT revoked
     * -- backward compatible -- UNTIL the operator sets {@link #REJECT_TVLESS_AFTER_PROPERTY} to a past
     * instant (chosen {@code >= } the max token lifetime after {@code tv} shipped, so no legitimate
     * tv-less token can still exist), after which every tv-less token is rejected. A {@code tv} claim
     * that is present but not an integer is revoked (REG-291). Never throws; a
     * malformed cutover fails OPEN (treated as unset) — {@code StartupValidator} rejects a malformed
     * value at boot so it never reaches here.
     */
    public static boolean isTokenRevoked(
            Object rawTokenVersion, DataSource dataSource, IdentityPackTableNames tables, String tenantId, String actorId) {
        if (rawTokenVersion == null) {
            return rejectTvlessTokensNow();
        }
        String claimedText = String.valueOf(rawTokenVersion);
        if (!TOKEN_VERSION_CLAIM.matcher(claimedText).matches()) {
            // REG-291/X0: a tv claim that is present but not an integer is not a version this
            // platform minted (JwtSigner writes an int, Jackson reads it back as one), so it cannot
            // match the stored token_version -- the token is revoked, never waved through.
            return true;
        }
        int claimedVersion = Integer.parseInt(claimedText);
        return claimedVersion != tokenVersion(dataSource, tables, tenantId, actorId);
    }

    // ---------------------------------------------------------------------------------------------
    // SESS-1: per-device sessions. A token carrying a `sid` claim is refused once its
    // identity::UserSession row is revoked. Same shared-decision-point rule as isTokenRevoked: BOTH
    // claim->context paths call this, so they cannot diverge.
    // ---------------------------------------------------------------------------------------------

    /** How long a "not revoked" answer is trusted -- the documented upper bound on how long a revoked
     *  session keeps working. A revoked answer is cached for the same time; either way one request in
     *  30 s per session costs a query, not every request. */
    static final long SESSION_CACHE_MILLIS = 30_000L;
    /** last_seen_at is refreshed at most this often per session. */
    static final long SESSION_TOUCH_MILLIS = 5 * 60_000L;
    private static final int SESSION_CACHE_MAX = 10_000;

    private record SessionVerdict(boolean revoked, long checkedAtMillis) {
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, SessionVerdict> SESSION_VERDICTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Evicts one session's cached verdict on THIS node, so a revoke made here shows at once here
     *  (other nodes converge within {@value #SESSION_CACHE_MILLIS} ms). */
    public static void forgetSession(String tenantId, String sessionId) {
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        SESSION_VERDICTS.remove(tenant + "|" + sessionId);
    }

    /** For tests: forget every cached verdict. */
    public static void clearSessionCache() {
        SESSION_VERDICTS.clear();
    }

    /**
     * True when {@code rawSessionId} names a session of this tenant whose {@code revoked_at} is set.
     * A token with no {@code sid} (minted before sessions), an app without the sessions table, a sid
     * with no row, or any database error all answer {@code false}: the feature degrades to "off" and
     * never turns into a sign-in outage (a revoked-by-tv token is still refused by {@link #isTokenRevoked}).
     * Also refreshes {@code last_seen_at}, at most once per {@value #SESSION_TOUCH_MILLIS} ms.
     */
    public static boolean isSessionRevoked(
            Object rawSessionId, DataSource dataSource, IdentityPackTableNames tables, String tenantId) {
        if (rawSessionId == null || dataSource == null || tables == null || tables.userSessionsTable() == null) {
            return false;
        }
        String sid = String.valueOf(rawSessionId).trim();
        if (sid.isEmpty()) {
            return false;
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        String key = tenant + "|" + sid;
        long now = System.currentTimeMillis();
        SessionVerdict cached = SESSION_VERDICTS.get(key);
        if (cached != null && now - cached.checkedAtMillis() < SESSION_CACHE_MILLIS) {
            return cached.revoked();
        }
        boolean revoked = false;
        try (Connection connection = dataSource.getConnection()) {
            java.sql.Timestamp revokedAt = null;
            java.sql.Timestamp lastSeen = null;
            boolean found = false;
            String select = "SELECT revoked_at, last_seen_at FROM " + tables.userSessionsTable()
                    + " WHERE session_id = ? AND tenant_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                statement.setString(1, sid);
                statement.setString(2, tenant);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        found = true;
                        revokedAt = resultSet.getTimestamp(1);
                        lastSeen = resultSet.getTimestamp(2);
                    }
                }
            }
            revoked = found && revokedAt != null;
            if (found && !revoked && (lastSeen == null || now - lastSeen.getTime() >= SESSION_TOUCH_MILLIS)) {
                String update = "UPDATE " + tables.userSessionsTable()
                        + " SET last_seen_at = ? WHERE session_id = ? AND tenant_id = ?";
                try (PreparedStatement statement = connection.prepareStatement(update)) {
                    statement.setTimestamp(1, new java.sql.Timestamp(now));
                    statement.setString(2, sid);
                    statement.setString(3, tenant);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException unavailable) {
            // A session table that cannot be read (stale private identity pack, transient DB error) must
            // not lock everyone out; the token_version check above still guards the account.
            LOG.fine("session revocation lookup unavailable: " + unavailable.getMessage());
            return false;
        }
        if (SESSION_VERDICTS.size() >= SESSION_CACHE_MAX) {
            SESSION_VERDICTS.clear();
        }
        SESSION_VERDICTS.put(key, new SessionVerdict(revoked, now));
        return revoked;
    }

    private static boolean rejectTvlessTokensNow() {
        String configured = System.getProperty(REJECT_TVLESS_AFTER_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return false;
        }
        try {
            java.time.Instant cutover = java.time.Instant.parse(configured.trim());
            return !java.time.Instant.now().isBefore(cutover);
        } catch (java.time.format.DateTimeParseException malformed) {
            return false;
        }
    }

    public static Set<String> rolesFor(DataSource dataSource, IdentityPackTableNames tables, String tenantId, String actorId) {
        if (dataSource == null || tables == null || actorId == null || actorId.isBlank()) {
            return Set.of();
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        String query = ROLE_QUERY_TEMPLATE.formatted(tables.usersTable(), tables.userRolesTable(), tables.rolesTable());
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setString(1, actorId);
            statement.setString(2, tenant);
            statement.setString(3, tenant);
            statement.setString(4, tenant);
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> roles = new LinkedHashSet<>();
                while (resultSet.next()) {
                    String role = resultSet.getString(1);
                    if (role != null && !role.isBlank()) {
                        roles.add(role.trim());
                    }
                }
                return roles;
            }
        } catch (SQLException exception) {
            return Set.of();
        }
    }
}
