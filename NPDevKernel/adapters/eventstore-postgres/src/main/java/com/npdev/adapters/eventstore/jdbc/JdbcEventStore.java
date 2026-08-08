package com.npdev.adapters.eventstore.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.events.EventMetaSummary;
import com.npdev.kernel.ports.EventMetaStore;
import com.npdev.kernel.ports.EventStore;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class JdbcEventStore implements EventStore, EventMetaStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final SqlDialect dialect;

    public JdbcEventStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    public JdbcEventStore(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, SqlDialects.active());
    }

    /**
     * Explicit dialect, for the conformance suite and for a host that pins its engine at boot.
     *
     * <p>The no-dialect constructors resolve {@link SqlDialects#active()}, which is the engine the
     * app was GENERATED for -- not one detected from the connection. Detection would make emitted
     * SQL depend on runtime discovery, so a misconfiguration would quietly produce different SQL
     * instead of failing.
     */
    public JdbcEventStore(DataSource dataSource, ObjectMapper objectMapper, SqlDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public void append(EventEnvelope event) {
        Objects.requireNonNull(event, "event");
        String sql = """
                INSERT INTO npdev_event_store (
                    event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                    timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.eventId());
            statement.setString(2, event.eventName());
            statement.setString(3, event.correlationId());
            statement.setString(4, event.causationId());
            statement.setString(5, event.flowName());
            statement.setInt(6, event.stepIndex());
            statement.setLong(7, event.timestampEpochMs());
            statement.setString(8, writeJson(event.payload()));
            statement.setString(9, writeJson(extractMetadata(event.payload())));
            statement.setString(10, event.tenantId());
            statement.setString(11, event.actorId());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed appending event envelope: " + event.eventId(), exception);
        }
    }

    @Override
    public Optional<EventEnvelope> findFirst(String eventName, String correlationId) {
        return findFirst(eventName, correlationId, null);
    }

    @Override
    public Optional<EventEnvelope> findFirst(String eventName, String correlationId, String tenantId) {
        if (eventName == null || eventName.isBlank() || correlationId == null || correlationId.isBlank()) {
            return Optional.empty();
        }
        String effectiveTenantId = normalizeTenant(tenantId);
        String sql = effectiveTenantId == null
                ? dialect.rowLimited("""
                SELECT event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                       timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                FROM npdev_event_store
                WHERE event_name = ? AND correlation_id = ?
                ORDER BY timestamp_ms ASC, event_id ASC
                """, 1)
                : dialect.rowLimited("""
                SELECT event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                       timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                FROM npdev_event_store
                WHERE event_name = ? AND correlation_id = ? AND tenant_id = ?
                ORDER BY timestamp_ms ASC, event_id ASC
                """, 1);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventName);
            statement.setString(2, correlationId);
            if (effectiveTenantId != null) {
                statement.setString(3, effectiveTenantId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readEvent(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed finding first event for eventName=" + eventName + ", correlationId=" + correlationId,
                    exception
            );
        }
    }

    @Override
    public List<EventEnvelope> readByCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                       timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                FROM npdev_event_store
                WHERE correlation_id = ?
                ORDER BY timestamp_ms ASC, event_id ASC
                """;
        return query(sql, correlationId);
    }

    @Override
    public List<EventEnvelope> readByCorrelation(String correlationId, String tenantId) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                       timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                FROM npdev_event_store
                WHERE tenant_id = ? AND correlation_id = ?
                ORDER BY timestamp_ms ASC, event_id ASC
                """;
        return queryScopedWithoutPaging(sql, effectiveTenantId, correlationId);
    }

    @Override
    public List<EventEnvelope> readByEventName(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                       timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                FROM npdev_event_store
                WHERE event_name = ?
                ORDER BY timestamp_ms ASC, event_id ASC
                """;
        return query(sql, eventName);
    }

    @Override
    public List<EventEnvelope> readByEventName(String eventName, String tenantId) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null || eventName == null || eventName.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                       timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                FROM npdev_event_store
                WHERE tenant_id = ? AND event_name = ?
                ORDER BY timestamp_ms ASC, event_id ASC
                """;
        return queryScopedWithoutPaging(sql, effectiveTenantId, eventName);
    }

    @Override
    public List<EventEnvelope> findByCorrelationId(String tenantId, String correlationId, int limit, int offset) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = normalizeOffset(offset);
        String sql = dialect.paginated("""
                SELECT event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                       timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                FROM npdev_event_store
                WHERE tenant_id = ? AND correlation_id = ?
                ORDER BY timestamp_ms ASC, event_id ASC
                """);
        return queryScoped(sql, effectiveTenantId, correlationId, effectiveLimit, effectiveOffset);
    }

    @Override
    public List<EventEnvelope> findByEventName(String tenantId, String eventName, int limit, int offset) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null || eventName == null || eventName.isBlank()) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = normalizeOffset(offset);
        String sql = dialect.paginated("""
                SELECT event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                       timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                FROM npdev_event_store
                WHERE tenant_id = ? AND event_name = ?
                ORDER BY timestamp_ms DESC, event_id DESC
                """);
        return queryScoped(sql, effectiveTenantId, eventName, effectiveLimit, effectiveOffset);
    }

    @Override
    public Optional<EventEnvelope> findByEventId(String tenantId, String eventId) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null || eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                SELECT event_id, event_name, correlation_id, causation_id, flow_name, step_index,
                       timestamp_ms, payload_json, metadata_json, tenant_id, actor_id
                FROM npdev_event_store
                WHERE tenant_id = ? AND event_id = ?
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, effectiveTenantId);
            statement.setString(2, eventId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(readEvent(resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed reading event by id", exception);
        }
    }

    @Override
    public List<EventMetaSummary> listByCorrelation(String tenantId, String correlationId, int limit, int offset) {
        String effectiveTenantId = normalizeTenant(tenantId);
        if (effectiveTenantId == null || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        int effectiveLimit = normalizeLimit(limit);
        int effectiveOffset = normalizeOffset(offset);
        String sql = dialect.paginated("""
                SELECT event_id, tenant_id, correlation_id, event_name, flow_name, step_index, timestamp_ms
                FROM npdev_event_store
                WHERE tenant_id = ? AND correlation_id = ?
                ORDER BY timestamp_ms ASC, event_id ASC
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
                List<EventMetaSummary> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(new EventMetaSummary(
                            resultSet.getString("event_id"),
                            resultSet.getString("tenant_id"),
                            resultSet.getString("correlation_id"),
                            resultSet.getString("event_name"),
                            resultSet.getString("flow_name"),
                            resultSet.getInt("step_index"),
                            resultSet.getLong("timestamp_ms")
                    ));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed listing event meta by correlation", exception);
        }
    }

    private List<EventEnvelope> query(String sql, String filterValue) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, filterValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<EventEnvelope> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readEvent(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed reading event envelopes", exception);
        }
    }

    private List<EventEnvelope> queryScoped(
            String sql,
            String tenantId,
            String filterValue,
            int limit,
            int offset
    ) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, filterValue);
            int pageIndex = 3;
            for (int pageValue : dialect.limitOffset().values(limit, offset)) {
                statement.setInt(pageIndex++, pageValue);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<EventEnvelope> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readEvent(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed reading scoped event envelopes", exception);
        }
    }

    private List<EventEnvelope> queryScopedWithoutPaging(
            String sql,
            String tenantId,
            String filterValue
    ) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, filterValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<EventEnvelope> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readEvent(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed reading scoped event envelopes", exception);
        }
    }

    private EventEnvelope readEvent(ResultSet resultSet) throws SQLException {
        String eventId = resultSet.getString("event_id");
        String eventName = resultSet.getString("event_name");
        String correlationId = resultSet.getString("correlation_id");
        String causationId = resultSet.getString("causation_id");
        String flowName = resultSet.getString("flow_name");
        int stepIndex = resultSet.getInt("step_index");
        long timestampMs = resultSet.getLong("timestamp_ms");
        String payloadJson = resultSet.getString("payload_json");
        String metadataJson = resultSet.getString("metadata_json");
        String tenantId = resultSet.getString("tenant_id");
        String actorId = resultSet.getString("actor_id");

        Map<String, Object> payload = new LinkedHashMap<>(readJson(payloadJson));
        Map<String, Object> metadata = readJson(metadataJson);
        if (!metadata.isEmpty() && !payload.containsKey("_meta")) {
            payload.put("_meta", metadata);
        }

        return new EventEnvelope(
                eventId,
                eventName,
                timestampMs,
                payload,
                correlationId,
                causationId,
                flowName,
                stepIndex,
                tenantId,
                actorId
        );
    }

    private String writeJson(Map<String, Object> content) {
        try {
            Map<String, Object> safeContent = content == null ? Map.of() : content;
            return objectMapper.writeValueAsString(safeContent);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed serializing event store JSON", exception);
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(json, MAP_TYPE);
            return value == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed parsing event store JSON", exception);
        }
    }

    private static Map<String, Object> extractMetadata(Map<String, Object> payload) {
        if (payload == null) {
            return Map.of();
        }
        Object metadata = payload.get("_meta");
        if (metadata instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                converted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return Map.copyOf(converted);
        }
        return Map.of();
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
}

