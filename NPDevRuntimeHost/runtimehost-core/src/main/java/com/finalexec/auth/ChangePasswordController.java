package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import com.npdev.runtime.support.IdentityRoleLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.security.PrivateKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WMS-9 N6: logged-in self-service "change my password" -- {@link PasswordResetController} is a
 * logged-OUT forgot-password/reset-token flow (no session, a mailed token stands in for identity);
 * this is its counterpart for a caller who is ALREADY authenticated and simply wants to change their
 * own password, taking the current password instead of a token. Mirrors {@link LoginController}'s
 * wiring (same credential-table properties, same JWT signing key) and reuses its exact
 * user-lookup/credential-lookup query shapes rather than inventing a second convention.
 *
 * <p>{@code JwtBearerAuthFilter} already rejects a stale token_version before this method runs, so
 * the caller here is guaranteed to be the account named by the token's own subject claim -- no
 * separate re-authentication step is needed, only the OLD PASSWORD check that makes this a genuine
 * "prove you still know it" change rather than an unauthenticated takeover of an already-open
 * session. A successful change bumps token_version (invalidating every OTHER outstanding session,
 * same mechanism {@link PasswordResetController#confirmReset} and the admin revoke-sessions endpoint
 * already use) and mints a FRESH token for the caller's own response, so changing your password does
 * not also log you out of the request that just changed it.</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class ChangePasswordController {

    private static final Logger LOG = Logger.getLogger(ChangePasswordController.class.getName());
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** Mirrors the generated {@code RuntimeApiKeyAuthFilter.CLAIMS_ATTRIBUTE} constant's string
     * value (npdev-runtime-api-key-auth-filter.mustache) -- this class lives in runtimehost-core,
     * which (unlike the top-level RuntimeHost module AggregateApiController lives in) cannot depend
     * on the per-app GENERATED RuntimeApiKeyAuthFilter/RuntimeContextService classes, only on real
     * platform packages. A request attribute is looked up by string name regardless, so no
     * compile-time dependency on the generated class is needed to read the SAME value it wrote. */
    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";

    private final DataSource dataSource;
    private final AuthenticatedContextResolver authenticatedContextResolver;
    private final ObjectMapper objectMapper;
    private final ModelHolder modelHolder;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final PrivateKey privateKey;
    private final String issuer;
    private final String audience;
    private final long expirySeconds;

    @Autowired
    public ChangePasswordController(
            DataSource dataSource,
            AuthenticatedContextResolver authenticatedContextResolver,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            ModelHolder modelHolder,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn,
            @Value("${npdev.auth.jwt.private-key-path:}") String privateKeyPath,
            @Value("${npdev.auth.jwt.issuer:}") String issuer,
            @Value("${npdev.auth.jwt.audience:}") String audience,
            @Value("${npdev.auth.jwt.expiry-seconds:28800}") long expirySeconds
    ) throws Exception {
        this.dataSource = dataSource;
        this.authenticatedContextResolver = authenticatedContextResolver;
        this.objectMapper = objectMapper;
        this.modelHolder = modelHolder;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
        this.privateKey = (privateKeyPath == null || privateKeyPath.isBlank())
                ? null
                : JwtSigner.loadPrivateKey(LoginController.readKeyFile(resourceLoader, privateKeyPath));
        this.issuer = issuer;
        this.audience = audience;
        this.expirySeconds = expirySeconds;
    }

    public record ChangePasswordRequest(String oldPassword, String newPassword) {
    }

    @PostMapping("/api/auth/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        ExecutionContext callerContext = currentContext(httpRequest);
        String tenantId = callerContext == null || callerContext.tenantId() == null || callerContext.tenantId().isBlank()
                ? "dev" : callerContext.tenantId().trim();
        String username = callerContext == null ? null : callerContext.actorId();

        if (username == null || username.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthenticated"));
        }

        String oldPassword = request == null ? null : request.oldPassword();
        String newPassword = request == null ? null : request.newPassword();
        if (oldPassword == null || oldPassword.isBlank()
                || newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
        }

        if (privateKey == null) {
            // Mirrors LoginController: a verify-only deployment has no signing key, so it cannot
            // mint the fresh token this endpoint returns on success.
            return ResponseEntity.status(503).body(Map.of(
                    "error", "token_issuance_unavailable",
                    "detail", "This deployment validates externally-issued JWTs only; no signing key "
                            + "(npdev.auth.jwt.private-key-path) is configured, so /api/auth/change-password "
                            + "cannot mint a replacement token."));
        }

        Optional<IdentityPackTableNames> resolvedIdentityTables = IdentityPackTableNames.tryResolve(modelHolder.get());
        if (resolvedIdentityTables.isEmpty()) {
            return ResponseEntity.status(503).body(Map.of("error", "identity_pack_not_composed"));
        }
        IdentityPackTableNames identityTables = resolvedIdentityTables.get();

        try (Connection connection = dataSource.getConnection()) {
            String userSql = "SELECT id, active, token_version FROM " + identityTables.usersTable()
                    + " WHERE username = ? AND tenant_id = ?";
            String userId;
            int tokenVersion;
            try (PreparedStatement ps = connection.prepareStatement(userSql)) {
                ps.setString(1, username);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean("active")) {
                        return ResponseEntity.status(401).body(Map.of("error", "unauthenticated"));
                    }
                    userId = rs.getString("id");
                    tokenVersion = rs.getInt("token_version");
                    if (rs.wasNull()) {
                        tokenVersion = 0;
                    }
                }
            }

            String credentialSql = "SELECT " + credentialPasswordColumn + " FROM " + credentialTable
                    + " WHERE " + credentialUserIdColumn + " = ? AND tenant_id = ?";
            String storedHash;
            try (PreparedStatement ps = connection.prepareStatement(credentialSql)) {
                ps.setObject(1, java.util.UUID.fromString(userId));
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return ResponseEntity.status(500).body(Map.of("error", "credential_row_missing"));
                    }
                    storedHash = rs.getString(1);
                }
            }

            if (!PasswordHasher.verify(oldPassword, storedHash)) {
                return ResponseEntity.status(400).body(Map.of("error", "invalid_old_password"));
            }

            String updateCredentialSql = "UPDATE " + credentialTable + " SET " + credentialPasswordColumn + " = ?"
                    + " WHERE " + credentialUserIdColumn + " = ? AND tenant_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(updateCredentialSql)) {
                ps.setString(1, PasswordHasher.hash(newPassword));
                ps.setObject(2, java.util.UUID.fromString(userId));
                ps.setString(3, tenantId);
                ps.executeUpdate();
            }

            int newTokenVersion = tokenVersion + 1;
            bumpTokenVersionTo(connection, identityTables.usersTable(), userId, tenantId, newTokenVersion);

            Set<String> roles = IdentityRoleLookup.rolesFor(dataSource, identityTables, tenantId, username);
            JwtSigner signer = new JwtSigner(objectMapper, privateKey, issuer, audience, expirySeconds);
            String token = signer.sign(tenantId, username, roles, newTokenVersion);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("token", token);
            body.put("tokenType", "Bearer");
            body.put("expiresInSeconds", expirySeconds);
            body.put("roles", roles);
            return ResponseEntity.ok(body);
        } catch (IdentityPackSchemaException schemaException) {
            // REG-39 shape (same as PasswordResetController.confirmReset): the credential update
            // above already committed, so the password DID change -- but failing to report the
            // revocation gap as a schema error would mask a stale identity-pack copy.
            LOG.log(Level.SEVERE, "Change password: identity pack schema mismatch bumping token_version",
                    schemaException);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "identity_pack_schema_error",
                    "detail", "Password changed but session revocation failed because this app's "
                            + "identity pack copy is out of date. See "
                            + "docs/CONFIGURATION.md#identity-pack-freshness-checked-at-boot. Cause: "
                            + schemaException.getMessage()));
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Change password failed", exception);
            return ResponseEntity.status(500).body(Map.of("error", "password_change_failed"));
        }
    }

    /** Resolves the CURRENT caller's identity from the claims the JWT (or api-key) auth filter
     * already stashed on the request -- the same mechanism the generated
     * RuntimeContextService.currentContext uses, reimplemented here without a compile-time
     * dependency on that generated class (see {@link #CLAIMS_ATTRIBUTE}'s own javadoc). Returns
     * null when no claims are present (unauthenticated), same contract the generated version
     * enforces via a thrown 401 -- the caller here checks null itself instead. */
    private ExecutionContext currentContext(HttpServletRequest request) {
        Object rawClaims = request.getAttribute(CLAIMS_ATTRIBUTE);
        if (!(rawClaims instanceof Map<?, ?> rawMap)) {
            return null;
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null) {
                claims.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        if (claims.isEmpty()) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith("x-tag-")) {
                    headers.put(name, request.getHeader(name));
                }
            }
        }
        return authenticatedContextResolver.resolveFromPrincipal(claims, headers);
    }

    /** Same shape as {@code PasswordResetController.bumpTokenVersion}, but sets an EXACT value
     * (not COALESCE+1) since the caller already read the current value in the same transaction and
     * needs to know the precise new value to mint this response's own replacement token with. Takes
     * the RESOLVED users-table name (identityTables.usersTable()), not a hardcoded "identity_users"
     * literal -- confirmed live (2026-09-19) that the real table name is versioned/pack-specific and
     * a hardcoded literal here fails with a table-not-found "schema mismatch" on every real app,
     * even though the SAME literal happens to match every hermetic test's own fixture schema. See
     * REG-226: PasswordResetController.bumpTokenVersion has this exact same hardcoded-literal bug,
     * not fixed here (out of scope for this change) but recorded for whoever picks it up. */
    private void bumpTokenVersionTo(Connection connection, String usersTable, String userId, String tenantId, int newTokenVersion) {
        String sql = "UPDATE " + usersTable + " SET token_version = ? WHERE id = ? AND tenant_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, newTokenVersion);
            ps.setObject(2, java.util.UUID.fromString(userId));
            ps.setString(3, tenantId);
            ps.executeUpdate();
        } catch (SQLException exception) {
            if (SqlSchemaErrors.isSchemaMismatch(exception)) {
                throw new IdentityPackSchemaException(exception);
            }
        }
    }
}
