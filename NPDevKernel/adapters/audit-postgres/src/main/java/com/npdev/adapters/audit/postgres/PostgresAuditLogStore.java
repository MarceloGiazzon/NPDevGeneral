package com.npdev.adapters.audit.postgres;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.audit.AuditRecord;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.AuditQuery;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PostgresAuditLogStore implements AuditLogStore {
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public PostgresAuditLogStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public PostgresAuditLogStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void append(AuditRecord record) {
        Objects.requireNonNull(record, "record");
        String sql = """
                INSERT INTO npdev_audit_log (
                    audit_id, ts_ms, tenant_id, actor_id, roles, action, resource_type,
                    resource_id, outcome, reason_code, tags_json, meta_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.auditId());
            statement.setLong(2, record.timestampMs());
            statement.setString(3, record.tenantId());
            statement.setString(4, record.actorId());
            statement.setString(5, toRolesCsv(record.roles()));
            statement.setString(6, record.action());
            statement.setString(7, record.resourceType());
            statement.setString(8, record.resourceId());
            statement.setString(9, record.outcome());
            statement.setString(10, record.reasonCode());
            statement.setString(11, writeJson(record.tags()));
            statement.setString(12, writeJson(record.meta()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed appending audit record: " + record.auditId(), exception);
        }
    }

    @Override
    public List<AuditRecord> search(AuditQuery query) {
        AuditQuery effective = query == null ? AuditQuery.emptyForTenant(null) : query;
        String tenantId = normalize(effective.tenantId());
        if (tenantId == null) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
                SELECT audit_id, ts_ms, tenant_id, actor_id, roles, action, resource_type,
                       resource_id, outcome, reason_code, tags_json, meta_json
                FROM npdev_audit_log
                WHERE tenant_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (normalize(effective.actorId()) != null) {
            sql.append(" AND actor_id = ?");
            params.add(effective.actorId().trim());
        }
        if (normalize(effective.action()) != null) {
            sql.append(" AND action = ?");
            params.add(effective.action().trim());
        }
        if (normalize(effective.resourceType()) != null) {
            sql.append(" AND resource_type = ?");
            params.add(effective.resourceType().trim());
        }
        if (normalize(effective.resourceId()) != null) {
            sql.append(" AND resource_id = ?");
            params.add(effective.resourceId().trim());
        }
        if (effective.fromMs() != null) {
            sql.append(" AND ts_ms >= ?");
            params.add(effective.fromMs());
        }
        if (effective.toMs() != null) {
            sql.append(" AND ts_ms <= ?");
            params.add(effective.toMs());
        }

        sql.append(" ORDER BY ts_ms DESC, audit_id DESC");
        sql.append(" LIMIT ? OFFSET ?");
        params.add(normalizeLimit(effective.limit()));
        params.add(normalizeOffset(effective.offset()));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Object param : params) {
                if (param instanceof String s) {
                    statement.setString(index++, s);
                } else if (param instanceof Long l) {
                    statement.setLong(index++, l);
                } else if (param instanceof Integer i) {
                    statement.setInt(index++, i);
                } else {
                    statement.setObject(index++, param);
                }
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AuditRecord> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(readRecord(resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed searching audit records", exception);
        }
    }

    private AuditRecord readRecord(ResultSet resultSet) throws SQLException {
        return new AuditRecord(
                resultSet.getString("audit_id"),
                resultSet.getLong("ts_ms"),
                resultSet.getString("tenant_id"),
                resultSet.getString("actor_id"),
                fromRolesCsv(resultSet.getString("roles")),
                resultSet.getString("action"),
                resultSet.getString("resource_type"),
                resultSet.getString("resource_id"),
                resultSet.getString("outcome"),
                resultSet.getString("reason_code"),
                readJson(resultSet.getString("tags_json")),
                readJson(resultSet.getString("meta_json"))
        );
    }

    private String writeJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed serializing audit map", exception);
        }
    }

    private Map<String, String> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> values = objectMapper.readValue(json, MAP_TYPE);
            return values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed deserializing audit map", exception);
        }
    }

    private static String toRolesCsv(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "USER";
        }
        return roles.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("USER");
    }

    private static Set<String> fromRolesCsv(String rolesCsv) {
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return Set.of("USER");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String token : rolesCsv.split(",")) {
            if (token == null) {
                continue;
            }
            String role = token.trim();
            if (!role.isBlank()) {
                roles.add(role);
            }
        }
        if (roles.isEmpty()) {
            roles.add("USER");
        }
        return Set.copyOf(roles);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
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

