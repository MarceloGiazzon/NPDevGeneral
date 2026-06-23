package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class JdbcBusinessConceptStore implements ConceptStore {
    private final DataSource dataSource;
    private final Map<String, ConceptShape> shapesByConcept;
    private final Map<String, TableColumns> tableColumnsCache = new ConcurrentHashMap<>();

    public JdbcBusinessConceptStore(DataSource dataSource, CompiledModel compiledModel) {
        this.dataSource = dataSource;
        this.shapesByConcept = shapes(compiledModel);
    }

    @Override
    public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
        ConceptShape shape = shape(conceptName);
        String sql = "SELECT * FROM " + shape.tableName() + " WHERE " + shape.idColumn() + " = ? AND tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, coerceId(id));
            statement.setObject(2, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(toRecord(shape, tenantId, resultSet));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed reading concept " + conceptName + " from JDBC store", exception);
        }
    }

    @Override
    public List<ConceptRecord> findAll(String tenantId, String conceptName) {
        ConceptShape shape = shape(conceptName);
        String sql = "SELECT * FROM " + shape.tableName() + " WHERE tenant_id = ? ORDER BY " + shape.idColumn();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ConceptRecord> out = new ArrayList<>();
                while (resultSet.next()) {
                    out.add(toRecord(shape, tenantId, resultSet));
                }
                return List.copyOf(out);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed listing concept " + conceptName + " from JDBC store", exception);
        }
    }

    @Override
    public ConceptRecord save(ConceptRecord record) {
        ConceptShape shape = shape(record.conceptName());
        Map<String, Object> dbRecord = dbRecord(shape, record);
        try (Connection connection = dataSource.getConnection()) {
            TableColumns columns = tableColumns(connection, shape.tableName());
            dbRecord.keySet().removeIf(column -> !columns.has(column));
            dbRecord.putIfAbsent(shape.idColumn(), coerceId(record.id()));
            List<String> columnNames = new ArrayList<>(dbRecord.keySet());
            columnNames.remove(shape.idColumn());
            columnNames.add(0, shape.idColumn());
            String sql = upsertSql(connection, shape.tableName(), shape.idColumn(), columnNames);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (String column : columnNames) {
                    statement.setObject(index++, coerceValue(column, dbRecord.get(column)));
                }
                statement.executeUpdate();
            }
            return record;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed saving concept " + record.conceptName() + " to JDBC store", exception);
        }
    }

    @Override
    public void deleteById(String tenantId, String conceptName, String id) {
        ConceptShape shape = shape(conceptName);
        String sql = "DELETE FROM " + shape.tableName() + " WHERE " + shape.idColumn() + " = ? AND tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, coerceId(id));
            statement.setObject(2, tenantId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed deleting concept " + conceptName + " from JDBC store", exception);
        }
    }

    private ConceptRecord toRecord(ConceptShape shape, String tenantId, ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        Map<String, Object> data = new LinkedHashMap<>();
        String id = "";
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            String column = metaData.getColumnLabel(index);
            Object value = resultSet.getObject(index);
            if (isJsonColumnType(metaData, index)) {
                value = parseJsonColumnValue(column, value);
            }
            String field = shape.fieldByColumn().getOrDefault(column.toLowerCase(Locale.ROOT), toRuntimeField(column));
            data.put(field, value);
            if (shape.idColumn().equalsIgnoreCase(column) && value != null) {
                id = String.valueOf(value);
            }
        }
        return new ConceptRecord(shape.conceptName(), id, tenantId, data);
    }

    private static boolean isJsonColumnType(ResultSetMetaData metaData, int index) throws SQLException {
        String typeName = metaData.getColumnTypeName(index);
        return typeName != null && (
                "JSON".equalsIgnoreCase(typeName) || "JSONB".equalsIgnoreCase(typeName)
        );
    }

    /**
     * The write side (coerceValue) stores object/array DSL fields as JSON text, since neither H2
     * nor Postgres accept a raw Java Map/List for a JSON-typed column. Reading it back, the JDBC
     * driver hands the column back as a String (or, on some drivers, raw bytes) -- parse it back
     * into the Map/List the rest of the runtime (entity mapping, JSON serialization to the
     * generated REST response, the business UI's object/array renderer) expects, so the round trip
     * is transparent: nothing downstream needs to know this field is JSON-backed.
     */
    private static Object parseJsonColumnValue(String column, Object value) {
        if (value == null) {
            return null;
        }
        String json;
        if (value instanceof byte[] bytes) {
            json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } else if (value instanceof String text) {
            json = text;
        } else {
            // Already a structured value (some drivers may already deserialize JSON columns) --
            // leave it as-is rather than guessing.
            return value;
        }
        if (json.isBlank()) {
            return null;
        }
        try {
            return JSON_COLUMN_MAPPER.readValue(json, Object.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse JSON column \"" + column + "\"", exception);
        }
    }

    private Map<String, Object> dbRecord(ConceptShape shape, ConceptRecord record) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(shape.idColumn(), coerceId(record.id()));
        // tenant_id has no safe DB-level default (unlike "version" DEFAULT 0) — it must come from
        // the ConceptRecord's own dedicated tenantId component, not record.data(). The
        // kernel-gateway write path (DefaultConceptGateway.save -> store.save) builds its payload
        // from DSL-declared fields only and never puts a "tenantId" entry into data(), so relying on
        // record.data() alone (as this loop does for every other column) would silently write NULL.
        out.put("tenant_id", record.tenantId());
        for (Map.Entry<String, Object> entry : record.data().entrySet()) {
            String column = shape.columnByField().getOrDefault(entry.getKey().toLowerCase(Locale.ROOT), toDbColumn(entry.getKey()));
            out.put(column, entry.getValue());
        }
        return out;
    }

    private ConceptShape shape(String conceptName) {
        ConceptShape shape = shapesByConcept.get(normalize(conceptName));
        if (shape == null) {
            throw new IllegalArgumentException("Unknown concept for JDBC ConceptStore: " + conceptName);
        }
        return shape;
    }

    private TableColumns tableColumns(Connection connection, String table) {
        return tableColumnsCache.computeIfAbsent(table.toLowerCase(Locale.ROOT), ignored -> loadColumns(connection, table));
    }

    private static Map<String, ConceptShape> shapes(CompiledModel model) {
        Map<String, ConceptShape> out = new LinkedHashMap<>();
        if (model == null) {
            return Map.of();
        }
        for (CompiledConcept concept : model.getConcepts()) {
            String table = concept.getTableName();
            if (table == null || table.isBlank()) {
                table = toDbColumn(concept.getName()) + "s";
            }
            String idColumn = "id";
            Map<String, String> columnByField = new LinkedHashMap<>();
            Map<String, String> fieldByColumn = new LinkedHashMap<>();
            for (CompiledField field : concept.getFields()) {
                String column = toDbColumn(field.getName());
                columnByField.put(field.getName().toLowerCase(Locale.ROOT), column);
                fieldByColumn.put(column.toLowerCase(Locale.ROOT), field.getName());
                if (field.isId()) {
                    idColumn = column;
                }
            }
            out.put(normalize(concept.getName()), new ConceptShape(concept.getName(), table, idColumn, columnByField, fieldByColumn));
        }
        return Map.copyOf(out);
    }

    private static TableColumns loadColumns(Connection connection, String table) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            TableColumns columns = new TableColumns();
            try (ResultSet resultSet = metaData.getColumns(null, null, table, null)) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME"));
                }
            }
            if (columns.empty()) {
                try (ResultSet resultSet = metaData.getColumns(null, null, table.toUpperCase(Locale.ROOT), null)) {
                    while (resultSet.next()) {
                        columns.add(resultSet.getString("COLUMN_NAME"));
                    }
                }
            }
            return columns;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed loading columns for " + table, exception);
        }
    }

    private static String upsertSql(Connection connection, String table, String idColumn, List<String> columns) throws SQLException {
        if (connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("h2")) {
            return "MERGE INTO " + table + " (" + String.join(", ", columns) + ") KEY(" + idColumn + ") VALUES ("
                    + "?,".repeat(columns.size()).replaceAll(",$", "") + ")";
        }
        List<String> updates = columns.stream()
                .filter(column -> !column.equalsIgnoreCase(idColumn))
                .map(column -> column + " = EXCLUDED." + column)
                .toList();
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + "?,".repeat(columns.size()).replaceAll(",$", "") + ") ON CONFLICT (" + idColumn + ") DO UPDATE SET "
                + String.join(", ", updates);
    }

    private static final ObjectMapper JSON_COLUMN_MAPPER = new ObjectMapper();

    private static Object coerceValue(String column, Object value) {
        if (value == null) {
            return null;
        }
        if (isUuidColumn(column)) {
            return coerceId(value);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            // Object/array DSL fields map to a JSON/JSONB column (SqlTypeSupport). Handing the
            // JDBC driver a raw Map/List makes it default to JAVA_OBJECT, which H2 (and Postgres)
            // both reject for a JSON-typed column ("Data conversion error converting JAVA_OBJECT
            // to JSON") -- write the JSON text representation instead, the standard plain-JDBC
            // idiom both engines accept for a JSON column via setObject/setString.
            try {
                return JSON_COLUMN_MAPPER.writeValueAsString(value);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to serialize column \"" + column + "\" to JSON", exception);
            }
        }
        return value;
    }

    private static Object coerceId(Object value) {
        if (value instanceof UUID) {
            return value;
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private static boolean isUuidColumn(String column) {
        String normalized = column == null ? "" : column.toLowerCase(Locale.ROOT);
        return "id".equals(normalized) || normalized.endsWith("_id");
    }

    private static String toDbColumn(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String toRuntimeField(String column) {
        if (column == null || column.isBlank() || !column.contains("_")) {
            return column;
        }
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char ch : column.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else if (upper) {
                out.append(Character.toUpperCase(ch));
                upper = false;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ConceptShape(
            String conceptName,
            String tableName,
            String idColumn,
            Map<String, String> columnByField,
            Map<String, String> fieldByColumn
    ) {
    }

    private static final class TableColumns {
        private final List<String> columns = new ArrayList<>();

        void add(String column) {
            if (column != null && !column.isBlank()) {
                columns.add(column.toLowerCase(Locale.ROOT));
            }
        }

        boolean has(String column) {
            return column != null && columns.contains(column.toLowerCase(Locale.ROOT));
        }

        boolean empty() {
            return columns.isEmpty();
        }
    }
}
