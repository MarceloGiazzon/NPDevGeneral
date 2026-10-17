package com.finalexec.auth;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * SEC-6: the single-use token primitive shared by password reset ({@link PasswordResetController})
 * and invitations (InvitationController). An invitation IS a reset token with a longer expiry,
 * accepted by the existing {@code POST /api/auth/password-reset/confirm}. Only the SHA-256 of the raw
 * token is ever stored.
 */
public final class OneTimeTokens {

    public static final String TOKEN_CONCEPT = "identity::PasswordResetToken";
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private OneTimeTokens() {
    }

    /** The composed physical table (e.g. identity_v1_password_reset_tokens), or null when absent. */
    public static String tableName(CompiledModel model) {
        if (model == null) {
            return null;
        }
        return model.findConcept(TOKEN_CONCEPT)
                .map(CompiledConcept::getTableName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(null);
    }

    public static String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static void insert(Connection connection, String table, UUID userId, String tokenHash,
                              Instant expiresAt, String tenantId) throws Exception {
        String sql = "INSERT INTO " + table + " (id, user_id, token_hash, expires_at, tenant_id) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setString(3, tokenHash);
            ps.setTimestamp(4, Timestamp.from(expiresAt));
            ps.setString(5, tenantId);
            ps.executeUpdate();
        }
    }
}
