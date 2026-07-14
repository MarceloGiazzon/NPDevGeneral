package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
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

    /**
     * LNCH-5: pushes the filter/sort/page window down to SQL. Column names are resolved through the
     * compiled model's field->column whitelist (never taken from raw input), and every filter value
     * is a bound parameter, so this is not a string-concatenation injection surface. {@code total} is
     * a matching {@code COUNT(*)} rather than a materialize-everything count, and {@code LIMIT}/{@code
     * OFFSET} keep the JVM from ever holding more than one page. A stable {@code ORDER BY} (the id
     * column when the caller declares no sort) makes OFFSET paging deterministic.
     */
    @Override
    public ConceptPage query(String tenantId, String conceptName, ConceptQuery query) {
        ConceptShape shape = shape(conceptName);
        ConceptQuery effective = query == null ? ConceptQuery.firstPage() : query;

        List<String> whereClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        whereClauses.add("tenant_id = ?");
        params.add(tenantId);
        for (ConceptQuery.Filter filter : effective.filters()) {
            String column = requireColumn(shape, filter.field());
            if (filter.operator() == ConceptQuery.Operator.CONTAINS) {
                // CAST to VARCHAR first: Postgres's LOWER() rejects non-text input outright (unlike
                // H2, which silently coerces), so a "contains" filter against a numeric/UUID column
                // would otherwise work under H2 in dev and fail under Postgres in production.
                whereClauses.add("LOWER(CAST(" + column + " AS VARCHAR)) LIKE ? ESCAPE '\\'");
                params.add("%" + likeEscape(String.valueOf(filter.value()).toLowerCase(Locale.ROOT)) + "%");
                continue;
            }
            String dslType = shape.dslTypeByColumn().get(column.toLowerCase(Locale.ROOT));
            whereClauses.add(column + " " + sqlOperator(filter.operator()) + " ?");
            params.add(coerceValue(column, filter.value(), dslType));
        }
        String whereSql = String.join(" AND ", whereClauses);
        String orderSql = orderByClause(shape, effective.sorts());

        try (Connection connection = dataSource.getConnection()) {
            long total;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + shape.tableName() + " WHERE " + whereSql)) {
                bindParams(statement, params, 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    total = resultSet.getLong(1);
                }
            }

            List<ConceptRecord> items = new ArrayList<>();
            String pageSql = "SELECT * FROM " + shape.tableName() + " WHERE " + whereSql + orderSql
                    + " LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(pageSql)) {
                int nextIndex = bindParams(statement, params, 1);
                statement.setInt(nextIndex++, effective.limit());
                statement.setInt(nextIndex, effective.offset());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        items.add(toRecord(shape, tenantId, resultSet));
                    }
                }
            }
            return ConceptPage.of(items, total, effective.offset());
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed querying concept " + conceptName + " from JDBC store", exception);
        }
    }

    private String requireColumn(ConceptShape shape, String field) {
        String column = shape.columnByField().get(field.toLowerCase(Locale.ROOT));
        if (column == null && shape.idColumn().equalsIgnoreCase(toDbColumn(field))) {
            column = shape.idColumn();
        }
        if (column == null) {
            throw new IllegalArgumentException(
                    "Unknown query field '" + field + "' for concept " + shape.conceptName()
                            + " -- only declared fields may be filtered or sorted");
        }
        return column;
    }

    private String orderByClause(ConceptShape shape, List<ConceptQuery.Sort> sorts) {
        if (sorts.isEmpty()) {
            // OFFSET paging is only deterministic under a stable order; default to the primary key.
            return " ORDER BY " + shape.idColumn();
        }
        List<String> terms = new ArrayList<>();
        for (ConceptQuery.Sort sort : sorts) {
            String column = requireColumn(shape, sort.field());
            terms.add(column + (sort.descending() ? " DESC" : " ASC"));
        }
        return " ORDER BY " + String.join(", ", terms);
    }

    private static String sqlOperator(ConceptQuery.Operator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NEQ -> "<>";
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
            case CONTAINS -> throw new IllegalStateException("CONTAINS is compiled to LIKE, not a binary operator");
        };
    }

    /** Escapes LIKE wildcard characters in a literal search term bound as a parameter. */
    private static String likeEscape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private int bindParams(PreparedStatement statement, List<Object> params, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object param : params) {
            statement.setObject(index++, param);
        }
        return index;
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
                    statement.setObject(index++, coerceValue(column, dbRecord.get(column), shape.dslTypeByColumn().get(column)));
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
            if (isJsonColumnType(metaData, index) || isJsonDslField(shape, column)) {
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
     * HARDEN-GC: {@code isJsonColumnType} trusts the JDBC driver's reported column type name, which
     * H2 does not reliably report as "JSON"/"JSONB" for a JSON column (confirmed live: a {@code
     * file}-typed field's column came back with a type name that failed that check, so the already
     * JSON-encoded write-side string was handed straight through instead of being parsed --
     * `attachment` field values round-tripped as a raw JSON string instead of a nested
     * object/array, silently defeating any code reading the field's structure, e.g. the
     * delete/replace-cascade file-field extraction). The DSL's own declared type (object/array/file
     * all map to a JSON/JSONB column per SqlTypeSupport) is authoritative and engine-independent,
     * so prefer it over trusting the driver.
     */
    private static boolean isJsonDslField(ConceptShape shape, String column) {
        String dslType = shape.dslTypeByColumn().get(column.toLowerCase(Locale.ROOT));
        return "object".equalsIgnoreCase(dslType) || "array".equalsIgnoreCase(dslType) || "file".equalsIgnoreCase(dslType);
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
            Map<String, String> dslTypeByColumn = new LinkedHashMap<>();
            for (CompiledField field : concept.getFields()) {
                String column = toDbColumn(field.getName());
                columnByField.put(field.getName().toLowerCase(Locale.ROOT), column);
                fieldByColumn.put(column.toLowerCase(Locale.ROOT), field.getName());
                dslTypeByColumn.put(column.toLowerCase(Locale.ROOT), field.getDslType());
                if (field.isId()) {
                    idColumn = column;
                }
            }
            out.put(normalize(concept.getName()), new ConceptShape(concept.getName(), table, idColumn, columnByField, fieldByColumn, dslTypeByColumn));
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

    private static Object coerceValue(String column, Object value, String dslType) {
        if (value == null) {
            return null;
        }
        if (isUuidColumn(column)) {
            return coerceId(value);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            // Object/array/file DSL fields map to a JSON/JSONB column (SqlTypeSupport). Handing the
            // JDBC driver a raw Map/List makes it default to JAVA_OBJECT, which H2 (and Postgres)
            // both reject for a JSON-typed column ("Data conversion error converting JAVA_OBJECT
            // to JSON"). Binding the JSON text as a java.lang.String isn't safe either: H2's JSON
            // column treats a bound String as a JSON *string value* and quotes/escapes it rather
            // than storing it as the object it represents (confirmed live: a file field's handle
            // round-tripped as a JSON-encoded string instead of a nested object, silently defeating
            // GeneratedCrudRuntimeSupport's file-field extraction on delete/replace-cascade).
            // Binding raw JSON bytes instead is accepted as JSON content by both H2 and Postgres.
            try {
                return JSON_COLUMN_MAPPER.writeValueAsBytes(value);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to serialize column \"" + column + "\" to JSON", exception);
            }
        }
        if (value instanceof String text && !text.isBlank()) {
            // A "date"/"datetime" DSL field's JSON value is always a plain ISO-8601 string (the
            // REST layer never sends a java.sql.Date/Timestamp). H2's JDBC driver silently casts a
            // bound VARCHAR into a DATE/TIMESTAMP column; real Postgres does not
            // ("column is of type date but expression is of type character varying") and rejects
            // it outright, so every date/datetime-bearing concept (Lote, Recebimento, Expedicao,
            // Movimento, DocumentoFiscal, InventarioArquivo, ...) failed to save under Postgres
            // specifically until this conversion was added.
            if ("date".equals(dslType)) {
                return java.sql.Date.valueOf(java.time.LocalDate.parse(text));
            }
            if ("datetime".equals(dslType)) {
                return java.sql.Timestamp.from(parseDateTime(text));
            }
            // LNCH-5: a filter value arriving as a String (e.g. a REST query parameter) against a
            // numeric column must bind as a number -- H2 silently casts a VARCHAR, but real Postgres
            // rejects "int = varchar". Leave non-numeric text untouched.
            if (isNumericDslType(dslType)) {
                try {
                    return new java.math.BigDecimal(text.trim());
                } catch (NumberFormatException ignored) {
                    return value;
                }
            }
        }
        return value;
    }

    private static boolean isNumericDslType(String dslType) {
        if (dslType == null) {
            return false;
        }
        return switch (dslType.trim().toLowerCase(Locale.ROOT)) {
            case "int", "integer", "long", "bigint", "decimal", "number", "numeric",
                    "float", "double", "money", "currency" -> true;
            default -> false;
        };
    }

    private static java.time.Instant parseDateTime(String text) {
        try {
            return java.time.OffsetDateTime.parse(text).toInstant();
        } catch (java.time.format.DateTimeParseException ignored) {
            return java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.systemDefault()).toInstant();
        }
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
            Map<String, String> fieldByColumn,
            Map<String, String> dslTypeByColumn
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
