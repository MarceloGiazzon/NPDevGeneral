package com.finalexec.npdev.service;

import com.npdev.kernel.dbschema.NpdevApiCredentialPolicyTable;
import com.npdev.kernel.dbschema.NpdevApiCredentialTable;
import com.npdev.kernel.ports.ApiKeyCredentialResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Issues, looks up, and revokes runtime-issued API key credentials -- the piece that lets a tenant
 * onboarded via {@code TenantRegistryService} actually authenticate without a regenerate/restart.
 *
 * <p>Security posture: the raw key is generated here, returned to the caller exactly ONCE in the
 * issuance response, and never stored. Only a SHA-256 hash of the key is persisted
 * ({@value NpdevApiCredentialTable#NAME}), so a database read alone can never recover a usable
 * credential. {@link #resolve} hashes the incoming key and looks up the hash -- it never compares
 * raw key material.</p>
 *
 * <p>INT-8: each credential also has a row in {@value NpdevApiCredentialPolicyTable#NAME} (label,
 * optional expiry, last-used stamp). A credential without that row (issued before the table
 * existed) has no label and never expires.</p>
 */
@Service
public class CredentialRegistryService implements ApiKeyCredentialResolver {

    public enum Status { ACTIVE, REVOKED }

    /** A credential's last-used stamp is refreshed at most this often, so a busy integration does not write per request. */
    static final long LAST_USED_REFRESH_MS = 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final Clock clock;

    @Autowired
    public CredentialRegistryService(ObjectProvider<DataSource> dataSourceProvider) {
        this(dataSourceProvider, Clock.systemUTC());
    }

    public CredentialRegistryService(ObjectProvider<DataSource> dataSourceProvider, Clock clock) {
        this.dataSourceProvider = dataSourceProvider;
        this.clock = clock;
    }

    /** Returns the raw key. This is the only place it is ever observable -- callers must save it now. */
    public Map<String, Object> issue(String tenantId, String actorId, Set<String> roles) {
        return issue(tenantId, actorId, roles, null, null, null);
    }

    /**
     * INT-8: issues a key with a human {@code label}, an optional expiry ({@code expiresAtMs}, epoch
     * millis; null = never) and the issuing admin's identity. An expiry that is already in the past
     * is rejected ({@link IllegalArgumentException}).
     */
    public Map<String, Object> issue(
            String tenantId, String actorId, Set<String> roles, String label, Long expiresAtMs, String createdBy) {
        String rawKey = generateRawKey();
        String credentialId = insert(tenantId, actorId, roles, hash(rawKey), label, expiresAtMs, createdBy);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credentialId", credentialId);
        body.put("apiKey", rawKey);
        body.put("tenantId", requireNonBlank(tenantId, "tenantId"));
        body.put("actorId", requireNonBlank(actorId, "actorId"));
        body.put("roles", normalizeRoles(roles));
        body.put("label", normalizeLabel(label));
        body.put("expiresAtMs", expiresAtMs);
        body.put("warning", "This key is shown once and is not retrievable again. Store it now.");
        return body;
    }

    /**
     * SEC-8 (B17): issues a credential using a CALLER-SUPPLIED key hash rather than generating a
     * random key -- for an operator-supplied bootstrap credential (a hash configured via {@code
     * npdev.superuser.bootstrap-key-hash}, or a raw key configured via {@code
     * .bootstrap-key-raw} and hashed immediately without ever being persisted). Returns no {@code
     * apiKey} field -- there is nothing to show; the operator already holds the raw key.
     */
    public Map<String, Object> issueWithKnownHash(String tenantId, String actorId, Set<String> roles, String keyHash) {
        String hashValue = requireNonBlank(keyHash, "keyHash");
        String credentialId = insert(tenantId, actorId, roles, hashValue, null, null, null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credentialId", credentialId);
        body.put("tenantId", requireNonBlank(tenantId, "tenantId"));
        body.put("actorId", requireNonBlank(actorId, "actorId"));
        body.put("roles", normalizeRoles(roles));
        return body;
    }

    private String insert(
            String tenantId, String actorId, Set<String> roles, String keyHash,
            String label, Long expiresAtMs, String createdBy) {
        String tenant = requireNonBlank(tenantId, "tenantId");
        String actor = requireNonBlank(actorId, "actorId");
        Set<String> normalizedRoles = normalizeRoles(roles);
        long now = clock.millis();
        if (expiresAtMs != null && expiresAtMs <= now) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        String credentialId = UUID.randomUUID().toString();

        DataSource dataSource = requireDataSource();
        String credentialSql = "INSERT INTO " + NpdevApiCredentialTable.NAME
                + " (credential_id, key_hash, tenant_id, actor_id, roles, status, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)";
        String policySql = "INSERT INTO " + NpdevApiCredentialPolicyTable.NAME
                + " (credential_id, label, expires_at_ms, created_by) VALUES (?, ?, ?, ?)";
        // One connection, one transaction: a credential must never exist without its policy row (an
        // expiry silently not recorded would turn a time-boxed key into a permanent one).
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(credentialSql);
                 PreparedStatement policy = connection.prepareStatement(policySql)) {
                statement.setString(1, credentialId);
                statement.setString(2, keyHash);
                statement.setString(3, tenant);
                statement.setString(4, actor);
                statement.setString(5, String.join(",", normalizedRoles));
                statement.setString(6, Status.ACTIVE.name());
                statement.setLong(7, now);
                statement.executeUpdate();

                policy.setString(1, credentialId);
                policy.setString(2, normalizeLabel(label));
                if (expiresAtMs == null) {
                    policy.setNull(3, java.sql.Types.BIGINT);
                } else {
                    policy.setLong(3, expiresAtMs);
                }
                policy.setString(4, createdBy == null || createdBy.isBlank() ? null : createdBy.trim());
                policy.executeUpdate();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed issuing credential: " + exception.getMessage(), exception);
        }
        return credentialId;
    }

    /** SEC-8 (B17): the ACTIVE credential id whose hash matches {@code apiKey}, if any -- so a
     * caller already holding a live key (e.g. the claim flow, authenticated via the bootstrap key
     * itself) can identify and revoke EXACTLY that credential rather than every active one. */
    public java.util.Optional<String> credentialIdForKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return java.util.Optional.empty();
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return java.util.Optional.empty();
        }
        String sql = "SELECT credential_id FROM " + NpdevApiCredentialTable.NAME
                + " WHERE key_hash = ? AND status = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash(apiKey));
            statement.setString(2, Status.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? java.util.Optional.of(resultSet.getString("credential_id"))
                        : java.util.Optional.empty();
            }
        } catch (SQLException exception) {
            return java.util.Optional.empty();
        }
    }

    /** SEC-8 (B17): {@code apiKey}'s stored status REGARDLESS of whether it is still ACTIVE --
     * unlike {@link #resolve}, which only ever reports a hit for a currently-usable credential. Lets
     * a caller (the super-user auth filter) distinguish "this key was issued, then revoked" from
     * "this key was never issued at all", so a claimed bootstrap key's later reuse gets a message
     * saying so instead of the generic rejection. */
    public java.util.Optional<Status> lookupStatus(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return java.util.Optional.empty();
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return java.util.Optional.empty();
        }
        String sql = "SELECT status FROM " + NpdevApiCredentialTable.NAME + " WHERE key_hash = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash(apiKey));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? java.util.Optional.of(Status.valueOf(resultSet.getString("status")))
                        : java.util.Optional.empty();
            }
        } catch (SQLException exception) {
            return java.util.Optional.empty();
        }
    }

    public List<Map<String, Object>> list() {
        return list(null);
    }

    /**
     * Lists credentials with their label, expiry and last-used stamp. {@code tenantFilterOrNull}
     * restricts the result to ONE tenant (a tenant admin must never see another tenant's keys);
     * null lists every tenant (SUPERUSER).
     */
    public List<Map<String, Object>> list(String tenantFilterOrNull) {
        DataSource dataSource = requireDataSource();
        boolean filtered = tenantFilterOrNull != null;
        String sql = "SELECT c.credential_id, c.tenant_id, c.actor_id, c.roles, c.status, c.created_at_ms,"
                + " p.label, p.expires_at_ms, p.last_used_at_ms, p.created_by FROM "
                + NpdevApiCredentialTable.NAME + " c LEFT JOIN " + NpdevApiCredentialPolicyTable.NAME
                + " p ON p.credential_id = c.credential_id"
                + (filtered ? " WHERE c.tenant_id = ?" : "")
                + " ORDER BY c.created_at_ms DESC";
        long now = clock.millis();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (filtered) {
                statement.setString(1, tenantFilterOrNull);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("credentialId", resultSet.getString("credential_id"));
                    row.put("tenantId", resultSet.getString("tenant_id"));
                    row.put("actorId", resultSet.getString("actor_id"));
                    row.put("roles", resultSet.getString("roles"));
                    row.put("status", resultSet.getString("status"));
                    row.put("createdAtMs", resultSet.getLong("created_at_ms"));
                    row.put("label", resultSet.getString("label"));
                    Long expiresAt = nullableLong(resultSet, "expires_at_ms");
                    row.put("expiresAtMs", expiresAt);
                    row.put("lastUsedAtMs", nullableLong(resultSet, "last_used_at_ms"));
                    row.put("createdBy", resultSet.getString("created_by"));
                    row.put("expired", expiresAt != null && expiresAt <= now);
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed listing credentials: " + exception.getMessage(), exception);
        }
    }

    public void revoke(String credentialId) {
        revoke(credentialId, null);
    }

    /**
     * Revokes a credential. When {@code tenantFilterOrNull} is set only a credential of THAT tenant
     * matches; any other id reports "Unknown credential" -- a tenant admin must not learn that an id
     * exists in another tenant.
     */
    public void revoke(String credentialId, String tenantFilterOrNull) {
        String id = requireNonBlank(credentialId, "credentialId");
        DataSource dataSource = requireDataSource();
        boolean filtered = tenantFilterOrNull != null;
        String sql = "UPDATE " + NpdevApiCredentialTable.NAME + " SET status = ? WHERE credential_id = ?"
                + (filtered ? " AND tenant_id = ?" : "");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Status.REVOKED.name());
            statement.setString(2, id);
            if (filtered) {
                statement.setString(3, tenantFilterOrNull);
            }
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Unknown credential: " + id);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed revoking credential '" + id + "': " + exception.getMessage(), exception);
        }
    }

    /**
     * {@link ApiKeyCredentialResolver} entry point: hashes the incoming key, looks up the hash, then
     * enforces INT-8 expiry (an expired key resolves to nothing, so the filter answers 401) and
     * refreshes the last-used stamp (at most once per {@value #LAST_USED_REFRESH_MS} ms). A key with
     * no policy row (issued before the policy table existed) never expires.
     */
    @Override
    public Optional<Principal> resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return Optional.empty();
        }
        String sql = "SELECT c.credential_id, c.tenant_id, c.actor_id, c.roles, p.credential_id AS policy_id,"
                + " p.expires_at_ms FROM " + NpdevApiCredentialTable.NAME + " c LEFT JOIN "
                + NpdevApiCredentialPolicyTable.NAME + " p ON p.credential_id = c.credential_id"
                + " WHERE c.key_hash = ? AND c.status = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash(apiKey));
            statement.setString(2, Status.ACTIVE.name());
            String credentialId;
            boolean hasPolicyRow;
            Principal principal;
            long now = clock.millis();
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                Long expiresAt = nullableLong(resultSet, "expires_at_ms");
                if (expiresAt != null && expiresAt <= now) {
                    return Optional.empty();
                }
                Set<String> roles = normalizeRoles(Set.of(resultSet.getString("roles").split(",")));
                principal = new Principal(
                        resultSet.getString("tenant_id"), resultSet.getString("actor_id"), roles);
                credentialId = resultSet.getString("credential_id");
                hasPolicyRow = resultSet.getString("policy_id") != null;
            }
            touchLastUsed(connection, credentialId, hasPolicyRow, now);
            return Optional.of(principal);
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    /** Best-effort: a failure to record "last used" must never fail an otherwise valid authentication. */
    private static void touchLastUsed(Connection connection, String credentialId, boolean hasPolicyRow, long now) {
        try {
            if (hasPolicyRow) {
                try (PreparedStatement update = connection.prepareStatement("UPDATE "
                        + NpdevApiCredentialPolicyTable.NAME + " SET last_used_at_ms = ? WHERE credential_id = ?"
                        + " AND (last_used_at_ms IS NULL OR last_used_at_ms < ?)")) {
                    update.setLong(1, now);
                    update.setString(2, credentialId);
                    update.setLong(3, now - LAST_USED_REFRESH_MS);
                    update.executeUpdate();
                }
            } else {
                // Legacy key (no policy row yet): create one so "last used" works for it too.
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO "
                        + NpdevApiCredentialPolicyTable.NAME + " (credential_id, last_used_at_ms) VALUES (?, ?)")) {
                    insert.setString(1, credentialId);
                    insert.setLong(2, now);
                    insert.executeUpdate();
                }
            }
        } catch (SQLException ignored) {
            // e.g. a concurrent request created the legacy policy row first
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String normalizeLabel(String label) {
        return label == null || label.isBlank() ? null : label.trim();
    }

    private DataSource requireDataSource() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException(
                    "Credential registry requires a physical database (H2Local/H2Server/Postgres) with internal tables.");
        }
        return dataSource;
    }

    private static String generateRawKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "npk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SEC-8 (B17): public so a caller with a raw key in hand (e.g. {@code SuperUserBootstrapper}'s
     * supplied-raw-key mode, `npdev admin hash-key`'s server-side twin) can compute the SAME hash
     * this service stores and matches against, rather than reimplementing SHA-256-hex a second way. */
    public static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static Set<String> normalizeRoles(Set<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    out.add(role.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        return out.isEmpty() ? Set.of("USER") : Set.copyOf(out);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
