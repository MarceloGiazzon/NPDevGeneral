package com.finalexec.npdev.service;

import com.npdev.kernel.dbschema.NpdevTenantTable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime registry of tenants (hybrid multitenancy: the signed permission model is unchanged; tenant
 * existence and status are live data). Powers create / list / enable / disable, and the per-request
 * {@code isActive} check that gives "disable" real teeth.
 *
 * <p><b>Fail-open by design</b>: only an explicitly DISABLED tenant is rejected. A tenant with no
 * registry row -- including every app that never registers tenants, and the implicit "default"
 * tenant -- is treated as active, so adding the registry never breaks an existing deployment. The
 * registry adds the ability to <em>suspend</em>, it does not flip the system to deny-by-default.</p>
 */
@Service
public class TenantRegistryService {

    public enum Status { ACTIVE, DISABLED }

    /** Thrown when create() fails because the tenant_id already exists -- distinct from a real DB
     * failure, which still surfaces as IllegalStateException. */
    public static final class TenantAlreadyExistsException extends RuntimeException {
        public TenantAlreadyExistsException(String tenantId) {
            super("Tenant already exists: " + tenantId);
        }
    }

    private final ObjectProvider<DataSource> dataSourceProvider;

    public TenantRegistryService(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    public Map<String, Object> create(String tenantId, String displayName) {
        String id = normalize(tenantId);
        if (id == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        DataSource dataSource = requireDataSource();
        String sql = "INSERT INTO " + NpdevTenantTable.NAME
                + " (tenant_id, display_name, status, created_at_ms) VALUES (?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, displayName == null || displayName.isBlank() ? id : displayName.trim());
            statement.setString(3, Status.ACTIVE.name());
            statement.setLong(4, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isIntegrityConstraintViolation(exception)) {
                throw new TenantAlreadyExistsException(id);
            }
            throw new IllegalStateException("Failed creating tenant '" + id + "': " + exception.getMessage(), exception);
        }
        return view(id, displayName == null || displayName.isBlank() ? id : displayName.trim(), Status.ACTIVE.name());
    }

    public List<Map<String, Object>> list() {
        DataSource dataSource = requireDataSource();
        String sql = "SELECT tenant_id, display_name, status, created_at_ms, persistence_mode FROM "
                + NpdevTenantTable.NAME + " ORDER BY tenant_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = view(
                        resultSet.getString("tenant_id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("status"));
                row.put("createdAtMs", resultSet.getLong("created_at_ms"));
                row.put("persistenceMode", resultSet.getString("persistence_mode"));
                out.add(row);
            }
            return out;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed listing tenants: " + exception.getMessage(), exception);
        }
    }

    /**
     * Live per-tenant driver for {@code persistence.adapter="tenant"}
     * ({@link com.finalexec.db.TenantControlledConceptStoreDecorator}). Valid values: "default"
     * (unwrapped store, the platform default for every existing tenant) | "audited" (every access
     * logged for that tenant only). Rejects anything else up front rather than silently persisting
     * a value the decorator's own equalsIgnoreCase check would just never match.
     */
    public Map<String, Object> setPersistenceMode(String tenantId, String mode) {
        String id = normalize(tenantId);
        if (id == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        String normalizedMode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!"default".equals(normalizedMode) && !"audited".equals(normalizedMode)) {
            throw new IllegalArgumentException(
                    "persistenceMode must be \"default\" or \"audited\", got: " + mode);
        }
        DataSource dataSource = requireDataSource();
        String sql = "UPDATE " + NpdevTenantTable.NAME + " SET persistence_mode = ? WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedMode);
            statement.setString(2, id);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Unknown tenant: " + id);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed updating tenant '" + id + "': " + exception.getMessage(), exception);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", id);
        row.put("persistenceMode", normalizedMode);
        return row;
    }

    public Map<String, Object> setStatus(String tenantId, Status status) {
        String id = normalize(tenantId);
        if (id == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        DataSource dataSource = requireDataSource();
        String sql = "UPDATE " + NpdevTenantTable.NAME + " SET status = ? WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, id);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Unknown tenant: " + id);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed updating tenant '" + id + "': " + exception.getMessage(), exception);
        }
        return view(id, null, status.name());
    }

    /**
     * Per-request gate: returns false only when the tenant has an explicit DISABLED row. Unknown
     * tenants and an absent/unreadable registry both return true (fail-open).
     */
    public boolean isActive(String tenantId) {
        String id = normalize(tenantId);
        if (id == null) {
            return true;
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return true;
        }
        String sql = "SELECT status FROM " + NpdevTenantTable.NAME + " WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return true;
                }
                return !Status.DISABLED.name().equalsIgnoreCase(resultSet.getString("status"));
            }
        } catch (SQLException exception) {
            return true;
        }
    }

    private DataSource requireDataSource() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new IllegalStateException(
                    "Tenant registry requires a physical database (H2Local/H2Server/Postgres) with internal tables.");
        }
        return dataSource;
    }

    private static Map<String, Object> view(String tenantId, String displayName, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", tenantId);
        if (displayName != null) {
            row.put("displayName", displayName);
        }
        row.put("status", status);
        return row;
    }

    /**
     * SQLState class "23" (integrity constraint violation, e.g. "23505" unique violation) is
     * standardized across H2 and PostgreSQL, unlike vendor-specific exception subclasses --
     * checking it directly is more reliable than relying on driver-specific exception types.
     */
    private static boolean isIntegrityConstraintViolation(SQLException exception) {
        String sqlState = exception.getSQLState();
        return sqlState != null && sqlState.startsWith("23");
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isBlank() ? null : trimmed;
    }
}
