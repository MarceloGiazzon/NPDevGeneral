package com.npdev.adapters.tracestore.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ports.TraceQuery;
import com.npdev.kernel.ports.TraceSummaryStore;
import com.npdev.kernel.ports.TraceStore;
import com.npdev.kernel.trace.FlowTrace;
import com.npdev.kernel.trace.StepOutcome;
import com.npdev.kernel.trace.TraceSummary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class JdbcTraceStore implements TraceStore, TraceSummaryStore {
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcTraceStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public JdbcTraceStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void save(FlowTrace trace) {
        Objects.requireNonNull(trace, "trace");
        String executionId = trace.meta().executionId();
        String flowName = trace.meta().flowName();
        String correlationId = trace.meta().correlationId();
        String tenantId = trace.meta().tenantId();
        String actorId = trace.meta().actorId();
        String status = resolveStatus(trace);
        long startedAt = trace.startedAtEpochMs();
        long endedAt = trace.endedAtEpochMs();
        String traceJson = toJson(trace);

        String updateSql = """
                UPDATE npdev_trace
                SET correlation_id = ?,
                    flow_name = ?,
                    tenant_id = ?,
                    actor_id = ?,
                    outcome = ?,
                    started_at_ms = ?,
                    ended_at_ms = ?,
                    trace_json = ?
                WHERE execution_id = ?
                """;
        String insertSql = """
                INSERT INTO npdev_trace (
                    execution_id, correlation_id, flow_name, tenant_id, actor_id, outcome,
                    started_at_ms, ended_at_ms, trace_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            update.setString(1, correlationId);
            update.setString(2, flowName);
            update.setString(3, tenantId);
            update.setString(4, actorId);
            update.setString(5, status);
            update.setLong(6, startedAt);
            update.setLong(7, endedAt);
            update.setString(8, traceJson);
            update.setString(9, executionId);

            int updated = update.executeUpdate();
            if (updated > 0) {
                return;
            }

            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                insert.setString(1, executionId);
                insert.setString(2, correlationId);
                insert.setString(3, flowName);
                insert.setString(4, tenantId);
                insert.setString(5, actorId);
                insert.setString(6, status);
                insert.setLong(7, startedAt);
                insert.setLong(8, endedAt);
                insert.setString(9, traceJson);
                insert.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed saving trace for executionId=" + executionId, exception);
        }
    }

    @Override
    public Optional<FlowTrace> findByExecutionId(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                SELECT trace_json
                FROM npdev_trace
                WHERE execution_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, executionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(fromJson(resultSet.getString("trace_json")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed loading trace by executionId=" + executionId, exception);
        }
    }

    @Override
    public List<FlowTrace> search(TraceQuery query) {
        TraceQuery effective = query == null ? TraceQuery.empty() : query;
        StringBuilder sql = new StringBuilder("""
                SELECT trace_json
                FROM npdev_trace
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (effective.correlationId() != null) {
            sql.append(" AND correlation_id = ?");
            params.add(effective.correlationId());
        }
        if (effective.flowName() != null) {
            sql.append(" AND flow_name = ?");
            params.add(effective.flowName());
        }
        if (effective.tenantId() != null) {
            sql.append(" AND tenant_id = ?");
            params.add(effective.tenantId());
        }
        if (effective.actorId() != null) {
            sql.append(" AND actor_id = ?");
            params.add(effective.actorId());
        }
        if (effective.status() != null) {
            sql.append(" AND outcome = ?");
            params.add(effective.status());
        }
        if (effective.fromEpochMs() != null) {
            sql.append(" AND started_at_ms >= ?");
            params.add(effective.fromEpochMs());
        }
        if (effective.toEpochMs() != null) {
            sql.append(" AND started_at_ms <= ?");
            params.add(effective.toEpochMs());
        }

        sql.append(" ORDER BY started_at_ms DESC, execution_id DESC");
        sql.append(" LIMIT ? OFFSET ?");
        params.add(effective.limit());
        params.add(effective.offset());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Object parameter : params) {
                if (parameter instanceof String s) {
                    statement.setString(index++, s);
                } else if (parameter instanceof Long l) {
                    statement.setLong(index++, l);
                } else if (parameter instanceof Integer i) {
                    statement.setInt(index++, i);
                } else {
                    statement.setObject(index++, parameter);
                }
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                List<FlowTrace> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(fromJson(resultSet.getString("trace_json")));
                }
                out.sort(Comparator
                        .comparingLong(FlowTrace::startedAtEpochMs).reversed()
                        .thenComparing(trace -> trace.meta().executionId(), Comparator.reverseOrder()));
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed searching traces", exception);
        }
    }

    @Override
    public List<TraceSummary> searchSummaries(TraceQuery query) {
        TraceQuery effective = query == null ? TraceQuery.empty() : query;
        StringBuilder sql = new StringBuilder("""
                SELECT execution_id, tenant_id, correlation_id, flow_name, outcome, started_at_ms, ended_at_ms
                FROM npdev_trace
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (effective.tenantId() != null) {
            sql.append(" AND tenant_id = ?");
            params.add(effective.tenantId());
        }
        if (effective.correlationId() != null) {
            sql.append(" AND correlation_id = ?");
            params.add(effective.correlationId());
        }
        if (effective.flowName() != null) {
            sql.append(" AND flow_name = ?");
            params.add(effective.flowName());
        }
        if (effective.status() != null) {
            sql.append(" AND outcome = ?");
            params.add(effective.status());
        }
        if (effective.fromEpochMs() != null) {
            sql.append(" AND started_at_ms >= ?");
            params.add(effective.fromEpochMs());
        }
        if (effective.toEpochMs() != null) {
            sql.append(" AND started_at_ms <= ?");
            params.add(effective.toEpochMs());
        }
        if (effective.actorId() != null) {
            sql.append(" AND actor_id = ?");
            params.add(effective.actorId());
        }

        sql.append(" ORDER BY started_at_ms DESC, execution_id DESC");
        sql.append(" LIMIT ? OFFSET ?");
        params.add(effective.limit());
        params.add(effective.offset());

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindParams(statement, params);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<TraceSummary> out = new ArrayList<>();
                while (resultSet.next()) {
                    long startedAt = resultSet.getLong("started_at_ms");
                    long endedAt = resultSet.getLong("ended_at_ms");
                    out.add(new TraceSummary(
                            resultSet.getString("execution_id"),
                            resultSet.getString("tenant_id"),
                            resultSet.getString("correlation_id"),
                            resultSet.getString("flow_name"),
                            resultSet.getString("outcome"),
                            startedAt,
                            endedAt,
                            endedAt
                    ));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed searching trace summaries", exception);
        }
    }

    private String toJson(FlowTrace trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed serializing FlowTrace", exception);
        }
    }

    private FlowTrace fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Stored trace_json is blank");
        }
        try {
            return objectMapper.readValue(json, FlowTrace.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed deserializing FlowTrace", exception);
        }
    }

    private static String resolveStatus(FlowTrace trace) {
        if (trace.outcome() == StepOutcome.OK) {
            return "OK";
        }
        boolean waiting = trace.steps().stream()
                .anyMatch(step -> "WAITING".equals(String.valueOf(step.info().get("awaitedEventStatus"))));
        return waiting ? "WAITING" : "FAILED";
    }

    private static void bindParams(PreparedStatement statement, List<Object> params) throws SQLException {
        int index = 1;
        for (Object parameter : params) {
            if (parameter instanceof String s) {
                statement.setString(index++, s);
            } else if (parameter instanceof Long l) {
                statement.setLong(index++, l);
            } else if (parameter instanceof Integer i) {
                statement.setInt(index++, i);
            } else {
                statement.setObject(index++, parameter);
            }
        }
    }
}

