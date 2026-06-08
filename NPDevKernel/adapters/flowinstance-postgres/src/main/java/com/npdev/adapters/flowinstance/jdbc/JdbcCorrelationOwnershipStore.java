package com.npdev.adapters.flowinstance.jdbc;

import com.npdev.kernel.CorrelationOwnershipViolationException;
import com.npdev.kernel.ports.CorrelationOwnershipStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public class JdbcCorrelationOwnershipStore implements CorrelationOwnershipStore {
    private final DataSource dataSource;

    public JdbcCorrelationOwnershipStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<String> findTenantByCorrelationId(String correlationId) {
        String effectiveCorrelationId = normalizeCorrelationId(correlationId);
        if (effectiveCorrelationId == null) {
            return Optional.empty();
        }
        String sql = """
                SELECT tenant_id
                FROM npdev_correlation_owner
                WHERE correlation_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, effectiveCorrelationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.ofNullable(resultSet.getString("tenant_id"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed loading correlation ownership", exception);
        }
    }

    @Override
    public void claimCorrelation(String correlationId, String tenantId) throws CorrelationOwnershipViolationException {
        String effectiveCorrelationId = requireCorrelationId(correlationId);
        String effectiveTenantId = requireTenantId(tenantId);

        Optional<String> currentOwner = findTenantByCorrelationId(effectiveCorrelationId);
        if (currentOwner.isPresent()) {
            if (!effectiveTenantId.equals(currentOwner.get())) {
                throw new CorrelationOwnershipViolationException(
                        effectiveCorrelationId,
                        currentOwner.get(),
                        effectiveTenantId
                );
            }
            return;
        }

        String sql = """
                INSERT INTO npdev_correlation_owner(correlation_id, tenant_id)
                VALUES (?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, effectiveCorrelationId);
            statement.setString(2, effectiveTenantId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (!isDuplicateKey(exception)) {
                throw new IllegalStateException("Failed claiming correlation ownership", exception);
            }
        }

        Optional<String> postClaimOwner = findTenantByCorrelationId(effectiveCorrelationId);
        if (postClaimOwner.isPresent() && !effectiveTenantId.equals(postClaimOwner.get())) {
            throw new CorrelationOwnershipViolationException(
                    effectiveCorrelationId,
                    postClaimOwner.get(),
                    effectiveTenantId
            );
        }
    }

    private static boolean isDuplicateKey(SQLException exception) {
        String state = exception.getSQLState();
        if ("23505".equals(state)) {
            return true;
        }
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("unique");
    }

    private static String requireCorrelationId(String correlationId) {
        String normalized = normalizeCorrelationId(correlationId);
        if (normalized == null) {
            throw new IllegalArgumentException("correlationId must be non-blank");
        }
        return normalized;
    }

    private static String normalizeCorrelationId(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        String trimmed = correlationId.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String requireTenantId(String tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must be non-blank");
        }
        String trimmed = tenantId.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("tenantId must be non-blank");
        }
        return trimmed;
    }
}

