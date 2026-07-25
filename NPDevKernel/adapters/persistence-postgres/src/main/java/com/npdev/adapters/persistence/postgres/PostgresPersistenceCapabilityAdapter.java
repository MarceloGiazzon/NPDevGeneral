
package com.npdev.adapters.persistence.postgres;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ports.PersistenceCapabilityContract;
import com.npdev.kernel.ports.TenantScope;
import com.npdev.kernel.ports.TenantScopedPersistenceCapabilityContract;

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
public final class PostgresPersistenceCapabilityAdapter
        implements PersistenceCapabilityContract, TenantScopedPersistenceCapabilityContract {

    private final DataSource dataSource;
    private final CompiledModel compiledModel;
    private final Map<String, TableColumns> tableColumnsCache = new ConcurrentHashMap<>();

    public PostgresPersistenceCapabilityAdapter(DataSource dataSource) {
        this(dataSource, null);
    }

    // Flow-compiled createConcept/updateConcept steps dispatch straight to this adapter's save(),
    // bypassing the generated per-concept service's enforceWithKernel/validateLifecycleTransitions
    // check that generic CRUD PUT/POST goes through (that check only exists in generated code, not
    // here). Without compiledModel, a concept's lifecycle-declared transitions were silently
    // unenforced on this path -- confirmed live: a flow-driven update jumped a Recebimento straight
    // from its initial stage to its terminal stage, skipping two required stages, with no error.
    public PostgresPersistenceCapabilityAdapter(DataSource dataSource, CompiledModel compiledModel) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.compiledModel = compiledModel;
    }

    @Override
    public Object save(Object entity) {
        return save("default", entity);
    }

    public Object save(Object concept, Object entity) {
        String table = tableName(concept);
        Map<String, Object> runtimeRecord = mutableRecord(entity);
        applyFieldDefaults(concept, runtimeRecord);
        String conceptIdField = inferredRuntimeIdField(concept, table);

        Object id = runtimeRecord.get("id");
        if (id == null && conceptIdField != null) {
            id = runtimeRecord.get(conceptIdField);
        }
        if (id == null || String.valueOf(id).isBlank()) {
            id = UUID.randomUUID().toString();
        }
        // Always normalize the id onto the canonical runtime "id" key. The schema-aware
        // resolveColumn maps "id" to whatever the table's real primary-key column is (e.g.
        // "id" or "user_id"), so injecting a concept-derived field like "contactId" only
        // produces a phantom column that normalizeRecordForSave rejects.
        runtimeRecord.put("id", id);

        try (Connection connection = dataSource.getConnection()) {
            TableColumns tableColumns = resolveTableColumns(connection, table);
            enforceLifecycleTransition(connection, concept, table, tableColumns, id, runtimeRecord);
            Map<String, Object> sqlRecord = normalizeRecordForSave(table, runtimeRecord, tableColumns);
            String idColumn = resolveIdColumn(table, tableColumns);

            List<String> columns = new ArrayList<>(sqlRecord.keySet());
            ensureColumnFirst(columns, idColumn);

            String sql = buildUpsertSql(connection, table, columns, idColumn);
            Map<String, String> dslTypeByColumn = dslTypeByColumn(concept, table, tableColumns);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int idx = 1;
                for (String column : columns) {
                    Object value = sqlRecord.get(column);
                    value = coerceValueForColumn(column, value, dslTypeByColumn.get(column));
                    ps.setObject(idx++, value);
                }
                ps.executeUpdate();
                return immutableRecord(runtimeRecord);
            }

        } catch (SQLException e) {
            if (isIntegrityConstraintViolation(e)) {
                // SQLState class 23 = integrity constraint violation (foreign key / unique / not null
                // / check). Surface it as IllegalArgumentException so the kernel classifies the
                // capability failure as a CONTRACT violation (a bad/missing reference supplied by the
                // caller) instead of a generic system_exception. The DB still rejects the write — this
                // only sharpens the error code reported to the caller (e.g. a bond to a missing row).
                throw new IllegalArgumentException(
                        "Referential/constraint violation persisting table " + table + ": " + e.getMessage(), e);
            }
            throw new IllegalStateException("Postgres persistence save failed for table " + table + ": " + e.getMessage(), e);
        }
    }

    private void enforceLifecycleTransition(
            Connection connection,
            Object concept,
            String table,
            TableColumns tableColumns,
            Object id,
            Map<String, Object> runtimeRecord
    ) throws SQLException {
        if (compiledModel == null) {
            return;
        }
        CompiledConcept compiledConcept = findCompiledConcept(concept);
        if (compiledConcept == null) {
            return;
        }
        CompiledLifecycle lifecycle = compiledConcept.getLifecycle();
        if (lifecycle == null || lifecycle.getStatusField() == null || lifecycle.getStatusField().isBlank()) {
            return;
        }
        String statusField = lifecycle.getStatusField();
        if (!runtimeRecord.containsKey(statusField)) {
            return;
        }
        String statusColumn = resolveColumn(table, statusField, tableColumns);
        String idColumn = resolveIdColumn(table, tableColumns);
        if (statusColumn == null) {
            return;
        }

        String previousValue = null;
        boolean rowExists = false;
        String sql = "SELECT " + statusColumn + " FROM " + table + " WHERE " + idColumn + " = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, coerceValueForColumn(idColumn, id));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    rowExists = true;
                    Object raw = rs.getObject(1);
                    previousValue = raw == null ? null : String.valueOf(raw);
                }
            }
        }
        if (!rowExists || previousValue == null) {
            return;
        }

        Object nextValueRaw = runtimeRecord.get(statusField);
        String nextValue = nextValueRaw == null ? null : String.valueOf(nextValueRaw);
        if (previousValue.equals(nextValue)) {
            return;
        }
        String fromValue = previousValue;
        boolean allowed = lifecycle.getTransitions().stream()
                .anyMatch(transition -> fromValue.equals(transition.getFrom())
                        && Objects.equals(nextValue, transition.getTo()));
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Status transition from '" + previousValue + "' to '" + nextValue
                            + "' is not allowed for " + compiledConcept.getName());
        }
    }

    /**
     * Maps each of the target concept's declared "date"/"datetime" fields onto its resolved
     * column name, so {@link #coerceValueForColumn} can convert the JSON-string value it receives
     * from a Flow's runtime payload into a real {@code java.sql.Date}/{@code Timestamp} instead of
     * relying on {@code isDateColumn}/{@code isTimestampColumn}'s English-naming-convention
     * heuristic (which never matched Portuguese-named columns like {@code data_movimento} —
     * confirmed live: every date-bearing WmsOffice concept saved via a Flow's createConcept/
     * updateConcept step failed against Postgres with "column is of type date but expression is
     * of type character varying" until this lookup was added). Empty when the model doesn't know
     * the concept (mirrors every other {@code compiledModel == null} guard in this class).
     */
    private Map<String, String> dslTypeByColumn(Object concept, String table, TableColumns tableColumns) {
        CompiledConcept compiledConcept = findCompiledConcept(concept);
        if (compiledConcept == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (CompiledField field : compiledConcept.getFields()) {
            String dslType = field.getDslType();
            if (!"date".equals(dslType) && !"datetime".equals(dslType)) {
                continue;
            }
            String column = resolveColumn(table, field.getName(), tableColumns);
            if (column != null) {
                out.put(column, dslType);
            }
        }
        return out;
    }

    // Flow-compiled createConcept/updateConcept steps dispatch straight to save() (see the
    // constructor note above), bypassing ConceptGatewaySemanticPolicy.applyDefaultsAndDerivedValues
    // -- the pass generic CRUD create goes through. Without this, a flow create that omits a field
    // with a declared default persisted it as null/missing instead of the default (ARCH-8b).
    // Scoped to literal defaultValue only, matching the CRUD path's simplest/most common case --
    // defaultExpression (computed from other fields) is not evaluated here.
    private void applyFieldDefaults(Object concept, Map<String, Object> runtimeRecord) {
        CompiledConcept compiledConcept = findCompiledConcept(concept);
        if (compiledConcept == null) {
            return;
        }
        for (CompiledField field : compiledConcept.getFields()) {
            if (field.getSchema() == null || field.getSchema().getDefaultValue() == null) {
                continue;
            }
            Object existing = runtimeRecord.get(field.getName());
            if (existing == null || (existing instanceof String text && text.isBlank())) {
                runtimeRecord.put(field.getName(), field.getSchema().getDefaultValue());
            }
        }
    }

    private CompiledConcept findCompiledConcept(Object concept) {
        if (concept == null || compiledModel == null) {
            return null;
        }
        String name = String.valueOf(concept);
        for (CompiledConcept candidate : compiledModel.getConcepts()) {
            if (candidate.getName().equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isIntegrityConstraintViolation(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String sqlState = current.getSQLState();
            if (sqlState != null && sqlState.startsWith("23")) {
                return true;
            }
        }
        String message = exception.getMessage() == null
                ? ""
                : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("referential integrity")
                || message.contains("foreign key")
                || message.contains("unique index or primary key")
                || message.contains("unique constraint")
                || message.contains("constraint violation");
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


    // ---------------------------------------------------------------------------------------------
    // REG-46: the tenant-scoped port. The tenant is prepended by RegistryCapabilityDispatcher from the
    // flow's authenticated state -- it is never one of the model author's declared step arguments.
    //
    // Scoping is applied only when the table actually HAS a tenant_id column, decided from the live
    // catalog the adapter already reads. Internal or shared tables without one keep working exactly as
    // before, and no statement gains a predicate the database would reject.
    // ---------------------------------------------------------------------------------------------

    private static final String TENANT_COLUMN = "tenant_id";

    @Override
    public Object save(TenantScope scope, Object entity) {
        return save(stampTenant(entity, scope.tenantId()));
    }

    @Override
    public Object findById(TenantScope scope, Object concept, Object id) {
        if (id == null) {
            return null;
        }
        String table = tableName(concept);
        try (Connection c = dataSource.getConnection()) {
            TableColumns tableColumns = resolveTableColumns(c, table);
            if (!tableColumns.hasColumn(TENANT_COLUMN)) {
                return findById(concept, id);
            }
            String idColumn = resolveIdColumn(table, tableColumns);
            String sql = "select * from " + table + " where " + idColumn + " = ? and "
                    + tableColumns.columnName(TENANT_COLUMN) + " = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, coerceValueForColumn(idColumn, id));
                ps.setString(2, scope.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rowToMap(rs) : null;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Postgres persistence findById failed for table " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Object query(TenantScope scope, Object concept, Object criteria) {
        // query() already builds an equality predicate per criterion, so scoping is just one more
        // criterion -- which also means it goes through the same column-resolution and binding path
        // rather than a second, hand-written one that could drift from it.
        return query(concept, withTenantCriterion(concept, criteria, scope.tenantId()));
    }

    @Override
    public Object delete(TenantScope scope, Object concept, Object id) {
        if (id == null) {
            return false;
        }
        String table = tableName(concept);
        try (Connection c = dataSource.getConnection()) {
            TableColumns tableColumns = resolveTableColumns(c, table);
            if (!tableColumns.hasColumn(TENANT_COLUMN)) {
                return delete(concept, id);
            }
            String idColumn = resolveIdColumn(table, tableColumns);
            String sql = "delete from " + table + " where " + idColumn + " = ? and "
                    + tableColumns.columnName(TENANT_COLUMN) + " = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, coerceValueForColumn(idColumn, id));
                ps.setString(2, scope.tenantId());
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Postgres persistence delete failed for table " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Object exists(TenantScope scope, Object concept, Object field, Object value) {
        String table = tableName(concept);
        String fieldName = Objects.toString(field, "").trim();
        if (fieldName.isBlank()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            TableColumns tableColumns = resolveTableColumns(connection, table);
            if (!tableColumns.hasColumn(TENANT_COLUMN)) {
                return exists(concept, field, value);
            }
            String column = resolveCriteriaColumn(table, fieldName, tableColumns);
            String sql = "select 1 from " + table + " where " + column + " = ? and "
                    + tableColumns.columnName(TENANT_COLUMN) + " = ? limit 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setObject(1, coerceValueForColumn(column, value));
                ps.setString(2, scope.tenantId());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Postgres persistence exists failed for table " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Object unique(TenantScope scope, Object concept, Object field, Object value) {
        // Deliberately tenant-scoped: "is this value free?" must be asked of the caller's OWN rows.
        // Asking globally would both leak the existence of another tenant's row and refuse a value
        // this tenant is entitled to use.
        return !(Boolean) exists(scope, concept, field, value);
    }

    /** Forces the tenant onto a save payload so a caller cannot write into another tenant. */
    private static Object stampTenant(Object entity, String tenantId) {
        if (!(entity instanceof Map<?, ?> source) || tenantId == null || tenantId.isBlank()) {
            return entity;
        }
        Map<String, Object> stamped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            stamped.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        stamped.put("tenantId", tenantId);
        stamped.put(TENANT_COLUMN, tenantId);
        return stamped;
    }

    /** Adds the tenant as one more equality criterion, when the table carries the column. */
    private Object withTenantCriterion(Object concept, Object criteria, String tenantId) {
        String table = tableName(concept);
        try (Connection connection = dataSource.getConnection()) {
            if (!resolveTableColumns(connection, table).hasColumn(TENANT_COLUMN)) {
                return criteria;
            }
        } catch (SQLException ignored) {
            return criteria;
        }
        Map<String, Object> scoped = new LinkedHashMap<>(criteriaMap(criteria));
        scoped.put(TENANT_COLUMN, tenantId);
        return scoped;
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
        String raw = Objects.toString(concept, "default").trim();
        if (raw.isBlank()) {
            raw = "default";
        }
        // Concept names are PascalCase/camelCase (e.g. "CareLogEntry"); the generated schema's
        // table names are snake_case-pluralized (e.g. "care_log_entrys") via SqlIdentifierSupport.
        // A plain toLowerCase() here would compute "carelogentrys" and never find the real table --
        // this conversion must keep matching that naming, since callers (e.g. Flow createConcept
        // steps) pass bare concept names rather than already-resolved table names.
        String snake = toSnakeCase(raw);
        if (snake.isBlank()) {
            snake = "default";
        }
        return snake.endsWith("s") ? snake : snake + "s";
    }

    private static String toSnakeCase(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        char previous = '\0';
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isUpperCase(current) && index > 0
                    && (Character.isLowerCase(previous) || Character.isDigit(previous))) {
                out.append('_');
            }
            if (Character.isLetterOrDigit(current)) {
                out.append(Character.toLowerCase(current));
            } else {
                out.append('_');
            }
            previous = current;
        }
        return out.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
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

    private static Object coerceValueForColumn(String column, Object value, String knownDslType) {
        // Model-driven check first (exact, works for any column-naming language) -- falls back to
        // the heuristic-based overload's English-naming guesses only when the caller has no
        // CompiledModel field-type info for this column (e.g. the id-lookup/delete call sites).
        if (value instanceof CharSequence) {
            if ("date".equals(knownDslType)) {
                return coerceDate(value);
            }
            if ("datetime".equals(knownDslType)) {
                return coerceTimestamp(value);
            }
        }
        return coerceValueForColumn(column, value);
    }

    private static Object coerceValueForColumn(String column, Object value) {
        // Object/array fields arrive here as a parsed Map/List (from the Flow's runtime
        // payload). Passing that raw Java structure straight to setObject() makes the JDBC
        // driver serialize it as a JAVA_OBJECT blob instead of writing it into the column's
        // actual JSON type, which the generated (non-Flow) CRUD repository path avoids by
        // always writing JSON text. Mirror that here so Flow-driven persistence of nested
        // object/array fields round-trips the same way.
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return toJsonText(value);
        }
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

    private static String toJsonText(Object value) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, value);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJsonString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                appendJson(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendJson(sb, item);
            }
            sb.append(']');
        } else if (value instanceof CharSequence) {
            appendJsonString(sb, value.toString());
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else {
            appendJsonString(sb, value.toString());
        }
    }

    private static void appendJsonString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
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
