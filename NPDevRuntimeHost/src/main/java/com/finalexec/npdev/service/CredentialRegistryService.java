package com.finalexec.npdev.service;

import com.npdev.kernel.dbschema.NpdevApiCredentialTable;
import com.npdev.kernel.ports.ApiKeyCredentialResolver;
import org.springframework.beans.factory.ObjectProvider;
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
import java.time.Instant;
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
 */
@Service
public class CredentialRegistryService implements ApiKeyCredentialResolver {

    public enum Status { ACTIVE, REVOKED }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<DataSource> dataSourceProvider;

    public CredentialRegistryService(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    /** Returns the raw key. This is the only place it is ever observable -- callers must save it now. */
    public Map<String, Object> issue(String tenantId, String actorId, Set<String> roles) {
        String tenant = requireNonBlank(tenantId, "tenantId");
        String actor = requireNonBlank(actorId, "actorId");
        Set<String> normalizedRoles = normalizeRoles(roles);
        String rawKey = generateRawKey();
        String credentialId = UUID.randomUUID().toString();

        DataSource dataSource = requireDataSource();
        String sql = "INSERT INTO " + NpdevApiCredentialTable.NAME
                + " (credential_id, key_hash, tenant_id, actor_id, roles, status, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, credentialId);
            statement.setString(2, hash(rawKey));
            statement.setString(3, tenant);
            statement.setString(4, actor);
            statement.setString(5, String.join(",", normalizedRoles));
            statement.setString(6, Status.ACTIVE.name());
            statement.setLong(7, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed issuing credential: " + exception.getMessage(), exception);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credentialId", credentialId);
        body.put("apiKey", rawKey);
        body.put("tenantId", tenant);
        body.put("actorId", actor);
        body.put("roles", normalizedRoles);
        body.put("warning", "This key is shown once and is not retrievable again. Store it now.");
        return body;
    }

    public List<Map<String, Object>> list() {
        DataSource dataSource = requireDataSource();
        String sql = "SELECT credential_id, tenant_id, actor_id, roles, status, created_at_ms FROM "
                + NpdevApiCredentialTable.NAME + " ORDER BY created_at_ms DESC";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("credentialId", resultSet.getString("credential_id"));
                row.put("tenantId", resultSet.getString("tenant_id"));
                row.put("actorId", resultSet.getString("actor_id"));
                row.put("roles", resultSet.getString("roles"));
                row.put("status", resultSet.getString("status"));
                row.put("createdAtMs", resultSet.getLong("created_at_ms"));
                out.add(row);
            }
            return out;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed listing credentials: " + exception.getMessage(), exception);
        }
    }

    public void revoke(String credentialId) {
        String id = requireNonBlank(credentialId, "credentialId");
        DataSource dataSource = requireDataSource();
        String sql = "UPDATE " + NpdevApiCredentialTable.NAME + " SET status = ? WHERE credential_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, Status.REVOKED.name());
            statement.setString(2, id);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Unknown credential: " + id);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed revoking credential '" + id + "': " + exception.getMessage(), exception);
        }
    }

    /** {@link ApiKeyCredentialResolver} entry point: hashes the incoming key, looks up the hash. */
    @Override
    public Optional<Principal> resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return Optional.empty();
        }
        String sql = "SELECT tenant_id, actor_id, roles FROM " + NpdevApiCredentialTable.NAME
                + " WHERE key_hash = ? AND status = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hash(apiKey));
            statement.setString(2, Status.ACTIVE.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                Set<String> roles = normalizeRoles(Set.of(resultSet.getString("roles").split(",")));
                return Optional.of(new Principal(
                        resultSet.getString("tenant_id"), resultSet.getString("actor_id"), roles));
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
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

    private static String hash(String value) {
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
