package com.finalexec.auth.mfa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SEC-11: JDBC access to {@code identity::UserMfa}. Every method takes the composed table name from
 * {@link MfaTables#userMfaTable} (never a literal) and carries the tenant predicate. A row with
 * {@code enabled_at IS NULL} is a PENDING enrollment and never counts as enabled.
 *
 * <p>Recovery codes are stored only as SHA-256 hashes ({@link Totp#recoveryCodeHash}), as a JSON
 * array string in {@code recovery_codes}.
 */
public final class MfaStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private MfaStore() {
    }

    /** lastUsedStep is -1 when no code was ever accepted. */
    public record Row(UUID id, String secretStored, Instant enabledAt, long lastUsedStep,
                      List<String> recoveryCodeHashes) {
        public boolean enabled() {
            return enabledAt != null;
        }
    }

    public static Optional<Row> find(Connection connection, String table, UUID userId, String tenantId)
            throws SQLException {
        String sql = "SELECT id, secret, enabled_at, last_used_step, recovery_codes FROM " + table
                + " WHERE user_id = ? AND tenant_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp enabledAt = rs.getTimestamp("enabled_at");
                long lastUsed = rs.getLong("last_used_step");
                if (rs.wasNull()) {
                    lastUsed = -1L;
                }
                return Optional.of(new Row(
                        UUID.fromString(String.valueOf(rs.getObject("id"))),
                        rs.getString("secret"),
                        enabledAt == null ? null : enabledAt.toInstant(),
                        lastUsed,
                        parseHashes(rs.getString("recovery_codes"))));
            }
        }
    }

    /** True only for an ENABLED (confirmed) enrollment; a pending row is not enabled. */
    public static boolean isEnabled(Connection connection, String mfaTable, UUID userId, String tenantId)
            throws SQLException {
        String sql = "SELECT 1 FROM " + mfaTable
                + " WHERE user_id = ? AND tenant_id = ? AND enabled_at IS NOT NULL";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Replaces any PENDING row of the user with a fresh one holding the (already encoded) secret. */
    public static void upsertPending(Connection connection, String table, UUID userId, String tenantId,
                                     String storedSecret) throws SQLException {
        String delete = "DELETE FROM " + table + " WHERE user_id = ? AND tenant_id = ? AND enabled_at IS NULL";
        try (PreparedStatement ps = connection.prepareStatement(delete)) {
            ps.setObject(1, userId);
            ps.setString(2, tenantId);
            ps.executeUpdate();
        }
        String insert = "INSERT INTO " + table + " (id, user_id, secret, tenant_id) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insert)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setString(3, storedSecret);
            ps.setString(4, tenantId);
            ps.executeUpdate();
        }
    }

    /** Confirms a pending row. Returns false when there was no pending row to confirm. */
    public static boolean enable(Connection connection, String table, UUID userId, String tenantId,
                                 long step, List<String> recoveryHashes) throws SQLException {
        String sql = "UPDATE " + table + " SET enabled_at = ?, last_used_step = ?, recovery_codes = ?"
                + " WHERE user_id = ? AND tenant_id = ? AND enabled_at IS NULL";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setInt(2, Math.toIntExact(step));
            ps.setString(3, toJson(recoveryHashes));
            ps.setObject(4, userId);
            ps.setString(5, tenantId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Atomic replay guard: advances last_used_step only when the new step is strictly greater, so
     * two concurrent requests presenting the same code cannot both succeed. Returns false when the
     * step was already used.
     */
    public static boolean setLastUsedStep(Connection connection, String table, UUID userId, String tenantId,
                                          long step) throws SQLException {
        String sql = "UPDATE " + table + " SET last_used_step = ?"
                + " WHERE user_id = ? AND tenant_id = ? AND enabled_at IS NOT NULL"
                + " AND (last_used_step IS NULL OR last_used_step < ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int s = Math.toIntExact(step);
            ps.setInt(1, s);
            ps.setObject(2, userId);
            ps.setString(3, tenantId);
            ps.setInt(4, s);
            return ps.executeUpdate() > 0;
        }
    }

    public static void replaceRecoveryCodes(Connection connection, String table, UUID userId, String tenantId,
                                            List<String> recoveryHashes) throws SQLException {
        String sql = "UPDATE " + table + " SET recovery_codes = ?"
                + " WHERE user_id = ? AND tenant_id = ? AND enabled_at IS NOT NULL";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, toJson(recoveryHashes));
            ps.setObject(2, userId);
            ps.setString(3, tenantId);
            ps.executeUpdate();
        }
    }

    /**
     * Removes the code's hash from the stored list. Returns true when it was present AND this call
     * removed it (the UPDATE is conditional on the list being unchanged, so two concurrent uses of
     * one code cannot both succeed).
     */
    public static boolean consumeRecoveryCode(Connection connection, String table, UUID userId, String tenantId,
                                              String hash) throws SQLException {
        Optional<Row> row = find(connection, table, userId, tenantId);
        if (row.isEmpty() || !row.get().enabled() || !row.get().recoveryCodeHashes().contains(hash)) {
            return false;
        }
        List<String> remaining = new ArrayList<>(row.get().recoveryCodeHashes());
        remaining.remove(hash);
        String previous = toJson(row.get().recoveryCodeHashes());
        String sql = "UPDATE " + table + " SET recovery_codes = ?"
                + " WHERE user_id = ? AND tenant_id = ? AND enabled_at IS NOT NULL AND recovery_codes = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, toJson(remaining));
            ps.setObject(2, userId);
            ps.setString(3, tenantId);
            ps.setString(4, previous);
            return ps.executeUpdate() > 0;
        }
    }

    public static void delete(Connection connection, String table, UUID userId, String tenantId)
            throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE user_id = ? AND tenant_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, tenantId);
            ps.executeUpdate();
        }
    }

    private static List<String> parseHashes(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String toJson(List<String> hashes) {
        try {
            return JSON.writeValueAsString(hashes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
