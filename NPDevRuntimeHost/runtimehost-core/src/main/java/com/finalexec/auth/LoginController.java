package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.auth.mfa.MfaChallengeStore;
import com.finalexec.auth.mfa.MfaSecretCodec;
import com.finalexec.auth.mfa.MfaStore;
import com.finalexec.auth.mfa.MfaTables;
import com.finalexec.auth.mfa.Totp;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.runtime.support.IdentityRoleLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.finalexec.config.DefaultTenant;

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
    // REG-177 fix: graceful tryResolve, not the throwing resolve() -- this bean is only gated on
    // npdev.auth.mode=jwt, NOT on the identity pack being composed, so an app that sets jwt auth
    // mode without ever composing the identity pack must still boot (guarded per-request instead).
    // REG-208 (B28 lift): resolved fresh from modelHolder.get() on every request rather than cached
    // at construction, so a hot model reload is observed without needing a rebuild listener.
    private final ModelHolder modelHolder;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final PrivateKey privateKey;
    private final String issuer;
    private final String audience;
    private final long expirySeconds;
    private final LoginThrottle throttle = new LoginThrottle();

    /** Convenience overload for existing test call sites with a plain {@link CompiledModel} and no
     * live {@link ModelHolder} -- wraps it in a non-reloading holder. */
    public LoginController(
            DataSource dataSource,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            CompiledModel compiledModel,
            String credentialTable,
            String credentialUserIdColumn,
            String credentialPasswordColumn,
            String privateKeyPath,
            String issuer,
            String audience,
            long expirySeconds
    ) throws Exception {
        this(dataSource, objectMapper, resourceLoader, new ModelHolder(compiledModel),
                credentialTable, credentialUserIdColumn, credentialPasswordColumn,
                privateKeyPath, issuer, audience, expirySeconds);
    }

    @Autowired
    public LoginController(
            DataSource dataSource,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            ModelHolder modelHolder,
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
        this.modelHolder = modelHolder;
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
        String tenantId = DefaultTenant.orDefault(request.tenantId());
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

        Optional<IdentityPackTableNames> resolvedIdentityTables = IdentityPackTableNames.tryResolve(modelHolder.get());
        if (resolvedIdentityTables.isEmpty()) {
            // This app runs npdev.auth.mode=jwt but never composed the identity pack -- there is no
            // identity_users table for this endpoint to authenticate against at all.
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

            // SEC-13: a user with a CONFIRMED authenticator enrollment has only passed the first factor.
            // No token, no cookie and no throttle.recordSuccess until the second factor passes.
            // MfaTables == null (identity pack older than 1.3.0) behaves exactly as before.
            String mfaTable = MfaTables.userMfaTable(modelHolder.get());
            if (mfaTable != null && MfaStore.isEnabled(connection, mfaTable, UUID.fromString(userId), tenantId)) {
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("mfaRequired", true);
                pending.put("challenge", MfaChallengeStore.shared().create(tenantId, username, null));
                pending.put("expiresInSeconds", MfaChallengeStore.TTL_MILLIS / 1000);
                return ResponseEntity.ok(pending);
            }
            return issueSession(tenantId, username, identityTables, tokenVersion, null, httpRequest);
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

    public record MfaLoginRequest(String challenge, String code, String recoveryCode) {
    }

    /**
     * SEC-13: second step of a sign-in for a user with two-step sign-in on. The challenge id (from
     * {@link #login} or the OAuth callback) is the only credential; this path is exempt from the JWT
     * filter exactly like {@code /api/auth/login}. Wrong codes count against BOTH the challenge (5) and
     * the same per-username/per-IP throttle the password step uses, so the 6-digit space cannot be
     * walked across many challenges. {@code active} and {@code token_version} are re-read here, never
     * carried in the challenge: an admin disabling the account or revoking sessions in between wins.
     */
    @PostMapping("/api/auth/login/mfa")
    public ResponseEntity<Map<String, Object>> loginMfa(@RequestBody MfaLoginRequest request,
                                                        HttpServletRequest httpRequest) {
        if (privateKey == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "token_issuance_unavailable",
                    "detail", "This deployment validates externally-issued JWTs only; no signing key "
                            + "(npdev.auth.jwt.private-key-path) is configured, so /api/auth/login/mfa cannot mint tokens."));
        }
        String challengeId = request == null ? null : request.challenge();
        MfaChallengeStore.Challenge challenge = MfaChallengeStore.shared().peek(challengeId).orElse(null);
        if (challenge == null) {
            return ResponseEntity.status(401).body(Map.of("error", "mfa_challenge_expired"));
        }
        String tenantId = challenge.tenantId();
        String username = challenge.username();
        String clientIp = clientIp(httpRequest);
        if (throttle.isLocked(tenantId, username, clientIp)) {
            return tooManyAttempts(tenantId, username, clientIp);
        }
        Optional<IdentityPackTableNames> resolvedIdentityTables = IdentityPackTableNames.tryResolve(modelHolder.get());
        String mfaTable = MfaTables.userMfaTable(modelHolder.get());
        if (resolvedIdentityTables.isEmpty() || mfaTable == null) {
            return ResponseEntity.status(503).body(Map.of("error", "mfa_not_available"));
        }
        IdentityPackTableNames identityTables = resolvedIdentityTables.get();

        try (Connection connection = dataSource.getConnection()) {
            String userSql = "SELECT id, active, token_version FROM " + identityTables.usersTable()
                    + " WHERE username = ? AND tenant_id = ?";
            UUID userId;
            int tokenVersion;
            try (PreparedStatement ps = connection.prepareStatement(userSql)) {
                ps.setString(1, username);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean("active")) {
                        MfaChallengeStore.shared().consume(challengeId);
                        return ResponseEntity.status(401).body(Map.of("error", "invalid_credentials"));
                    }
                    userId = UUID.fromString(String.valueOf(rs.getObject("id")));
                    tokenVersion = rs.getInt("token_version");
                    if (rs.wasNull()) {
                        tokenVersion = 0;
                    }
                }
            }
            Optional<MfaStore.Row> row = MfaStore.find(connection, mfaTable, userId, tenantId);
            if (row.isEmpty() || !row.get().enabled()) {
                // The second factor was switched off after the password step: restart the sign-in.
                MfaChallengeStore.shared().consume(challengeId);
                return ResponseEntity.status(401).body(Map.of("error", "mfa_challenge_expired"));
            }
            boolean accepted = false;
            String code = request.code();
            String recoveryCode = request.recoveryCode();
            if (code != null && !code.isBlank()) {
                long step = -1L;
                try {
                    step = Totp.verify(MfaSecretCodec.decode(row.get().secretStored()), code, Instant.now(),
                            row.get().lastUsedStep());
                } catch (RuntimeException unreadableSecret) {
                    // e.g. the secret was stored encrypted and npdev.auth.mfa.secret-key is gone: the TOTP
                    // path is unusable, recovery codes (hash-based) still work.
                    LOG.log(Level.SEVERE, "MFA secret unreadable for a user; only recovery codes can sign in",
                            unreadableSecret);
                }
                accepted = step >= 0
                        && MfaStore.setLastUsedStep(connection, mfaTable, userId, tenantId, step);
            } else if (recoveryCode != null && !recoveryCode.isBlank()) {
                accepted = MfaStore.consumeRecoveryCode(connection, mfaTable, userId, tenantId,
                        Totp.recoveryCodeHash(recoveryCode));
            }
            if (!accepted) {
                MfaChallengeStore.shared().recordFailure(challengeId);
                throttle.recordFailure(tenantId, username, clientIp);
                return ResponseEntity.status(401).body(Map.of("error", "invalid_mfa_code"));
            }
            MfaChallengeStore.shared().consume(challengeId);
            return issueSession(tenantId, username, identityTables, tokenVersion, challenge.next(), httpRequest);
        } catch (SQLException schemaCandidate) {
            if (SqlSchemaErrors.isSchemaMismatch(schemaCandidate)) {
                LOG.log(Level.SEVERE, "MFA login failed: identity pack schema mismatch", schemaCandidate);
                return identityPackSchemaError(schemaCandidate);
            }
            LOG.log(Level.WARNING, "MFA login failed", schemaCandidate);
            return ResponseEntity.status(500).body(Map.of("error", "mfa_login_failed"));
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "MFA login failed", exception);
            return ResponseEntity.status(500).body(Map.of("error", "mfa_login_failed"));
        }
    }

    /**
     * The success tail of a sign-in, shared by the password step and the MFA second step: resets the
     * throttle, mints the JWT and sets the session cookie. {@code next} (an already-sanitized post-login
     * target) is put into the body only when non-null.
     */
    private ResponseEntity<Map<String, Object>> issueSession(String tenantId, String username,
                                                             IdentityPackTableNames identityTables, int tokenVersion,
                                                             String next, HttpServletRequest httpRequest) {
        throttle.recordSuccess(tenantId, username);
        Set<String> roles = IdentityRoleLookup.rolesFor(dataSource, identityTables, tenantId, username);
        JwtSigner signer = new JwtSigner(objectMapper, privateKey, issuer, audience, expirySeconds);
        String token = signer.sign(tenantId, username, roles, tokenVersion);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("tokenType", "Bearer");
        body.put("expiresInSeconds", expirySeconds);
        body.put("roles", roles);
        if (next != null) {
            body.put("next", next);
        }
        // A plain browser page navigation (typing a URL, clicking a link) never carries a
        // custom Authorization header -- only a cookie rides along automatically. Without this,
        // an arbitrary role-gated page route (a trusted-source panel's own declared route,
        // which this shared controller cannot special-case) had no way to ever authenticate a
        // top-level GET, even though the JSON token above already made fetch()-driven calls
        // work fine. HttpOnly so client JS/XSS can't read it (the JSON token above still covers
        // that use case). SameSite=Lax, not Strict (REG-233): Lax still blocks the cookie from
        // riding along on cross-site subrequests and form posts -- CSRF protection is preserved
        // -- but it permits the cookie on a top-level GET navigation that lands here via a
        // cross-site redirect, which Strict does not. That distinction matters because the
        // Google OAuth account-LINK callback (OAuthGoogleController) needs this exact cookie
        // to survive a top-level redirect FROM accounts.google.com back to our own callback URL;
        // browsers treat that as cross-site-initiated even though the destination is same-origin,
        // and Strict silently dropped the cookie on that hop. Secure mirrors the inbound
        // request's own scheme so local http dev still works while a TLS-terminated deployment
        // gets the flag automatically.
        ResponseCookie sessionCookie = ResponseCookie.from("npdev_jwt", token)
                .httpOnly(true)
                .secure(httpRequest != null && httpRequest.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(expirySeconds)
                .build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie.toString()).body(body);
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

    /** AGENT-1 (A5.2): public so AgentLinkService (com.finalexec.agent, a different package) can
     *  load a PEM key file the same way OAuthGoogleController does within this package. */
    public static String readKeyFile(ResourceLoader resourceLoader, String path) throws Exception {
        if (path.startsWith("classpath:")) {
            Resource resource = resourceLoader.getResource(path);
            try (var inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return java.nio.file.Files.readString(java.nio.file.Path.of(path), StandardCharsets.UTF_8);
    }
}
