
package com.npdev.adapters.persistence.postgres;

import com.npdev.kernel.ports.PersistenceCapabilityContract;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC-based Postgres implementation of the persistence capability.
 *
 * Runtime payload keys are accepted only when they can be resolved to actual
 * database columns for the target table. This prevents accidental SQL generation
 * from stale or non-canonical field names.
 */
public final class PostgresPersistenceCapabilityAdapter implements PersistenceCapabilityContract {

    private final DataSource dataSource;
    private final Map<String, TableColumns> tableColumnsCache = new ConcurrentHashMap<>();

    public PostgresPersistenceCapabilityAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        System.out.println("NPDEV-UPGRADE-MARKER 2026-03-03 :: PostgresPersistenceCapabilityAdapter created (this proves NP export is running)");
    }

    @Override
    public Object save(Object entity) {
        return save("default", entity);
    }

    public Object save(Object concept, Object entity) {
        String table = tableName(concept);
        Map<String, Object> runtimeRecord = mutableRecord(entity);
        String conceptIdField = inferredRuntimeIdField(concept, table);

        Object id = runtimeRecord.get("id");
        if (id == null && conceptIdField != null) {
            id = runtimeRecord.get(conceptIdField);
        }
        if (id == null || String.valueOf(id).isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (conceptIdField == null || "id".equals(conceptIdField)) {
            runtimeRecord.put("id", id);
        } else {
            runtimeRecord.putIfAbsent(conceptIdField, id);
        }

        try (Connection connection = dataSource.getConnection()) {
            TableColumns tableColumns = resolveTableColumns(connection, table);
            Map<String, Object> sqlRecord = normalizeRecordForSave(table, runtimeRecord, tableColumns);
            String idColumn = resolveIdColumn(table, tableColumns);

            List<String> columns = new ArrayList<>(sqlRecord.keySet());
            ensureColumnFirst(columns, idColumn);

            String sql = buildUpsertSql(connection, table, columns, idColumn);
            System.out.println(String.format(
                    "NPDEV-PG-SAVE :: concept=%s table=%s columns=%s sql=%s recordKeys=%s",
                    concept,
                    table,
                    columns,
                    sql,
                    runtimeRecord.keySet()
            ));

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int idx = 1;
                for (String column : columns) {
                    Object value = sqlRecord.get(column);
                    value = coerceValueForColumn(column, value);
                    ps.setObject(idx++, value);
                }
                ps.executeUpdate();
                return immutableRecord(runtimeRecord);
            }

        } catch (SQLException e) {
            throw new IllegalStateException("Postgres persistence save failed for table " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Object findById(Object concept, Object id) {
        if (id == null) {
            return null;
        }
        String table = tableName(concept);

        try (Connection c = dataSource.getConnection()) {
            TableColumns tableColumns = resolveTableColumns(c, table);
            String idColumn = resolveIdColumn(table, tableColumns);
            String sql = "select * from " + table + " where " + idColumn + " = ?";

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, coerceValueForColumn(idColumn, id));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return rowToMap(rs);
                }
            }

        } catch (SQLException e) {
            throw new IllegalStateException("Postgres persistence findById failed for table " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Object query(Object concept, Object criteria) {
        String table = tableName(concept);
        Map<String, Object> crit = criteriaMap(criteria);

        try (Connection connection = dataSource.getConnection()) {
            TableColumns tableColumns = resolveTableColumns(connection, table);
            Map<String, Object> sqlCriteria = normalizeCriteria(table, crit, tableColumns);

            StringBuilder sql = new StringBuilder("select * from ").append(table);
            List<Object> params = new ArrayList<>();

            if (!sqlCriteria.isEmpty()) {
                sql.append(" where ");
                boolean first = true;
                for (Map.Entry<String, Object> entry : sqlCriteria.entrySet()) {
                    if (!first) {
                        sql.append(" and ");
                    }
                    first = false;
                    String column = entry.getKey();
                    sql.append(column).append(" = ?");
                    params.add(coerceValueForColumn(column, entry.getValue()));
                }
            }

            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }

                List<Map<String, Object>> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rowToMap(rs));
                    }
                }
                return List.copyOf(out);
            }

        } catch (SQLException e) {
            throw new IllegalStateException("Postgres persistence query failed for table " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Object delete(Object concept, Object id) {
        if (id == null) {
            return false;
        }
        String table = tableName(concept);

        try (Connection c = dataSource.getConnection()) {
            TableColumns tableColumns = resolveTableColumns(c, table);
            String idColumn = resolveIdColumn(table, tableColumns);
            String sql = "delete from " + table + " where " + idColumn + " = ?";

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, coerceValueForColumn(idColumn, id));
                return ps.executeUpdate() > 0;
            }

        } catch (SQLException e) {
            throw new IllegalStateException("Postgres persistence delete failed for table " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Object exists(Object concept, Object field, Object value) {
        String table = tableName(concept);
        String fieldName = Objects.toString(field, "").trim();
        if (fieldName.isBlank()) {
            return false;
        }

        try (Connection connection = dataSource.getConnection()) {
            TableColumns tableColumns = resolveTableColumns(connection, table);
            String column = resolveCriteriaColumn(table, fieldName, tableColumns);
            String sql = "select 1 from " + table + " where " + column + " = ? limit 1";

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, coerceValueForColumn(column, value));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }

        } catch (SQLException e) {
            throw new IllegalStateException("Postgres persistence exists failed for table " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Object unique(Object concept, Object field, Object value) {
        return !(Boolean) exists(concept, field, value);
    }

    private TableColumns resolveTableColumns(Connection connection, String table) {
        return tableColumnsCache.computeIfAbsent(table.toLowerCase(Locale.ROOT), ignored -> loadTableColumns(connection, table));
    }

    private static TableColumns loadTableColumns(Connection connection, String table) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            if (metaData == null) {
                return TableColumns.unavailable();
            }

            TableColumns columns = readColumns(metaData, null, table);
            if (!columns.isEmpty()) {
                return columns;
            }

            columns = readColumns(metaData, null, table.toLowerCase(Locale.ROOT));
            if (!columns.isEmpty()) {
                return columns;
            }

            columns = readColumns(metaData, null, table.toUpperCase(Locale.ROOT));
            if (!columns.isEmpty()) {
                return columns;
            }
        } catch (SQLException ignored) {
            // fallback below
        }

        return TableColumns.unavailable();
    }

    private static TableColumns readColumns(DatabaseMetaData metaData, String schemaPattern, String tableName) throws SQLException {
        TableColumns columns = new TableColumns(true);
        try (ResultSet rs = metaData.getColumns(null, schemaPattern, tableName, null)) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                if (columnName != null && !columnName.isBlank()) {
                    columns.add(columnName);
                }
            }
        }
        return columns;
    }

    private static Map<String, Object> normalizeRecordForSave(String table, Map<String, Object> runtimeRecord, TableColumns tableColumns) {
        if (!tableColumns.isAvailable()) {
            return dbColumnRecord(runtimeRecord);
        }

        LinkedHashMap<String, Object> sqlRecord = new LinkedHashMap<>();
        List<String> unknownFields = new ArrayList<>();

        for (Map.Entry<String, Object> entry : runtimeRecord.entrySet()) {
            String runtimeField = entry.getKey();
            String column = resolveColumn(table, runtimeField, tableColumns);
            if (column == null) {
                unknownFields.add(runtimeField);
                continue;
            }
            sqlRecord.put(column, entry.getValue());
        }

        if (!unknownFields.isEmpty()) {
            throw unknownFieldException(table, unknownFields, tableColumns);
        }

        String idColumn = resolveIdColumn(table, tableColumns);
        if (!sqlRecord.containsKey(idColumn)) {
            throw new IllegalArgumentException(
                    "Persistence save requires id field/column '" + idColumn + "' for table " + table);
        }

        return sqlRecord;
    }

    private static String resolveIdColumn(String table, TableColumns tableColumns) {
        if (tableColumns == null || !tableColumns.isAvailable()) {
            return "id";
        }
        String idColumn = resolveColumn(table, "id", tableColumns);
        if (idColumn == null || idColumn.isBlank()) {
            throw new IllegalArgumentException(
                    "Persistence table " + table + " has no resolvable id column. Allowed database columns: "
                            + tableColumns.columnNames());
        }
        return idColumn;
    }

    private static Map<String, Object> normalizeCriteria(String table, Map<String, Object> runtimeCriteria, TableColumns tableColumns) {
        if (!tableColumns.isAvailable()) {
            LinkedHashMap<String, Object> dbCriteria = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : runtimeCriteria.entrySet()) {
                dbCriteria.put(toDbColumn(entry.getKey()), entry.getValue());
            }
            return dbCriteria;
        }

        LinkedHashMap<String, Object> sqlCriteria = new LinkedHashMap<>();
        List<String> unknownFields = new ArrayList<>();

        for (Map.Entry<String, Object> entry : runtimeCriteria.entrySet()) {
            String column = resolveColumn(table, entry.getKey(), tableColumns);
            if (column == null) {
                unknownFields.add(entry.getKey());
                continue;
            }
            sqlCriteria.put(column, entry.getValue());
        }

        if (!unknownFields.isEmpty()) {
            throw unknownFieldException(table, unknownFields, tableColumns);
        }

        return sqlCriteria;
    }

    private static String resolveCriteriaColumn(String table, String runtimeField, TableColumns tableColumns) {
        if (!tableColumns.isAvailable()) {
            return toDbColumn(runtimeField);
        }

        String column = resolveColumn(table, runtimeField, tableColumns);
        if (column == null) {
            throw unknownFieldException(table, List.of(runtimeField), tableColumns);
        }
        return column;
    }

    private static String resolveColumn(String table, String runtimeField, TableColumns tableColumns) {
        if (runtimeField == null || runtimeField.isBlank()) {
            return null;
        }

        if (tableColumns.hasColumn(runtimeField)) {
            return tableColumns.columnName(runtimeField);
        }

        String dbColumn = toDbColumn(runtimeField);
        if (tableColumns.hasColumn(dbColumn)) {
            return tableColumns.columnName(dbColumn);
        }

        if ("id".equalsIgnoreCase(runtimeField)) {
            String inferredIdColumn = toDbColumn(inferredRuntimeIdField(null, table));
            if (tableColumns.hasColumn(inferredIdColumn)) {
                return tableColumns.columnName(inferredIdColumn);
            }
        }

        String runtimeAlias = toRuntimeField(runtimeField);
        if (tableColumns.hasRuntimeField(runtimeAlias)) {
            return tableColumns.columnNameForRuntimeField(runtimeAlias);
        }

        String dbAliasRuntimeField = toRuntimeField(dbColumn);
        if (tableColumns.hasRuntimeField(dbAliasRuntimeField)) {
            return tableColumns.columnNameForRuntimeField(dbAliasRuntimeField);
        }

        return null;
    }

    private static IllegalArgumentException unknownFieldException(String table, List<String> unknownFields, TableColumns tableColumns) {
        return new IllegalArgumentException(
                "Unknown persistence field(s) for table " + table + ": " + unknownFields
                        + ". Allowed runtime fields: " + tableColumns.allowedRuntimeFields()
                        + ". Allowed database columns: " + tableColumns.columnNames()
        );
    }

    private static Map<String, Object> dbColumnRecord(Map<String, Object> runtimeRecord) {
        LinkedHashMap<String, Object> dbRecord = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : runtimeRecord.entrySet()) {
            dbRecord.put(toDbColumn(entry.getKey()), entry.getValue());
        }
        return dbRecord;
    }

    private static String tableName(Object concept) {
        String c = Objects.toString(concept, "default").trim().toLowerCase(Locale.ROOT);
        if (c.isBlank()) {
            c = "default";
        }
        if (c.endsWith("s")) {
            return c;
        }
        return c + "s";
    }

    private static String inferredRuntimeIdField(Object concept, String table) {
        String raw = concept == null ? "" : Objects.toString(concept, "").trim();
        String base;
        if (!raw.isBlank()) {
            base = raw.substring(0, 1).toLowerCase(Locale.ROOT) + raw.substring(1);
        } else {
            String normalizedTable = table == null ? "" : table.trim().toLowerCase(Locale.ROOT);
            if (normalizedTable.endsWith("ies") && normalizedTable.length() > 3) {
                base = normalizedTable.substring(0, normalizedTable.length() - 3) + "y";
            } else if (normalizedTable.endsWith("s") && normalizedTable.length() > 1) {
                base = normalizedTable.substring(0, normalizedTable.length() - 1);
            } else {
                base = normalizedTable;
            }
            base = toRuntimeField(base);
        }
        if (base.isBlank() || "default".equalsIgnoreCase(base)) {
            return "id";
        }
        return base + "Id";
    }

    private static Map<String, Object> mutableRecord(Object entity) {
        if (!(entity instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Persistence save expects a map-like entity");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static Map<String, Object> criteriaMap(Object criteria) {
        if (criteria == null) {
            return Map.of();
        }
        if (!(criteria instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Query criteria must be a map");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static void ensureColumnFirst(List<String> columns, String firstColumn) {
        columns.remove(firstColumn);
        columns.add(0, firstColumn);
    }

    private static String toDbColumn(String name) {
        if (name == null || name.isBlank() || "id".equals(name)) {
            return name;
        }
        StringBuilder sb = new StringBuilder();
        char[] chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char ch = chars[i];
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String toRuntimeField(String columnLabel) {
        if (columnLabel == null || columnLabel.isBlank() || !columnLabel.contains("_")) {
            return columnLabel;
        }
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char ch : columnLabel.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            if (upperNext) {
                sb.append(Character.toUpperCase(ch));
                upperNext = false;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String buildUpsertSql(Connection connection, String table, List<String> columns, String idColumn) {
        if (isH2Connection(connection)) {
            return buildH2UpsertSql(table, columns, idColumn);
        }
        return buildPostgresUpsertSql(table, columns, idColumn);
    }

    private static boolean isH2Connection(Connection connection) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            if (metaData == null) {
                return false;
            }

            String productName = metaData.getDatabaseProductName();
            return productName != null && productName.toLowerCase(Locale.ROOT).contains("h2");
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static String buildPostgresUpsertSql(String table, List<String> columns, String idColumn) {
        StringBuilder sb = new StringBuilder();
        sb.append("insert into ").append(table).append(" (");
        sb.append(String.join(", ", columns));
        sb.append(") values (");
        sb.append(String.join(", ", Collections.nCopies(columns.size(), "?")));
        sb.append(") on conflict (").append(idColumn).append(") do update set ");

        List<String> sets = new ArrayList<>();
        for (String col : columns) {
            if (idColumn.equalsIgnoreCase(col)) {
                continue;
            }
            sets.add(col + " = excluded." + col);
        }
        sb.append(String.join(", ", sets));
        return sb.toString();
    }

    private static String buildH2UpsertSql(String table, List<String> columns, String idColumn) {
        StringBuilder sb = new StringBuilder();
        sb.append("merge into ").append(table).append(" (");
        sb.append(String.join(", ", columns));
        sb.append(") key(").append(idColumn).append(") values (");
        sb.append(String.join(", ", Collections.nCopies(columns.size(), "?")));
        sb.append(")");
        return sb.toString();
    }

    private static Object coerceValueForColumn(String column, Object value) {
        if (isUuidColumn(column)) {
            return coerceUuid(value);
        }
        if (isDateColumn(column)) {
            return coerceDate(value);
        }
        if (isTimestampColumn(column)) {
            return coerceTimestamp(value);
        }
        return value;
    }

    private static boolean isUuidColumn(String column) {
        if (column == null || column.isBlank()) {
            return false;
        }
        String normalized = column.toLowerCase(Locale.ROOT);
        return "id".equals(normalized) || normalized.endsWith("_id");
    }

    private static boolean isDateColumn(String column) {
        if (column == null || column.isBlank()) {
            return false;
        }
        String normalized = column.toLowerCase(Locale.ROOT);
        return normalized.endsWith("_date") || normalized.startsWith("date_") || normalized.contains("_date_");
    }

    private static boolean isTimestampColumn(String column) {
        if (column == null || column.isBlank()) {
            return false;
        }
        String normalized = column.toLowerCase(Locale.ROOT);
        return normalized.endsWith("_at")
                || normalized.endsWith("_time")
                || normalized.contains("timestamp");
    }

    private static Object coerceUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID) {
            return value;
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignore) {
                return value;
            }
        }
        return value;
    }

    private static Object coerceDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date || value instanceof LocalDate) {
            return value;
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return Date.valueOf(LocalDate.parse(text));
            } catch (DateTimeParseException ignore) {
                return value;
            }
        }
        return value;
    }

    private static Object coerceTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp
                || value instanceof OffsetDateTime
                || value instanceof Instant
                || value instanceof java.util.Date) {
            return value;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return Timestamp.valueOf(localDateTime);
        }
        if (value instanceof CharSequence) {
            String text = value.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(text);
            } catch (DateTimeParseException ignore) {
            }
            try {
                LocalDateTime localDateTime = LocalDateTime.parse(text);
                return Timestamp.valueOf(localDateTime);
            } catch (DateTimeParseException ignore) {
            }
            try {
                Instant instant = Instant.parse(text);
                return Timestamp.from(instant);
            } catch (DateTimeParseException ignore) {
                return value;
            }
        }
        return value;
    }

    private static Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 1; i <= cols; i++) {
            String columnLabel = md.getColumnLabel(i);
            out.put(toRuntimeField(columnLabel), rs.getObject(i));
        }
        return immutableRecord(out);
    }

    private static Map<String, Object> immutableRecord(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static final class TableColumns {
        private final boolean available;
        private final Map<String, String> columnsByLowerName = new LinkedHashMap<>();
        private final Map<String, String> columnsByRuntimeFieldLowerName = new LinkedHashMap<>();

        private TableColumns(boolean available) {
            this.available = available;
        }

        static TableColumns unavailable() {
            return new TableColumns(false);
        }

        void add(String columnName) {
            String actual = columnName.trim();
            String lower = actual.toLowerCase(Locale.ROOT);
            columnsByLowerName.put(lower, actual);
            columnsByRuntimeFieldLowerName.put(toRuntimeField(actual).toLowerCase(Locale.ROOT), actual);
        }

        boolean isAvailable() {
            return available;
        }

        boolean isEmpty() {
            return columnsByLowerName.isEmpty();
        }

        boolean hasColumn(String name) {
            return name != null && columnsByLowerName.containsKey(name.toLowerCase(Locale.ROOT));
        }

        String columnName(String name) {
            return columnsByLowerName.get(name.toLowerCase(Locale.ROOT));
        }

        boolean hasRuntimeField(String runtimeField) {
            return runtimeField != null && columnsByRuntimeFieldLowerName.containsKey(runtimeField.toLowerCase(Locale.ROOT));
        }

        String columnNameForRuntimeField(String runtimeField) {
            return columnsByRuntimeFieldLowerName.get(runtimeField.toLowerCase(Locale.ROOT));
        }

        List<String> columnNames() {
            return List.copyOf(columnsByLowerName.values());
        }

        List<String> allowedRuntimeFields() {
            Set<String> runtimeFields = new LinkedHashSet<>();
            for (String columnName : columnsByLowerName.values()) {
                runtimeFields.add(toRuntimeField(columnName));
            }
            return List.copyOf(runtimeFields);
        }
    }
}
