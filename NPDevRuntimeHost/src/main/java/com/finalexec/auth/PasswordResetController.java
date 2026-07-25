package com.finalexec.auth;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LNCH-4 P1: self-service password reset, built on LNCH-11's mail capability -- "request" emails a
 * single-use token to the account's email on file (identity_users.email), "confirm" consumes it and
 * sets a new password. Deliberately generic (never reveals whether a username/email exists) so this
 * can't be used to enumerate accounts.
 *
 * <p>An app must declare AND bind a "mail" capability in its own model.json for this to actually
 * send anything -- {@link CapabilityRegistry#has(String)} is checked before every send attempt, and
 * a request from an app with no mail capability bound still returns the same generic response (it
 * just never emails anyone). Admin-forced reset (no email required) remains available via
 * {@link com.finalexec.controlpanel.ControlPanelTenantUsersController} for accounts with no email on
 * file or apps that haven't bound a mail capability yet.</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class PasswordResetController {

    private static final Logger LOG = Logger.getLogger(PasswordResetController.class.getName());
    private static final long TOKEN_EXPIRY_MINUTES = 30;
    private static final int TOKEN_BYTES = 32;
    private static final int MIN_PASSWORD_LENGTH = 8;
    /** REG-21 (REG-16 finding F4): reset requests per (tenant,username) per window before further ones
     * are silently no-op'd -- low, because a real user needs one, and it caps email-bombing. */
    private static final int RESET_MAX_PER_USER = 5;
    /** REG-21: reset requests per source IP per window -- higher (shared IPs) but still spray-catching. */
    private static final int RESET_MAX_PER_IP = 20;
    private static final Map<String, Object> GENERIC_REQUEST_RESPONSE =
            Map.of("ok", true, "message", "If that account exists, a password reset email has been sent.");

    private final DataSource dataSource;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityDispatcher capabilityDispatcher;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final String resetLinkBaseUrl;
    /** REG-21: the same bounded sliding-window limiter login uses, tuned lower for reset requests. */
    private final LoginThrottle resetThrottle = new LoginThrottle(
            java.time.Clock.systemUTC(), RESET_MAX_PER_USER, RESET_MAX_PER_IP);

    public PasswordResetController(
            DataSource dataSource,
            CapabilityRegistry capabilityRegistry,
            CapabilityDispatcher capabilityDispatcher,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn,
            @Value("${npdev.auth.password-reset.link-base-url:}") String resetLinkBaseUrl
    ) {
        this.dataSource = dataSource;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityDispatcher = capabilityDispatcher;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
        this.resetLinkBaseUrl = resetLinkBaseUrl;
    }

    public record RequestResetRequest(String username, String tenantId) {
    }

    public record ConfirmResetRequest(String token, String newPassword, String tenantId) {
    }

    @PostMapping("/api/auth/password-reset/request")
    public ResponseEntity<Map<String, Object>> requestReset(
            @RequestBody RequestResetRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        String tenantId = normalizeTenant(request == null ? null : request.tenantId());
        String username = request == null || request.username() == null ? null : request.username().trim();

        if (username == null || username.isBlank() || !capabilityRegistry.has("mail")) {
            return ResponseEntity.ok(GENERIC_REQUEST_RESPONSE);
        }

        // REG-21: rate-limit reset requests per (tenant,username) and per source IP so a known account
        // cannot be email-bombed and so token rows cannot be spammed. When over the limit we return
        // the SAME generic response (never revealing the throttle) but skip the email + token entirely.
        String clientIp = clientIp(httpRequest);
        if (resetThrottle.isLocked(tenantId, username, clientIp)) {
            return ResponseEntity.ok(GENERIC_REQUEST_RESPONSE);
        }
        resetThrottle.recordFailure(tenantId, username, clientIp);

        try (Connection connection = dataSource.getConnection()) {
            UserLookup user = findActiveUserWithEmail(connection, tenantId, username);
            if (user == null) {
                return ResponseEntity.ok(GENERIC_REQUEST_RESPONSE);
            }

            String rawToken = generateToken();
            String tokenHash = sha256Hex(rawToken);
            Instant expiresAt = Instant.now().plusSeconds(TOKEN_EXPIRY_MINUTES * 60);
            insertResetToken(connection, user.userId(), tokenHash, expiresAt, tenantId);
            sendResetEmail(user.email(), rawToken);
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Password reset request failed", exception);
            // Deliberately swallowed -- the response must stay identical whether the account,
            // email, or capability binding was the reason nothing got sent.
        }
        return ResponseEntity.ok(GENERIC_REQUEST_RESPONSE);
    }

    @PostMapping("/api/auth/password-reset/confirm")
    public ResponseEntity<Map<String, Object>> confirmReset(@RequestBody ConfirmResetRequest request) {
        String tenantId = normalizeTenant(request == null ? null : request.tenantId());
        String token = request == null ? null : request.token();
        String newPassword = request == null ? null : request.newPassword();

        if (token == null || token.isBlank() || newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
        }

        String tokenHash = sha256Hex(token);
        try (Connection connection = dataSource.getConnection()) {
            ResetTokenRecord record = findValidToken(connection, tokenHash, tenantId);
            if (record == null) {
                return ResponseEntity.status(400).body(Map.of("error", "invalid_or_expired_token"));
            }

            String updateCredentialSql = "UPDATE " + credentialTable + " SET " + credentialPasswordColumn + " = ?"
                    + " WHERE " + credentialUserIdColumn + " = ? AND tenant_id = ?";
            int updated;
            try (PreparedStatement ps = connection.prepareStatement(updateCredentialSql)) {
                ps.setString(1, PasswordHasher.hash(newPassword));
                ps.setObject(2, record.userId());
                ps.setString(3, tenantId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                return ResponseEntity.status(400).body(Map.of("error", "invalid_or_expired_token"));
            }

            markTokenUsed(connection, record.tokenId());
            bumpTokenVersion(connection, record.userId(), tenantId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IdentityPackSchemaException schemaException) {
            // REG-39: the token_version bump below found a schema mismatch (stale built-in-pack
            // copy). The credential update already committed, so the reset itself worked -- but
            // failing to report the revocation gap as a schema error (instead of the generic
            // password_reset_failed) is exactly the kind of masking that made REG-39 expensive.
            LOG.log(Level.SEVERE, "Password reset confirm: identity pack schema mismatch bumping token_version",
                    schemaException);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "identity_pack_schema_error",
                    "detail", "Password reset succeeded but session revocation failed because this app's "
                            + "identity pack copy is out of date. See "
                            + "docs/CONFIGURATION.md#identity-pack-freshness-checked-at-boot. Cause: "
                            + schemaException.getMessage()));
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Password reset confirm failed", exception);
            return ResponseEntity.status(500).body(Map.of("error", "password_reset_failed"));
        }
    }

    private record UserLookup(UUID userId, String email) {
    }

    private record ResetTokenRecord(UUID tokenId, UUID userId) {
    }

    private UserLookup findActiveUserWithEmail(Connection connection, String tenantId, String username) throws Exception {
        String sql = "SELECT id, email FROM identity_users WHERE username = ? AND tenant_id = ? AND active = TRUE";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String email = rs.getString("email");
                if (email == null || email.isBlank()) {
                    return null;
                }
                return new UserLookup((UUID) rs.getObject("id"), email);
            }
        }
    }

    private void insertResetToken(Connection connection, UUID userId, String tokenHash, Instant expiresAt, String tenantId)
            throws Exception {
        String sql = "INSERT INTO identity_password_reset_tokens (id, user_id, token_hash, expires_at, tenant_id) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setString(3, tokenHash);
            ps.setTimestamp(4, Timestamp.from(expiresAt));
            ps.setString(5, tenantId);
            ps.executeUpdate();
        }
    }

    private ResetTokenRecord findValidToken(Connection connection, String tokenHash, String tenantId) throws Exception {
        String sql = "SELECT id, user_id, expires_at, used_at FROM identity_password_reset_tokens "
                + "WHERE token_hash = ? AND tenant_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tokenHash);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Timestamp usedAt = rs.getTimestamp("used_at");
                Timestamp expiresAt = rs.getTimestamp("expires_at");
                if (usedAt != null || expiresAt == null || expiresAt.toInstant().isBefore(Instant.now())) {
                    return null;
                }
                return new ResetTokenRecord((UUID) rs.getObject("id"), (UUID) rs.getObject("user_id"));
            }
        }
    }

    private void markTokenUsed(Connection connection, UUID tokenId) throws Exception {
        String sql = "UPDATE identity_password_reset_tokens SET used_at = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setObject(2, tokenId);
            ps.executeUpdate();
        }
    }

    /**
     * Mirrors {@code ControlPanelTenantUsersController.bumpTokenVersion} -- a successful reset must
     * invalidate every JWT already minted under the old password, or a still-live token keeps
     * working right through the reset that was supposed to lock it out.
     */
    private void bumpTokenVersion(Connection connection, UUID userId, String tenantId) {
        String sql = "UPDATE identity_users SET token_version = COALESCE(token_version, 0) + 1"
                + " WHERE id = ? AND tenant_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, tenantId);
            ps.executeUpdate();
        } catch (SQLException exception) {
            // REG-39: a schema mismatch here is NOT the same kind of "best-effort, don't care" failure
            // as e.g. a transient lock -- it means this app's identity pack copy is stale, and staying
            // silent about it is exactly how REG-39 went undiagnosed. Let it propagate distinctly;
            // anything else stays best-effort, same as before.
            if (SqlSchemaErrors.isSchemaMismatch(exception)) {
                throw new IdentityPackSchemaException(exception);
            }
        }
    }

    private void sendResetEmail(String email, String rawToken) {
        String adapterId = capabilityRegistry.debugDefaultAdapterId("mail");
        String link = resetLinkBaseUrl.isBlank() ? rawToken : resetLinkBaseUrl + rawToken;
        String body = resetLinkBaseUrl.isBlank()
                ? "Your password reset code is: " + rawToken + "\nIt expires in " + TOKEN_EXPIRY_MINUTES + " minutes."
                : "Reset your password: " + link + "\nThis link expires in " + TOKEN_EXPIRY_MINUTES + " minutes.";
        CapabilityCall call = new CapabilityCall(
                "mail", "EmailCapability", adapterId, "send",
                List.of(email, "Password reset request", body)
        );
        CapabilityResult result = capabilityDispatcher.invoke(call, Map.of());
        if (!result.ok()) {
            LOG.warning("Password reset email dispatch failed: " + result.error());
        }
    }

    private static String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "dev" : tenantId.trim();
    }

    /** REG-21: best-effort client IP for rate limiting (X-Forwarded-For first hop, else socket peer). */
    private static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
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

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
