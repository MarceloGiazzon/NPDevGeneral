package com.npdev.adapters.flowinstance.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.exec.ExecutionSummary;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.execution.FlowInstanceStatus;
import com.npdev.kernel.ports.ExecutionSummaryStore;
import com.npdev.kernel.ports.FlowInstanceStore;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.npdev.kernel.storage.sql.StorageCapability;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class JdbcFlowInstanceStore implements FlowInstanceStore, ExecutionSummaryStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * R8c (RUN-2): fallback lease when a caller passes a non-positive {@code leaseMillis} to
     * {@link #claimWaitingEligibleToResume} -- 15x {@code ResumeSchedulerRunner}'s default 2s poll
     * interval, long enough that a normal resume attempt finishes well within it, short enough that
     * a claimant that crashes mid-resume does not block that row for long.
     */
    private static final long DEFAULT_CLAIM_LEASE_MILLIS = 30_000L;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final SqlDialect dialect;

    public JdbcFlowInstanceStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    public JdbcFlowInstanceStore(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, SqlDialects.active());
    }

    /** Explicit dialect, for the conformance suite and for a host that pins its engine at boot. */
    public JdbcFlowInstanceStore(DataSource dataSource, ObjectMapper objectMapper, SqlDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public void save(FlowInstance instance) {
        upsert(instance);
    }

    @Override
    public void update(FlowInstance instance) {
        upsert(instance);
    }

    @Override
    public Optional<FlowInstance> findByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                SELECT execution_id, flow_name, correlation_id, status, current_step_index,
                       waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at,
                       resume_attempt_count, last_resume_at, last_resume_error_code,
                       next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                FROM npdev_flow_instance
                WHERE execution_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readInstance(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed loading flow instance by executionId: " + executionId, exception);
        }
    }

    @Override
    public List<FlowInstance> findWaitingByCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT execution_id, flow_name, correlation_id, status, current_step_index,
                       waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at,
                       resume_attempt_count, last_resume_at, last_resume_error_code,
                       next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                FROM npdev_flow_instance
                WHERE status = 'WAITING_EVENT' AND correlation_id = ?
                ORDER BY updated_at ASC, execution_id ASC
                """;
        return queryWaiting(sql, correlationId);
    }

    @Override
    public List<FlowInstance> findWaitingByEvent(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT execution_id, flow_name, correlation_id, status, current_step_index,
                       waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at,
                       resume_attempt_count, last_resume_at, last_resume_error_code,
                       next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                FROM npdev_flow_instance
                WHERE status = 'WAITING_EVENT' AND waiting_for_event_name = ?
                ORDER BY updated_at ASC, execution_id ASC
                """;
        return queryWaiting(sql, eventName);
    }

    @Override
    public List<FlowInstance> findAllWaiting(int limit) {
        int effectiveLimit = limit <= 0 ? 500 : limit;
        String sql = dialect.limited("""
                SELECT execution_id, flow_name, correlation_id, status, current_step_index,
                       waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at,
                       resume_attempt_count, last_resume_at, last_resume_error_code,
                       next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                FROM npdev_flow_instance
                WHERE status = 'WAITING_EVENT'
                ORDER BY updated_at ASC, execution_id ASC
                """);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, effectiveLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FlowInstance> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readInstance(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying waiting flow instances", exception);
        }
    }

    @Override
    public List<FlowInstance> findWaitingEligibleToResume(String tenantId, long nowEpochMs, int limit) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        // RUN-3 (R8b): was a literal "NULLS FIRST" here -- Postgres/H2-only syntax that MySQL has
        // never supported and SQL Server has no equivalent for at all. dialect.nullsFirstAscending
        // returns a CASE-based tie-breaker that sorts identically on all four engines; see its
        // javadoc for why this is a dialect method despite having only one implementation.
        String sql = dialect.limited("""
                SELECT execution_id, flow_name, correlation_id, status, current_step_index,
                       waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at,
                       resume_attempt_count, last_resume_at, last_resume_error_code,
                       next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                FROM npdev_flow_instance
                WHERE tenant_id = ?
                  AND status = 'WAITING_EVENT'
                  AND (next_eligible_resume_at IS NULL OR next_eligible_resume_at <= ?)
                ORDER BY %s, updated_at DESC, execution_id ASC
                """.formatted(dialect.nullsFirstAscending("next_eligible_resume_at")));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, effectiveTenantId);
            statement.setTimestamp(2, new Timestamp(nowEpochMs));
            statement.setInt(3, effectiveLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FlowInstance> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readInstance(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying resume-eligible waiting instances", exception);
        }
    }

    /**
     * R8c (RUN-2): the real claim, and the one override of {@link
     * FlowInstanceStore#claimWaitingEligibleToResume}'s no-op default in this whole codebase. One
     * transaction does both halves atomically -- {@code SELECT ... FOR UPDATE SKIP LOCKED} to pick
     * the batch (so a competing claimant on another connection never blocks on, and never re-picks,
     * a row this transaction is about to claim), then an {@code UPDATE} stamping {@code claimed_by}/
     * {@code claimed_until} on exactly those rows, then commit. A row already claimed by someone
     * else with an unexpired lease fails the {@code claimed_until} predicate and is invisible to
     * this query entirely -- SKIP LOCKED only matters for the narrower race where two claimants
     * reach the WHERE-eligible set at the same instant, before either has committed its UPDATE.
     */
    @Override
    public List<FlowInstance> claimWaitingEligibleToResume(
            String tenantId, long nowEpochMs, long leaseMillis, String claimantId, int limit) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        long effectiveLeaseMillis = leaseMillis > 0 ? leaseMillis : DEFAULT_CLAIM_LEASE_MILLIS;
        // X0 rule: ask before building skip-locked SQL, rather than assume every future engine
        // answers yes just because all four supported today do (StorageCapability#SKIP_LOCKED_READS).
        dialect.require(StorageCapability.SKIP_LOCKED_READS);
        String sql = dialect.selectForUpdateSkipLocked(
                "execution_id, flow_name, correlation_id, status, current_step_index, "
                        + "waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at, "
                        + "resume_attempt_count, last_resume_at, last_resume_error_code, "
                        + "next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, "
                        + "last_error_message, failed_at",
                "npdev_flow_instance",
                "tenant_id = ? AND status = 'WAITING_EVENT' "
                        + "AND (next_eligible_resume_at IS NULL OR next_eligible_resume_at <= ?) "
                        + "AND (claimed_until IS NULL OR claimed_until < ?)",
                dialect.nullsFirstAscending("next_eligible_resume_at") + ", updated_at DESC, execution_id ASC",
                effectiveLimit);
        Timestamp now = new Timestamp(nowEpochMs);
        Timestamp leaseExpiry = new Timestamp(nowEpochMs + effectiveLeaseMillis);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<FlowInstance> claimed = selectAndLockEligible(connection, sql, effectiveTenantId, now);
                if (!claimed.isEmpty()) {
                    markClaimed(connection, claimed, claimantId, leaseExpiry);
                }
                connection.commit();
                return claimed;
            } catch (SQLException failure) {
                rollbackQuietly(connection);
                throw new IllegalStateException("Failed claiming resume-eligible waiting instances", failure);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed claiming resume-eligible waiting instances", exception);
        }
    }

    private List<FlowInstance> selectAndLockEligible(
            Connection connection, String sql, String tenantId, Timestamp now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, tenantId);
            select.setTimestamp(2, now);
            select.setTimestamp(3, now);
            try (ResultSet resultSet = select.executeQuery()) {
                List<FlowInstance> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readInstance(resultSet));
                }
                return List.copyOf(out);
            }
        }
    }

    private void markClaimed(
            Connection connection, List<FlowInstance> claimed, String claimantId, Timestamp leaseExpiry)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE npdev_flow_instance SET claimed_by = ?, claimed_until = ? WHERE execution_id = ?")) {
            for (FlowInstance instance : claimed) {
                update.setString(1, claimantId);
                update.setTimestamp(2, leaseExpiry);
                update.setString(3, instance.executionId());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort cleanup only -- the claim attempt has already failed
        }
    }

    @Override
    public List<FlowInstance> findStaleWaiting(String tenantId, long olderThanEpochMs, int limit, int offset) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = normalizeOffset(offset);
        String sql = dialect.paginated("""
                SELECT execution_id, flow_name, correlation_id, status, current_step_index,
                       waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at,
                       resume_attempt_count, last_resume_at, last_resume_error_code,
                       next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                FROM npdev_flow_instance
                WHERE tenant_id = ?
                  AND status = 'WAITING_EVENT'
                  AND COALESCE(last_progress_at, created_at) <= ?
                ORDER BY COALESCE(last_progress_at, created_at) ASC, execution_id ASC
                """);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, effectiveTenantId);
            statement.setTimestamp(2, new Timestamp(olderThanEpochMs));
            int pageIndex = 3;
            for (int pageValue : dialect.limitOffset().values(effectiveLimit, effectiveOffset)) {
                statement.setInt(pageIndex++, pageValue);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FlowInstance> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readInstance(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying stale waiting instances", exception);
        }
    }

    @Override
    public List<FlowInstance> findRecent(String tenantId, int limit, int offset) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = normalizeOffset(offset);
        String sql = dialect.paginated("""
                SELECT execution_id, flow_name, correlation_id, status, current_step_index,
                       waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at,
                       resume_attempt_count, last_resume_at, last_resume_error_code,
                       next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                FROM npdev_flow_instance
                WHERE tenant_id = ?
                ORDER BY updated_at DESC, execution_id DESC
                """);
        return queryScoped(sql, effectiveTenantId, effectiveLimit, effectiveOffset);
    }

    @Override
    public List<FlowInstance> findWaiting(String tenantId, int limit, int offset) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = normalizeOffset(offset);
        String sql = dialect.paginated("""
                SELECT execution_id, flow_name, correlation_id, status, current_step_index,
                       waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at,
                       resume_attempt_count, last_resume_at, last_resume_error_code,
                       next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                FROM npdev_flow_instance
                WHERE tenant_id = ? AND status = 'WAITING_EVENT'
                ORDER BY updated_at DESC, execution_id DESC
                """);
        return queryScoped(sql, effectiveTenantId, effectiveLimit, effectiveOffset);
    }

    @Override
    public List<FlowInstance> findByCorrelationId(String tenantId, String correlationId) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT execution_id, flow_name, correlation_id, status, current_step_index,
                       waiting_for_event_name, state_json, tenant_id, actor_id, created_at, updated_at,
                       resume_attempt_count, last_resume_at, last_resume_error_code,
                       next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                FROM npdev_flow_instance
                WHERE tenant_id = ? AND correlation_id = ?
                ORDER BY updated_at DESC, execution_id DESC
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, effectiveTenantId);
            statement.setString(2, correlationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FlowInstance> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readInstance(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying flow instances by correlation", exception);
        }
    }

    @Override
    public List<ExecutionSummary> listSummaries(String tenantId, String mode, int limit, int offset) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = normalizeOffset(offset);
        boolean waitingOnly = "waiting".equalsIgnoreCase(mode);
        String sql = waitingOnly
                ? dialect.paginated("""
                SELECT execution_id, tenant_id, correlation_id, flow_name, status, current_step_index,
                       waiting_for_event_name, updated_at, resume_attempt_count, last_resume_at,
                       last_resume_error_code, next_eligible_resume_at, last_progress_at,
                       last_error_kind, last_error_code, failed_at
                FROM npdev_flow_instance
                WHERE tenant_id = ? AND status = 'WAITING_EVENT'
                ORDER BY updated_at DESC, execution_id DESC
                """)
                : dialect.paginated("""
                SELECT execution_id, tenant_id, correlation_id, flow_name, status, current_step_index,
                       waiting_for_event_name, updated_at, resume_attempt_count, last_resume_at,
                       last_resume_error_code, next_eligible_resume_at, last_progress_at,
                       last_error_kind, last_error_code, failed_at
                FROM npdev_flow_instance
                WHERE tenant_id = ?
                ORDER BY updated_at DESC, execution_id DESC
                """);
        return querySummaries(sql, effectiveTenantId, effectiveLimit, effectiveOffset);
    }

    @Override
    public List<ExecutionSummary> listByCorrelation(String tenantId, String correlationId, int limit, int offset) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = normalizeOffset(offset);
        String sql = dialect.paginated("""
                SELECT execution_id, tenant_id, correlation_id, flow_name, status, current_step_index,
                       waiting_for_event_name, updated_at, resume_attempt_count, last_resume_at,
                       last_resume_error_code, next_eligible_resume_at, last_progress_at,
                       last_error_kind, last_error_code, failed_at
                FROM npdev_flow_instance
                WHERE tenant_id = ? AND correlation_id = ?
                ORDER BY updated_at DESC, execution_id DESC
                """);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, effectiveTenantId);
            statement.setString(2, correlationId);
            int pageIndex = 3;
            for (int pageValue : dialect.limitOffset().values(effectiveLimit, effectiveOffset)) {
                statement.setInt(pageIndex++, pageValue);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutionSummary> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readSummary(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying execution summaries by correlation", exception);
        }
    }

    @Override
    public List<ExecutionSummary> listFailureSummaries(String tenantId, int limit, int offset) {
        return listByStatus(tenantId, FlowInstanceStatus.FAILED_PERMANENT, limit, offset);
    }

    @Override
    public List<ExecutionSummary> listStuckSummaries(String tenantId, int limit, int offset) {
        return listByStatus(tenantId, FlowInstanceStatus.STUCK, limit, offset);
    }

    private void upsert(FlowInstance instance) {
        Objects.requireNonNull(instance, "instance");
        String updateSql = """
                UPDATE npdev_flow_instance
                SET flow_name = ?,
                    correlation_id = ?,
                    tenant_id = ?,
                    actor_id = ?,
                    status = ?,
                    current_step_index = ?,
                    waiting_for_event_name = ?,
                    state_json = ?,
                    updated_at = ?,
                    resume_attempt_count = ?,
                    last_resume_at = ?,
                    last_resume_error_code = ?,
                    next_eligible_resume_at = ?,
                    last_progress_at = ?,
                    last_error_kind = ?,
                    last_error_code = ?,
                    last_error_message = ?,
                    failed_at = ?
                WHERE execution_id = ?
                """;
        String insertSql = """
                INSERT INTO npdev_flow_instance (
                    execution_id, flow_name, correlation_id, tenant_id, actor_id, status, current_step_index,
                    waiting_for_event_name, state_json, created_at, updated_at,
                    resume_attempt_count, last_resume_at, last_resume_error_code,
                    next_eligible_resume_at, last_progress_at, last_error_kind, last_error_code, last_error_message, failed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            update.setString(1, instance.flowName());
            update.setString(2, instance.correlationId());
            update.setString(3, instance.tenantId());
            update.setString(4, instance.actorId());
            update.setString(5, instance.status().name());
            update.setInt(6, instance.currentStepIndex());
            update.setString(7, instance.waitingForEventName());
            update.setString(8, writeStateJson(instance.state()));
            update.setTimestamp(9, new Timestamp(instance.updatedAtEpochMs()));
            update.setInt(10, instance.resumeAttemptCount());
            update.setTimestamp(11, toTimestamp(instance.lastResumeAtEpochMs()));
            update.setString(12, instance.lastResumeErrorCode());
            update.setTimestamp(13, toTimestamp(instance.nextEligibleResumeAtEpochMs()));
            update.setTimestamp(14, toTimestamp(instance.lastProgressAtEpochMs()));
            update.setString(15, instance.lastErrorKind());
            update.setString(16, instance.lastErrorCode());
            update.setString(17, instance.lastErrorMessage());
            update.setTimestamp(18, toTimestamp(instance.failedAtEpochMs()));
            update.setString(19, instance.executionId());

            int updatedRows = update.executeUpdate();
            if (updatedRows > 0) {
                return;
            }

            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                insert.setString(1, instance.executionId());
                insert.setString(2, instance.flowName());
                insert.setString(3, instance.correlationId());
                insert.setString(4, instance.tenantId());
                insert.setString(5, instance.actorId());
                insert.setString(6, instance.status().name());
                insert.setInt(7, instance.currentStepIndex());
                insert.setString(8, instance.waitingForEventName());
                insert.setString(9, writeStateJson(instance.state()));
                insert.setTimestamp(10, new Timestamp(instance.createdAtEpochMs()));
                insert.setTimestamp(11, new Timestamp(instance.updatedAtEpochMs()));
                insert.setInt(12, instance.resumeAttemptCount());
                insert.setTimestamp(13, toTimestamp(instance.lastResumeAtEpochMs()));
                insert.setString(14, instance.lastResumeErrorCode());
                insert.setTimestamp(15, toTimestamp(instance.nextEligibleResumeAtEpochMs()));
                insert.setTimestamp(16, toTimestamp(instance.lastProgressAtEpochMs()));
                insert.setString(17, instance.lastErrorKind());
                insert.setString(18, instance.lastErrorCode());
                insert.setString(19, instance.lastErrorMessage());
                insert.setTimestamp(20, toTimestamp(instance.failedAtEpochMs()));
                insert.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed saving flow instance: " + instance.executionId(), exception);
        }
    }

    private List<ExecutionSummary> listByStatus(
            String tenantId,
            FlowInstanceStatus status,
            int limit,
            int offset
    ) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null || status == null) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = normalizeOffset(offset);
        String sql = dialect.paginated("""
                SELECT execution_id, tenant_id, correlation_id, flow_name, status, current_step_index,
                       waiting_for_event_name, updated_at, resume_attempt_count, last_resume_at,
                       last_resume_error_code, next_eligible_resume_at, last_progress_at,
                       last_error_kind, last_error_code, failed_at
                FROM npdev_flow_instance
                WHERE tenant_id = ? AND status = ?
                ORDER BY updated_at DESC, execution_id DESC
                """);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, effectiveTenantId);
            statement.setString(2, status.name());
            int pageIndex = 3;
            for (int pageValue : dialect.limitOffset().values(effectiveLimit, effectiveOffset)) {
                statement.setInt(pageIndex++, pageValue);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutionSummary> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readSummary(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying execution summaries by status: " + status, exception);
        }
    }

    private List<FlowInstance> queryWaiting(String sql, String filterValue) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, filterValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FlowInstance> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readInstance(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying waiting flow instances", exception);
        }
    }

    private List<FlowInstance> queryScoped(
            String sql,
            String tenantId,
            int limit,
            int offset
    ) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            int pageIndex = 2;
            for (int pageValue : dialect.limitOffset().values(limit, offset)) {
                statement.setInt(pageIndex++, pageValue);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<FlowInstance> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readInstance(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying scoped flow instances", exception);
        }
    }

    private List<ExecutionSummary> querySummaries(
            String sql,
            String tenantId,
            int limit,
            int offset
    ) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            int pageIndex = 2;
            for (int pageValue : dialect.limitOffset().values(limit, offset)) {
                statement.setInt(pageIndex++, pageValue);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ExecutionSummary> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readSummary(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying execution summaries", exception);
        }
    }

    private FlowInstance readInstance(ResultSet resultSet) throws SQLException {
        String executionId = resultSet.getString("execution_id");
        String flowName = resultSet.getString("flow_name");
        String correlationId = resultSet.getString("correlation_id");
        String tenantId = resultSet.getString("tenant_id");
        String actorId = resultSet.getString("actor_id");
        String status = resultSet.getString("status");
        int currentStepIndex = resultSet.getInt("current_step_index");
        String waitingForEventName = resultSet.getString("waiting_for_event_name");
        String stateJson = resultSet.getString("state_json");
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        int resumeAttemptCount = resultSet.getInt("resume_attempt_count");
        Timestamp lastResumeAt = resultSet.getTimestamp("last_resume_at");
        String lastResumeErrorCode = resultSet.getString("last_resume_error_code");
        Timestamp nextEligibleResumeAt = resultSet.getTimestamp("next_eligible_resume_at");
        Timestamp lastProgressAt = resultSet.getTimestamp("last_progress_at");
        String lastErrorKind = resultSet.getString("last_error_kind");
        String lastErrorCode = resultSet.getString("last_error_code");
        String lastErrorMessage = resultSet.getString("last_error_message");
        Timestamp failedAt = resultSet.getTimestamp("failed_at");
        return new FlowInstance(
                executionId,
                flowName,
                correlationId,
                tenantId,
                actorId,
                currentStepIndex,
                parseStatus(status),
                readStateJson(stateJson),
                waitingForEventName,
                createdAt == null ? 0L : createdAt.getTime(),
                updatedAt == null ? 0L : updatedAt.getTime(),
                resumeAttemptCount,
                lastResumeAt == null ? null : lastResumeAt.getTime(),
                lastResumeErrorCode,
                nextEligibleResumeAt == null ? null : nextEligibleResumeAt.getTime(),
                lastProgressAt == null ? null : lastProgressAt.getTime(),
                lastErrorKind,
                lastErrorCode,
                lastErrorMessage,
                failedAt == null ? null : failedAt.getTime()
        );
    }

    private String writeStateJson(Map<String, Object> state) {
        try {
            return objectMapper.writeValueAsString(state == null ? Map.of() : state);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed serializing flow instance state", exception);
        }
    }

    private Map<String, Object> readStateJson(String stateJson) {
        if (stateJson == null || stateJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(stateJson, MAP_TYPE);
            return Map.copyOf(new LinkedHashMap<>(parsed));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed deserializing flow instance state", exception);
        }
    }

    private static FlowInstanceStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalStateException("Flow instance status must be non-blank");
        }
        try {
            return FlowInstanceStatus.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unknown flow instance status: " + status, exception);
        }
    }

    private static ExecutionSummary readSummary(ResultSet resultSet) throws SQLException {
        Timestamp updatedAt = resultSet.getTimestamp("updated_at");
        Timestamp lastResumeAt = resultSet.getTimestamp("last_resume_at");
        Timestamp nextEligibleResumeAt = resultSet.getTimestamp("next_eligible_resume_at");
        Timestamp lastProgressAt = resultSet.getTimestamp("last_progress_at");
        Timestamp failedAt = resultSet.getTimestamp("failed_at");
        return new ExecutionSummary(
                resultSet.getString("execution_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("correlation_id"),
                resultSet.getString("flow_name"),
                resultSet.getString("status"),
                resultSet.getInt("current_step_index"),
                resultSet.getString("waiting_for_event_name"),
                updatedAt == null ? 0L : updatedAt.getTime(),
                resultSet.getInt("resume_attempt_count"),
                lastResumeAt == null ? null : lastResumeAt.getTime(),
                resultSet.getString("last_resume_error_code"),
                nextEligibleResumeAt == null ? null : nextEligibleResumeAt.getTime(),
                lastProgressAt == null ? null : lastProgressAt.getTime(),
                resultSet.getString("last_error_kind"),
                resultSet.getString("last_error_code"),
                failedAt == null ? null : failedAt.getTime()
        );
    }

    private static String normalizeTenant(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        String trimmed = tenantId.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 1000);
    }

    private static int normalizeOffset(int offset) {
        return Math.max(offset, 0);
    }

    private static Timestamp toTimestamp(Long epochMs) {
        if (epochMs == null || epochMs <= 0) {
            return null;
        }
        return new Timestamp(epochMs);
    }
}

