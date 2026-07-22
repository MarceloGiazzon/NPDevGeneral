package com.npdev.adapters.circuit.jdbc;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitBreakerStateSummary;
import com.npdev.kernel.capability.CircuitState;
import com.npdev.kernel.ports.CircuitBreakerStateStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class JdbcCircuitBreakerStateStore implements CircuitBreakerStateStore {
    private final DataSource dataSource;

    public JdbcCircuitBreakerStateStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public CircuitBreakerState get(CapabilityOpKey key) {
        Objects.requireNonNull(key, "key");
        String sql = """
                SELECT state, consecutive_failures, opened_at_ms, last_failure_at_ms, half_open_allowed_at_ms, half_open_trial_count
                FROM npdev_circuit_breaker
                WHERE tenant_id = ? AND capability = ? AND operation = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.capabilityName());
            statement.setString(3, key.operationName());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return CircuitBreakerState.closed();
                }
                return new CircuitBreakerState(
                        parseState(resultSet.getString("state")),
                        resultSet.getInt("consecutive_failures"),
                        resultSet.getLong("opened_at_ms"),
                        resultSet.getLong("last_failure_at_ms"),
                        resultSet.getLong("half_open_allowed_at_ms"),
                        resultSet.getInt("half_open_trial_count")
                );
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed loading circuit breaker state", exception);
        }
    }

    @Override
    public void put(CapabilityOpKey key, CircuitBreakerState state) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(state, "state");
        int updated = update(key, state);
        if (updated > 0) {
            return;
        }
        insertOrIgnore(key, state);
    }

    @Override
    public void reset(CapabilityOpKey key) {
        Objects.requireNonNull(key, "key");
        String sql = """
                DELETE FROM npdev_circuit_breaker
                WHERE tenant_id = ? AND capability = ? AND operation = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.capabilityName());
            statement.setString(3, key.operationName());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed resetting circuit breaker state", exception);
        }
    }

    @Override
    public List<CircuitBreakerStateSummary> listStates(
            String tenantId,
            String capabilityName,
            String operationName,
            int limit,
            int offset
    ) {
        String scopedTenant = normalize(tenantId);
        if (scopedTenant == null) {
            return List.of();
        }
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 1000);
        int effectiveOffset = Math.max(offset, 0);
        String sql = """
                SELECT tenant_id, capability, operation, state, consecutive_failures, opened_at_ms,
                       last_failure_at_ms, half_open_allowed_at_ms, half_open_trial_count
                FROM npdev_circuit_breaker
                WHERE tenant_id = ?
                  AND (? IS NULL OR capability = ?)
                  AND (? IS NULL OR operation = ?)
                ORDER BY capability ASC, operation ASC
                LIMIT ? OFFSET ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            String scopedCapability = normalize(capabilityName);
            String scopedOperation = normalize(operationName);
            statement.setString(1, scopedTenant);
            statement.setString(2, scopedCapability);
            statement.setString(3, scopedCapability);
            statement.setString(4, scopedOperation);
            statement.setString(5, scopedOperation);
            statement.setInt(6, effectiveLimit);
            statement.setInt(7, effectiveOffset);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<CircuitBreakerStateSummary> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(new CircuitBreakerStateSummary(
                            resultSet.getString("tenant_id"),
                            resultSet.getString("capability"),
                            resultSet.getString("operation"),
                            parseState(resultSet.getString("state")),
                            resultSet.getInt("consecutive_failures"),
                            resultSet.getLong("opened_at_ms"),
                            resultSet.getLong("last_failure_at_ms"),
                            resultSet.getLong("half_open_allowed_at_ms"),
                            resultSet.getInt("half_open_trial_count")
                    ));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed listing circuit breaker states", exception);
        }
    }

    private int update(CapabilityOpKey key, CircuitBreakerState state) {
        String sql = """
                UPDATE npdev_circuit_breaker
                SET state = ?, consecutive_failures = ?, opened_at_ms = ?, last_failure_at_ms = ?,
                    half_open_allowed_at_ms = ?, half_open_trial_count = ?
                WHERE tenant_id = ? AND capability = ? AND operation = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.state().name());
            statement.setInt(2, state.consecutiveFailures());
            statement.setLong(3, state.openedAtMs());
            statement.setLong(4, state.lastFailureAtMs());
            statement.setLong(5, state.halfOpenAllowedAtMs());
            statement.setInt(6, state.halfOpenTrialCount());
            statement.setString(7, key.tenantId());
            statement.setString(8, key.capabilityName());
            statement.setString(9, key.operationName());
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed updating circuit breaker state", exception);
        }
    }

    private void insertOrIgnore(CapabilityOpKey key, CircuitBreakerState state) {
        String sql = """
                INSERT INTO npdev_circuit_breaker (
                    tenant_id, capability, operation, state, consecutive_failures,
                    opened_at_ms, last_failure_at_ms, half_open_allowed_at_ms, half_open_trial_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.capabilityName());
            statement.setString(3, key.operationName());
            statement.setString(4, state.state().name());
            statement.setInt(5, state.consecutiveFailures());
            statement.setLong(6, state.openedAtMs());
            statement.setLong(7, state.lastFailureAtMs());
            statement.setLong(8, state.halfOpenAllowedAtMs());
            statement.setInt(9, state.halfOpenTrialCount());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isDuplicateKey(exception)) {
                update(key, state);
                return;
            }
            throw new IllegalStateException("Failed inserting circuit breaker state", exception);
        }
    }

    private static CircuitState parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return CircuitState.CLOSED;
        }
        try {
            return CircuitState.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return CircuitState.CLOSED;
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

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}

