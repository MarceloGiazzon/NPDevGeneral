package com.npdev.runtime.support;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resolves the roles assigned to an actor in the built-in identity pack
 * (identity_users / identity_roles / identity_user_roles), tenant-scoped.
 *
 * <p>Shared so that BOTH context-resolution paths apply identity-backed roles uniformly: the
 * RuntimeHost {@code IdentityAwareContextResolver} (admin / business-UI controllers) and
 * {@link GeneratedCrudRuntimeSupport}'s own per-request context (generated business CRUD permission
 * checks). Returns an empty set -- meaning "no identity backing, keep the caller's claim-roles" --
 * when the actor is unknown, the user is inactive, the tenant doesn't match, or the identity tables
 * are absent ({@code internal.tables=false}). Never throws.</p>
 */
public final class IdentityRoleLookup {

    private static final String ROLE_QUERY = """
            SELECT r.name
            FROM identity_users u
            JOIN identity_user_roles ur ON ur.user_id = u.id
            JOIN identity_roles r ON r.id = ur.role_id
            WHERE u.username = ? AND u.tenant_id = ? AND u.active = TRUE
              AND ur.tenant_id = ? AND r.tenant_id = ?
            """;

    private static final String TOKEN_VERSION_QUERY = """
            SELECT token_version FROM identity_users WHERE username = ? AND tenant_id = ? AND active = TRUE
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
    public static int tokenVersion(DataSource dataSource, String tenantId, String actorId) {
        if (dataSource == null || actorId == null || actorId.isBlank()) {
            return 0;
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(TOKEN_VERSION_QUERY)) {
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
            return 0;
        }
    }

    /** REG-23: config property (JVM system property, bridged from Spring by RuntimeHost at boot) — an
     * ISO-8601 instant after which legacy tokens with NO {@code tv} claim are rejected. Unset/blank =
     * today's lenient behavior. */
    public static final String REJECT_TVLESS_AFTER_PROPERTY = "npdev.auth.jwt.reject-tokens-without-tv-after";

    /**
     * REG-23: THE single revocation decision point, called by BOTH claim->context paths (RuntimeHost
     * {@code IdentityAwareContextResolver} and {@link GeneratedCrudRuntimeSupport}) so they can never
     * diverge. A token WITH a {@code tv} claim is revoked when its version no longer matches the stored
     * {@code token_version}. A token WITHOUT a {@code tv} claim (legacy, pre-{@code tv}) is NOT revoked
     * -- backward compatible -- UNTIL the operator sets {@link #REJECT_TVLESS_AFTER_PROPERTY} to a past
     * instant (chosen {@code >= } the max token lifetime after {@code tv} shipped, so no legitimate
     * tv-less token can still exist), after which every tv-less token is rejected. Never throws; a
     * malformed cutover fails OPEN (treated as unset) — {@code StartupValidator} rejects a malformed
     * value at boot so it never reaches here.
     */
    public static boolean isTokenRevoked(Object rawTokenVersion, DataSource dataSource, String tenantId, String actorId) {
        if (rawTokenVersion == null) {
            return rejectTvlessTokensNow();
        }
        int claimedVersion;
        try {
            claimedVersion = Integer.parseInt(String.valueOf(rawTokenVersion));
        } catch (NumberFormatException malformed) {
            return false;
        }
        return claimedVersion != tokenVersion(dataSource, tenantId, actorId);
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

    public static Set<String> rolesFor(DataSource dataSource, String tenantId, String actorId) {
        if (dataSource == null || actorId == null || actorId.isBlank()) {
            return Set.of();
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ROLE_QUERY)) {
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
