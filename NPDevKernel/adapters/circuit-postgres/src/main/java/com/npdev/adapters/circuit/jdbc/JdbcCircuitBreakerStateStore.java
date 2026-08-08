package com.npdev.adapters.circuit.jdbc;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitBreakerStateSummary;
import com.npdev.kernel.capability.CircuitBreakerTransitions;
import com.npdev.kernel.capability.CircuitState;
import com.npdev.kernel.ports.CircuitBreakerStateStore;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

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
    private final SqlDialect dialect;

    public JdbcCircuitBreakerStateStore(DataSource dataSource) {
        this(dataSource, SqlDialects.active());
    }

    /**
     * Explicit dialect, for the conformance suite and for a host that pins its engine at boot.
     *
     * <p>The no-dialect constructors resolve {@link SqlDialects#active()}, which is the engine the
     * app was GENERATED for -- not one detected from the connection. Detection would make emitted
     * SQL depend on runtime discovery, so a misconfiguration would quietly produce different SQL
     * instead of failing.
     */
    public JdbcCircuitBreakerStateStore(DataSource dataSource, SqlDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
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

    /**
     * REG-37: read, decide and write inside ONE transaction with the row locked, so N concurrent
     * failures against the same key produce exactly N increments.
     *
     * <p>The previous shape -- caller does {@code get()}, computes, then {@code put()} -- issues two
     * independent statements on two independent connections with nothing serialising them, so a burst
     * of failures collapses into a single increment and the circuit opens late or never.</p>
     *
     * <p><b>Why the row is seeded first.</b> {@code SELECT ... FOR UPDATE} locks rows, and a row that
     * does not exist cannot be locked -- two concurrent <em>first</em> failures would both find
     * nothing, both compute 1, and one would lose. So a CLOSED/zero row is inserted first (duplicate
     * key tolerated, since another thread racing to the same conclusion is exactly the expected case),
     * and only then is it locked. Seeding a zero row is harmless: it is the same state {@link #get}
     * already synthesises for a missing key.</p>
     */
    @Override
    public CircuitBreakerState recordFailure(CapabilityOpKey key, long nowMs, int openAfterFailures, long openMs) {
        Objects.requireNonNull(key, "key");
        seedIfAbsent(key);

        String selectForUpdate = """
                SELECT state, consecutive_failures, opened_at_ms, last_failure_at_ms, half_open_allowed_at_ms, half_open_trial_count
                FROM npdev_circuit_breaker
                WHERE tenant_id = ? AND capability = ? AND operation = ?
                FOR UPDATE
                """;
        String update = """
                UPDATE npdev_circuit_breaker
                SET state = ?, consecutive_failures = ?, opened_at_ms = ?, last_failure_at_ms = ?,
                    half_open_allowed_at_ms = ?, half_open_trial_count = ?
                WHERE tenant_id = ? AND capability = ? AND operation = ?
                """;

        Connection connection = null;
        boolean previousAutoCommit = true;
        try {
            connection = dataSource.getConnection();
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            CircuitBreakerState current = null;
            try (PreparedStatement statement = connection.prepareStatement(selectForUpdate)) {
                statement.setString(1, key.tenantId());
                statement.setString(2, key.capabilityName());
                statement.setString(3, key.operationName());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        current = new CircuitBreakerState(
                                parseState(resultSet.getString("state")),
                                resultSet.getInt("consecutive_failures"),
                                resultSet.getLong("opened_at_ms"),
                                resultSet.getLong("last_failure_at_ms"),
                                resultSet.getLong("half_open_allowed_at_ms"),
                                resultSet.getInt("half_open_trial_count")
                        );
                    }
                }
            }

            CircuitBreakerState next = CircuitBreakerTransitions.afterFailure(current, nowMs, openAfterFailures, openMs);
            try (PreparedStatement statement = connection.prepareStatement(update)) {
                statement.setString(1, next.state().name());
                statement.setInt(2, next.consecutiveFailures());
                statement.setLong(3, next.openedAtMs());
                statement.setLong(4, next.lastFailureAtMs());
                statement.setLong(5, next.halfOpenAllowedAtMs());
                statement.setInt(6, next.halfOpenTrialCount());
                statement.setString(7, key.tenantId());
                statement.setString(8, key.capabilityName());
                statement.setString(9, key.operationName());
                statement.executeUpdate();
            }
            connection.commit();
            return next;
        } catch (SQLException exception) {
            rollbackQuietly(connection);
            throw new IllegalStateException("Failed recording circuit breaker failure", exception);
        } finally {
            closeQuietly(connection, previousAutoCommit);
        }
    }

    /**
     * Create a CLOSED/zero row for {@code key} if there is not one already, and otherwise do nothing
     * at all.
     *
     * <p>Deliberately NOT {@link #insertOrIgnore}: despite the name, that method <em>upserts</em> --
     * on a duplicate key it falls through to {@code update(key, state)}. Seeding through it therefore
     * reset the counter to zero at the start of every single {@code recordFailure}, so the count never
     * exceeded 1. The concurrency test caught it immediately (expected 200, got 1), which is the
     * argument for asserting the counter's VALUE rather than merely that it moved.</p>
     */
    private void seedIfAbsent(CapabilityOpKey key) {
        String sql = """
                INSERT INTO npdev_circuit_breaker (
                    tenant_id, capability, operation, state, consecutive_failures,
                    opened_at_ms, last_failure_at_ms, half_open_allowed_at_ms, half_open_trial_count
                ) VALUES (?, ?, ?, ?, 0, 0, 0, 0, 0)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.tenantId());
            statement.setString(2, key.capabilityName());
            statement.setString(3, key.operationName());
            statement.setString(4, CircuitState.CLOSED.name());
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (isDuplicateKey(exception)) {
                return;  // another thread seeded it first -- the expected outcome, not an error
            }
            throw new IllegalStateException("Failed seeding circuit breaker state", exception);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting; a rollback that also fails adds nothing.
        }
    }

    private static void closeQuietly(Connection connection, boolean restoreAutoCommit) {
        if (connection == null) {
            return;
        }
        try (connection) {
            // Restore autoCommit before returning the connection: a pooled connection handed back in
            // manual-commit mode silently breaks whichever unrelated caller borrows it next.
            connection.setAutoCommit(restoreAutoCommit);
        } catch (SQLException ignored) {
            // Nothing useful to do while unwinding.
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
        String sql = dialect.paginated("""
                SELECT tenant_id, capability, operation, state, consecutive_failures, opened_at_ms,
                       last_failure_at_ms, half_open_allowed_at_ms, half_open_trial_count
                FROM npdev_circuit_breaker
                WHERE tenant_id = ?
                  AND (? IS NULL OR capability = ?)
                  AND (? IS NULL OR operation = ?)
                ORDER BY capability ASC, operation ASC
                """);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            String scopedCapability = normalize(capabilityName);
            String scopedOperation = normalize(operationName);
            statement.setString(1, scopedTenant);
            statement.setString(2, scopedCapability);
            statement.setString(3, scopedCapability);
            statement.setString(4, scopedOperation);
            statement.setString(5, scopedOperation);
            int pageIndex = 6;
            for (int pageValue : dialect.limitOffset().values(effectiveLimit, effectiveOffset)) {
                statement.setInt(pageIndex++, pageValue);
            }
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

