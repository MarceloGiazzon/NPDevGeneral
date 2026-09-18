package com.finalexec.auth;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.kernel.ports.IdentityProvider;
import com.npdev.runtime.support.IdentityRoleLookup;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The account-linking semantics of the external identity-provider abstraction (SEC-11,
 * NPDEV_MEGA_ROADMAP.md Session 3b), on top of the existing JWT login machinery: resolves an
 * identity provider's verified claims against the identity pack's persisted affiliations and, on
 * any successful outcome, hands back the exact same {@code JwtSigner} inputs {@code LoginController}
 * would produce -- the user's own {@code token_version} included -- so session revocation via
 * {@code token_version} works for Google sessions exactly as it does for password sessions, with no
 * new revocation machinery.
 *
 * <p>Three flows, one method each:
 * <ul>
 *   <li>{@link #resolveAndSignIn}: an unauthenticated {@code login} callback. Looks up the provider
 *       subject on the {@code ExternalIdentity} linkage; if the user already linked, logs in.
 *       Otherwise a user whose email/username already exists with NO linkage is refused with
 *       {@code email_exists_requires_link} (the settled collision policy -- never auto-link, never
 *       silently take over); otherwise the user is created with the Google profile and linked.</li>
 *   <li>{@link #linkToAuthenticatedUser}: the authenticated {@code link} callback. Verifies the
 *       Google email matches the signed-in account's own email/username, then records the linkage.
 *       The existing session continues; no token is minted.</li>
 * </ul>
 *
 * <p>Deliberately provider-agnostic: the {@link IdentityProvider} is injected, so this service
 * knows "the provider" but never "Google". Table names come from the compiled model (pack-versioned
 * schema); the {@code identity::ExternalIdentity} concept is required and its absence is treated
 * like an unconfigured provider (a 503 for the caller), never a crash.
 */
public final class OAuthGoogleAuthService {

    /** Outcome of a callback, coarse enough for the controller, precise enough for the UI. */
    public enum Outcome {
        LOGIN,
        SIGNUP,
        /** Email-collision policy: an unlinked account already owns this email; explicit link required. */
        EMAIL_EXISTS_REQUIRES_LINK,
        PROVIDER_REFUSED,
        UNVERIFIED_OR_MISSING_EMAIL,
        /** Existing account is valid but disabled. */
        INACTIVE_USER,
        /** Link leg: the Google email does not belong to the signed-in account. */
        LINK_EMAIL_MISMATCH,
        /** Link leg: the Google subject is already linked to a DIFFERENT account. */
        LINK_ALREADY_USED,
        /** Link leg: the signed-in account cannot be resolved (should be unreachable for a valid JWT). */
        LINK_ACCOUNT_UNRESOLVED,
        /** Link leg success (the session itself is unchanged). */
        LINKED
    }

    /** Immutable ticket for the controller: like {@code LoginController}'s body, but only when login succeeded. */
    public record SessionTicket(
            Outcome outcome,
            String errorCode,
            String token,
            String tenantId,
            String username,
            Set<String> roles
    ) {
        static SessionTicket error(Outcome outcome, String errorCode) {
            return new SessionTicket(outcome, errorCode, null, null, null, Set.of());
        }
    }

    private final DataSource dataSource;
    private final ModelHolder modelHolder;
    private final IdentityProvider identityProvider;
    private final JwtSigner jwtSigner;

    public OAuthGoogleAuthService(
            DataSource dataSource,
            ModelHolder modelHolder,
            IdentityProvider identityProvider,
            JwtSigner jwtSigner
    ) {
        this.dataSource = dataSource;
        this.modelHolder = modelHolder;
        this.identityProvider = identityProvider;
        this.jwtSigner = jwtSigner;
    }

    /**
     * {@code login} callback leg: provider claims -> linkage lookup -> (found: LOGIN) /
     * (email claimed by an unlinked user: EMAIL_EXISTS_REQUIRES_LINK) / (nothing: SIGNUP).
     */
    public SessionTicket resolveAndSignIn(String code, String redirectUri, String tenantId) {
        Optional<IdentityProvider.IdentityProviderClaims> claims =
                identityProvider.resolveIdentity(code, redirectUri);
        if (claims.isEmpty()) {
            return SessionTicket.error(Outcome.PROVIDER_REFUSED, "oauth_provider_refused");
        }
        IdentityProvider.IdentityProviderClaims verified = claims.get();
        if (!isUsableEmail(verified)) {
            return SessionTicket.error(Outcome.UNVERIFIED_OR_MISSING_EMAIL, "oauth_unverified_email");
        }
        String normalizedTenant = (tenantId == null || tenantId.isBlank()) ? "dev" : tenantId.trim();

        Tables tables = Tables.resolve(modelHolder.get());
        if (tables == null || tables.externalIdentityTable() == null) {
            return SessionTicket.error(Outcome.PROVIDER_REFUSED, "identity_pack_not_composed");
        }

        Optional<String> linkedUserId = findLinkedUser(tables, verified.subject(), normalizedTenant);
        if (linkedUserId.isPresent()) {
            return signInExistingUser(tables, linkedUserId.get(), normalizedTenant);
        }

        Optional<ExistingUser> owner = findUserByEmailOrUsername(tables, verified.email(), normalizedTenant);
        if (owner.isPresent()) {
            // Collision policy (settled with the owner, SEC-11): never auto-link, never take over.
            // The account owner signs in with password and links Google explicitly.
            return SessionTicket.error(Outcome.EMAIL_EXISTS_REQUIRES_LINK, "email_exists_requires_link");
        }

        return signUp(tables, verified, normalizedTenant);
    }

    /**
     * {@code link} callback leg: the signed-in account (identified by its username, the JWT
     * subject) claims this Google identity. Records the linkage only after proving the Google
     * email is the account's own.
     */
    public SessionTicket linkToAuthenticatedUser(
            String code,
            String redirectUri,
            String tenantId,
            String actorUsername
    ) {
        Optional<IdentityProvider.IdentityProviderClaims> claims =
                identityProvider.resolveIdentity(code, redirectUri);
        if (claims.isEmpty()) {
            return SessionTicket.error(Outcome.PROVIDER_REFUSED, "oauth_provider_refused");
        }
        IdentityProvider.IdentityProviderClaims verified = claims.get();
        if (!isUsableEmail(verified)) {
            return SessionTicket.error(Outcome.UNVERIFIED_OR_MISSING_EMAIL, "oauth_unverified_email");
        }
        String normalizedTenant = (tenantId == null || tenantId.isBlank()) ? "dev" : tenantId.trim();
        if (actorUsername == null || actorUsername.isBlank()) {
            return SessionTicket.error(Outcome.LINK_ACCOUNT_UNRESOLVED, "oauth_link_account_unresolved");
        }

        Tables tables = Tables.resolve(modelHolder.get());
        if (tables == null || tables.externalIdentityTable() == null) {
            return SessionTicket.error(Outcome.PROVIDER_REFUSED, "identity_pack_not_composed");
        }

        Optional<ExistingUser> account = findUserByUsername(tables, actorUsername, normalizedTenant);
        if (account.isEmpty()) {
            return SessionTicket.error(Outcome.LINK_ACCOUNT_UNRESOLVED, "oauth_link_account_unresolved");
        }
        ExistingUser owner = account.get();
        if (!owner.active()) {
            return SessionTicket.error(Outcome.INACTIVE_USER, "oauth_inactive_user");
        }
        boolean emailMatches = verified.email().toLowerCase(Locale.ROOT).equals(
                nullSafeLower(owner.email(), ""));
        if (!emailMatches && !verified.email().toLowerCase(Locale.ROOT).equals(actorUsername.toLowerCase(Locale.ROOT))) {
            return SessionTicket.error(Outcome.LINK_EMAIL_MISMATCH, "oauth_link_email_mismatch");
        }

        Optional<String> existingLink = findLinkOwner(tables, verified.subject());
        if (existingLink.isPresent()) {
            if (existingLink.get().equals(owner.id())) {
                return SessionTicket.error(Outcome.LINKED, "oauth_already_linked");
            }
            return SessionTicket.error(Outcome.LINK_ALREADY_USED, "oauth_link_already_used");
        }

        if (!insertLink(tables, owner.id(), verified.subject(), normalizedTenant)) {
            return SessionTicket.error(Outcome.LINK_ALREADY_USED, "oauth_link_already_used");
        }
        return SessionTicket.error(Outcome.LINKED, null);
    }

    // ---------------------------------------------------------------- login/signup internals

    private SessionTicket signInExistingUser(Tables tables, String userId, String tenantId) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, username, active, token_version FROM " + tables.usersTable()
                            + " WHERE id = ? AND tenant_id = ?")) {
                ps.setObject(1, UUID.fromString(userId));
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return SessionTicket.error(Outcome.PROVIDER_REFUSED, "oauth_provider_refused");
                    }
                    if (!rs.getBoolean("active")) {
                        return SessionTicket.error(Outcome.INACTIVE_USER, "oauth_inactive_user");
                    }
                    String username = rs.getString("username");
                    int tokenVersion = rs.getInt("token_version");
                    if (rs.wasNull()) {
                        tokenVersion = 0;
                    }
                    touchLastLogin(connection, tables.usersTable(), userId);
                    return mint(tenantId, username, tokenVersion);
                }
            }
        } catch (SQLException schemaCandidate) {
            // REG-39's rule: a stale pack copy must surface as a schema error, not as a generic
            // auth failure -- same posture as LoginController's identical catch.
            return SessionTicket.error(Outcome.PROVIDER_REFUSED, "identity_pack_schema_error");
        }
    }

    private SessionTicket signUp(Tables tables, IdentityProvider.IdentityProviderClaims claims, String tenantId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String userId = UUID.randomUUID().toString();
                String username = claims.email();
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO " + tables.usersTable()
                                + " (id, tenant_id, username, display_name, email, active, token_version,"
                                + " avatar_url, last_login_at, created_at, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setObject(1, UUID.fromString(userId));
                    ps.setString(2, tenantId);
                    ps.setString(3, username);
                    ps.setString(4, displayName(claims, username));
                    ps.setString(5, claims.email());
                    ps.setBoolean(6, true);
                    ps.setInt(7, 0);
                    if (claims.avatarUrl() == null || claims.avatarUrl().isBlank()) {
                        ps.setNull(8, Types.VARCHAR);
                    } else {
                        ps.setString(8, claims.avatarUrl());
                    }
                    ps.setTimestamp(9, Timestamp.from(Instant.now()));
                    ps.setTimestamp(10, Timestamp.from(Instant.now()));
                    ps.setTimestamp(11, Timestamp.from(Instant.now()));
                    ps.executeUpdate();
                }
                insertLink(connection, tables, userId, claims.subject(), tenantId);
                connection.commit();
                SessionTicket minted = mint(tenantId, username, 0);
                return new SessionTicket(Outcome.SIGNUP, null, minted.token(), minted.tenantId(),
                        minted.username(), minted.roles());
            } catch (SQLException ex) {
                connection.rollback();
                // 23505 = unique constraint violation: a concurrent signup (or a cross-tenant
                // username collision) landed between our check and the insert. Refuse as the
                // collision always refuses, regardless of which unique index fired.
                if ("23505".equals(ex.getSQLState())) {
                    return SessionTicket.error(Outcome.EMAIL_EXISTS_REQUIRES_LINK, "email_exists_requires_link");
                }
                if (SqlSchemaErrors.isSchemaMismatch(ex)) {
                    return SessionTicket.error(Outcome.PROVIDER_REFUSED, "identity_pack_schema_error");
                }
                throw ex;
            }
        } catch (SQLException schemaCandidate) {
            // Same REG-39 posture as LoginController: never report a stale schema as credentials.
            if (SqlSchemaErrors.isSchemaMismatch(schemaCandidate)) {
                return SessionTicket.error(Outcome.PROVIDER_REFUSED, "identity_pack_schema_error");
            }
            return SessionTicket.error(Outcome.PROVIDER_REFUSED, "oauth_provider_refused");
        }
    }

    private SessionTicket mint(String tenantId, String username, int tokenVersion) {
        Set<String> roles = IdentityPackTableNames.tryResolve(modelHolder.get())
                .map(tables -> IdentityRoleLookup.rolesFor(dataSource, tables, tenantId, username))
                .orElseGet(Set::of);
        String token = jwtSigner.sign(tenantId, username, roles, tokenVersion);
        return new SessionTicket(Outcome.LOGIN, null, token, tenantId, username, roles);
    }

    // ---------------------------------------------------------------- lookup helpers

    private Optional<String> findLinkedUser(Tables tables, String subject, String tenantId) {
        Optional<String> userId = findLinkOwner(tables, subject);
        if (userId.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM " + tables.usersTable() + " WHERE id = ? AND tenant_id = ?")) {
                ps.setObject(1, UUID.fromString(userId.get()));
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? userId : Optional.empty();
                }
            }
        } catch (SQLException ex) {
            return Optional.empty();
        }
    }

    private Optional<String> findLinkOwner(Tables tables, String subject) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT user_id FROM " + tables.externalIdentityTable()
                            + " WHERE provider = ? AND provider_subject = ?")) {
                ps.setString(1, identityProvider.providerId());
                ps.setString(2, subject);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString("user_id")) : Optional.empty();
                }
            }
        } catch (SQLException ex) {
            return Optional.empty();
        }
    }

    private record ExistingUser(String id, String username, String email, boolean active) {
    }

    private Optional<ExistingUser> findUserByEmailOrUsername(Tables tables, String email, String tenantId) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, username, email, active FROM " + tables.usersTable()
                            + " WHERE tenant_id = ? AND (LOWER(username) = ? OR LOWER(email) = ?)")) {
                ps.setString(1, tenantId);
                ps.setString(2, email.toLowerCase(Locale.ROOT));
                ps.setString(3, email.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new ExistingUser(
                                    rs.getString("id"),
                                    rs.getString("username"),
                                    rs.getString("email"),
                                    rs.getBoolean("active")))
                            : Optional.empty();
                }
            }
        } catch (SQLException ex) {
            // REG-39 posture: never treat the identity pack's own query failure as "no user".
            if (SqlSchemaErrors.isSchemaMismatch(ex)) {
                throw new IllegalStateException("identity pack schema mismatch while resolving external identity", ex);
            }
            return Optional.empty();
        }
    }

    private Optional<ExistingUser> findUserByUsername(Tables tables, String username, String tenantId) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, username, email, active FROM " + tables.usersTable()
                            + " WHERE username = ? AND tenant_id = ?")) {
                ps.setString(1, username);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new ExistingUser(
                                    rs.getString("id"),
                                    rs.getString("username"),
                                    rs.getString("email"),
                                    rs.getBoolean("active")))
                            : Optional.empty();
                }
            }
        } catch (SQLException ex) {
            if (SqlSchemaErrors.isSchemaMismatch(ex)) {
                throw new IllegalStateException("identity pack schema mismatch while resolving external identity", ex);
            }
            return Optional.empty();
        }
    }

    private boolean insertLink(Tables tables, String userId, String subject, String tenantId) {
        try (Connection connection = dataSource.getConnection()) {
            return insertLink(connection, tables, userId, subject, tenantId);
        } catch (SQLException ex) {
            return false;
        }
    }

    private boolean insertLink(Connection connection, Tables tables, String userId, String subject, String tenantId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO " + tables.externalIdentityTable()
                        + " (id, tenant_id, user_id, provider, provider_subject, linked_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, tenantId == null || tenantId.isBlank() ? "dev" : tenantId.trim());
            ps.setObject(3, UUID.fromString(userId));
            ps.setString(4, identityProvider.providerId());
            ps.setString(5, subject);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
            return true;
        }
    }

    private static void touchLastLogin(Connection connection, String usersTable, String userId) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + usersTable + " SET last_login_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setObject(2, UUID.fromString(userId));
            ps.executeUpdate();
        } catch (SQLException ignored) {
            // Best-effort bookkeeping; a failure here must not fail a successful login.
        }
    }

    private static boolean isUsableEmail(IdentityProvider.IdentityProviderClaims claims) {
        return claims.emailVerified()
                && claims.email() != null
                && !claims.email().isBlank();
    }

    private static String displayName(IdentityProvider.IdentityProviderClaims claims, String fallbackUsername) {
        if (claims.displayName() != null && !claims.displayName().isBlank()) {
            return claims.displayName();
        }
        int at = fallbackUsername.indexOf('@');
        return at > 0 ? fallbackUsername.substring(0, at) : fallbackUsername;
    }

    private static String nullSafeLower(String value, String fallback) {
        return value == null ? fallback : value.toLowerCase(Locale.ROOT);
    }

    /** Compiled-model table resolution for the two identity-pack concepts this service touches. */
    private record Tables(String usersTable, String externalIdentityTable) {

        static Tables resolve(CompiledModel compiledModel) {
            String users = tableName(compiledModel, "identity::User");
            String external = tableName(compiledModel, "identity::ExternalIdentity");
            if (users == null) {
                return null;
            }
            return new Tables(users, external);
        }

        private static String tableName(CompiledModel compiledModel, String conceptName) {
            return compiledModel.findConcept(conceptName)
                    .map(CompiledConcept::getTableName)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(null);
        }
    }
}