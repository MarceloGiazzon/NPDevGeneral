package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.runtime.support.IdentityRoleLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Real login for the built-in identity pack (identity_users/identity_roles/identity_user_roles).
 * Bridges an app's own credential-holding concept (e.g. WmsOffice's {@code Usuario}, bonded to
 * {@code identity::User} via a {@code userId} reference) to the platform's already-working JWT
 * validation ({@link JwtBearerAuthFilter}) and role-based permission enforcement
 * ({@link IdentityAwareContextResolver}/{@link IdentityRoleLookup}) -- neither of which had any
 * mint-side counterpart before this class.
 *
 * <p>The credential table/column names are configurable (not hardcoded to WmsOffice's "Usuario")
 * so this is a genuinely reusable recipe: any app bonding its own identity table to
 * {@code identity::User} the same way gets a working login endpoint by setting 4 properties.</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class LoginController {

    private static final Logger LOG = Logger.getLogger(LoginController.class.getName());

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final PrivateKey privateKey;
    private final String issuer;
    private final String audience;
    private final long expirySeconds;
    private final LoginThrottle throttle = new LoginThrottle();

    public LoginController(
            DataSource dataSource,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn,
            // REG-9 (2026-07-21): default to empty, so this bean can construct in a VERIFY-ONLY
            // deployment. npdev.auth.mode=jwt has two legitimate shapes: (1) full - this instance
            // both issues (signs) and validates its own tokens; needs a private key; (2) verify-only
            // (the external-beta profile) - this instance only validates externally-issued tokens
            // via JwtBearerAuthFilter's public key and never mints its own; needs NO private key.
            // Before this change the missing property left ${npdev.auth.jwt.private-key-path}
            // unresolved and crashed the WHOLE context at bean creation with an opaque
            // placeholder error (this is IT-EXTPG-1/REG-2's JwtAuthExternalBetaIT failure). A
            // set-but-unreadable key is now caught early with a clear message by StartupValidator.
            @Value("${npdev.auth.jwt.private-key-path:}") String privateKeyPath,
            @Value("${npdev.auth.jwt.issuer:}") String issuer,
            @Value("${npdev.auth.jwt.audience:}") String audience,
            @Value("${npdev.auth.jwt.expiry-seconds:28800}") long expirySeconds
    ) throws Exception {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
        this.privateKey = (privateKeyPath == null || privateKeyPath.isBlank())
                ? null
                : JwtSigner.loadPrivateKey(readKeyFile(resourceLoader, privateKeyPath));
        this.issuer = issuer;
        this.audience = audience;
        this.expirySeconds = expirySeconds;
    }

    public record LoginRequest(String username, String password, String tenantId) {
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String tenantId = (request.tenantId() == null || request.tenantId().isBlank()) ? "dev" : request.tenantId().trim();
        String username = request.username() == null ? null : request.username().trim();
        String password = request.password();
        // REG-20: the source IP is the second throttle dimension -- one password sprayed across many
        // usernames never trips a per-username counter, but it does trip the per-IP one.
        String clientIp = clientIp(httpRequest);

        // REG-9: verify-only deployment (no signing key). This instance validates
        // externally-issued tokens but cannot mint its own, so token issuance is unavailable
        // rather than silently NPE-ing on a null key. Checked before any credential work so it
        // never leaks whether a username/password was otherwise valid.
        if (privateKey == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "token_issuance_unavailable",
                    "detail", "This deployment validates externally-issued JWTs only; no signing key "
                            + "(npdev.auth.jwt.private-key-path) is configured, so /api/auth/login cannot mint tokens."));
        }

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return unauthorized(tenantId, username, clientIp);
        }

        // LNCH-4: checked BEFORE any credential lookup, and uniformly for every rejection reason
        // below (unknown user, inactive, wrong password) -- a locked-out window rejects every
        // attempt without distinguishing right-shaped guesses from wrong ones, and without leaking
        // via timing/response differences which specific check would otherwise have failed.
        if (throttle.isLocked(tenantId, username, clientIp)) {
            return tooManyAttempts(tenantId, username, clientIp);
        }

        try (Connection connection = dataSource.getConnection()) {
            String userSql = "SELECT id, active, token_version FROM identity_users WHERE username = ? AND tenant_id = ?";
            String userId;
            int tokenVersion;
            try (PreparedStatement ps = connection.prepareStatement(userSql)) {
                ps.setString(1, username);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean("active")) {
                        // REG-18: spend the same PBKDF2 work as the wrong-password path so an unknown
                        // (or inactive) username is not distinguishable by response latency.
                        PasswordHasher.verifyDecoy(password);
                        return unauthorized(tenantId, username, clientIp);
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
                        // REG-18: same-work decoy verify for a user that exists but has no credential
                        // row, so this path is latency-indistinguishable from a wrong password.
                        PasswordHasher.verifyDecoy(password);
                        return unauthorized(tenantId, username, clientIp);
                    }
                    storedHash = rs.getString(1);
                }
            }

            if (!PasswordHasher.verify(password, storedHash)) {
                return unauthorized(tenantId, username, clientIp);
            }

            throttle.recordSuccess(tenantId, username);
            Set<String> roles = IdentityRoleLookup.rolesFor(dataSource, tenantId, username);
            JwtSigner signer = new JwtSigner(objectMapper, privateKey, issuer, audience, expirySeconds);
            String token = signer.sign(tenantId, username, roles, tokenVersion);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("token", token);
            body.put("tokenType", "Bearer");
            body.put("expiresInSeconds", expirySeconds);
            body.put("roles", roles);
            return ResponseEntity.ok(body);
        } catch (SQLException schemaCandidate) {
            // REG-39: a stale built-in-pack copy of the identity pack (missing the token_version
            // column platform code reads unconditionally, or any other column it depends on) must
            // surface as a schema error, NOT as invalid_credentials -- swallowing this exact class of
            // exception into a generic auth failure is what made WmsOffice's stale copy so expensive
            // to diagnose. StartupValidator already fails the app at boot for the identity-pack case
            // specifically; this is the fallback if that check was ever bypassed or the drift is in a
            // column StartupValidator doesn't yet cover.
            if (SqlSchemaErrors.isSchemaMismatch(schemaCandidate)) {
                LOG.log(Level.SEVERE, "Login failed: identity pack schema mismatch", schemaCandidate);
                return identityPackSchemaError(schemaCandidate);
            }
            return unauthorized(tenantId, username, clientIp);
        } catch (Exception exception) {
            return unauthorized(tenantId, username, clientIp);
        }
    }

    private ResponseEntity<Map<String, Object>> identityPackSchemaError(SQLException exception) {
        return ResponseEntity.status(500).body(Map.of(
                "error", "identity_pack_schema_error",
                "detail", "Login failed because the database schema does not match what this app's "
                        + "identity pack copy expects -- likely a stale built-in-pack copy. See "
                        + "docs/CONFIGURATION.md#identity-pack-freshness-checked-at-boot. Cause: "
                        + exception.getMessage()));
    }

    private ResponseEntity<Map<String, Object>> unauthorized(String tenantId, String username, String clientIp) {
        // REG-20: record against BOTH the per-username and per-IP windows. The IP arm is recorded even
        // for a blank/unknown username (a spray with junk usernames still counts toward the IP ceiling).
        throttle.recordFailure(tenantId, username == null ? "" : username, clientIp);
        return ResponseEntity.status(401).body(Map.of("error", "invalid_credentials"));
    }

    private ResponseEntity<Map<String, Object>> tooManyAttempts(String tenantId, String username, String clientIp) {
        long retryAfterSeconds = throttle.retryAfterSeconds(tenantId, username, clientIp);
        return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .body(Map.of("error", "too_many_attempts", "retryAfterSeconds", retryAfterSeconds));
    }

    /**
     * REG-20: best-effort client IP for rate limiting. Honours the first hop of {@code
     * X-Forwarded-For} when present (the app sits behind the generated Caddy/compose proxy), else the
     * socket peer. Never throws; a null/blank result disables the per-IP arm for that request.
     */
    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String readKeyFile(ResourceLoader resourceLoader, String path) throws Exception {
        if (path.startsWith("classpath:")) {
            Resource resource = resourceLoader.getResource(path);
            try (var inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return java.nio.file.Files.readString(java.nio.file.Path.of(path), StandardCharsets.UTF_8);
    }
}
