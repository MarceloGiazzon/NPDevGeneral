package com.npdev.adapters.idempotency.postgres;

import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.ports.IdempotencyStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class PostgresIdempotencyStore implements IdempotencyStore {
    private final DataSource dataSource;

    public PostgresIdempotencyStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public Optional<IdempotencyRecord> find(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey
    ) {
        String sql = """
                SELECT tenant_id, idempotency_key, capability, operation, created_at_ms, status, result_json_redacted, error_code
                FROM npdev_idempotency
                WHERE tenant_id = ? AND capability = ? AND operation = ? AND idempotency_key = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, requireValue(tenantId, "tenantId"));
            statement.setString(2, requireValue(capability, "capability"));
            statement.setString(3, requireValue(operation, "operation"));
            statement.setString(4, requireValue(idempotencyKey, "idempotencyKey"));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new IdempotencyRecord(
                        resultSet.getString("tenant_id"),
                        resultSet.getString("idempotency_key"),
                        resultSet.getString("capability"),
                        resultSet.getString("operation"),
                        resultSet.getLong("created_at_ms"),
                        resultSet.getString("status"),
                        resultSet.getString("result_json_redacted"),
                        resultSet.getString("error_code")
                ));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed finding idempotency record", exception);
        }
    }

    @Override
    public void saveSuccess(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey,
            String resultJsonRedacted,
            long createdAtMs
    ) {
        upsert(
                tenantId,
                capability,
                operation,
                idempotencyKey,
                createdAtMs,
                IdempotencyRecord.STATUS_SUCCESS,
                resultJsonRedacted,
                null
        );
    }

    @Override
    public void saveFailure(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey,
            String errorCode,
            long createdAtMs
    ) {
        upsert(
                tenantId,
                capability,
                operation,
                idempotencyKey,
                createdAtMs,
                IdempotencyRecord.STATUS_FAILED,
                null,
                errorCode
        );
    }

    private void upsert(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey,
            long createdAtMs,
            String status,
            String resultJsonRedacted,
            String errorCode
    ) {
        String safeTenant = requireValue(tenantId, "tenantId");
        String safeCapability = requireValue(capability, "capability");
        String safeOperation = requireValue(operation, "operation");
        String safeKey = requireValue(idempotencyKey, "idempotencyKey");
        if (createdAtMs <= 0) {
            throw new IllegalArgumentException("createdAtMs must be > 0");
        }

        int updated = update(
                safeTenant,
                safeCapability,
                safeOperation,
                safeKey,
                createdAtMs,
                status,
                resultJsonRedacted,
                errorCode
        );
        if (updated > 0) {
            return;
        }
        insertOrIgnore(
                safeTenant,
                safeCapability,
                safeOperation,
                safeKey,
                createdAtMs,
                status,
                resultJsonRedacted,
                errorCode
        );
    }

    private int update(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey,
            long createdAtMs,
            String status,
            String resultJsonRedacted,
            String errorCode
    ) {
        String sql = """
                UPDATE npdev_idempotency
                SET created_at_ms = ?, status = ?, result_json_redacted = ?, error_code = ?
                WHERE tenant_id = ? AND capability = ? AND operation = ? AND idempotency_key = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, createdAtMs);
            statement.setString(2, status);
            statement.setString(3, resultJsonRedacted);
            statement.setString(4, errorCode);
            statement.setString(5, tenantId);
            statement.setString(6, capability);
            statement.setString(7, operation);
            statement.setString(8, idempotencyKey);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed updating idempotency record", exception);
        }
    }

    private void insertOrIgnore(
            String tenantId,
            String capability,
            String operation,
            String idempotencyKey,
            long createdAtMs,
            String status,
            String resultJsonRedacted,
            String errorCode
    ) {
        String sql = """
                INSERT INTO npdev_idempotency (
                    tenant_id, capability, operation, idempotency_key,
                    created_at_ms, status, result_json_redacted, error_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, capability);
            statement.setString(3, operation);
            statement.setString(4, idempotencyKey);
            statement.setLong(5, createdAtMs);
            statement.setString(6, status);
            statement.setString(7, resultJsonRedacted);
            statement.setString(8, errorCode);
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isDuplicateKey(exception)) {
                update(tenantId, capability, operation, idempotencyKey, createdAtMs, status, resultJsonRedacted, errorCode);
                return;
            }
            throw new IllegalStateException("Failed inserting idempotency record", exception);
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

    private static String requireValue(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return trimmed;
    }
}
