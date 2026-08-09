package com.npdev.kernel.storage.sql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * PostgreSQL.
 *
 * <p><b>Every string this class returns is the exact text the call site emitted before extraction.</b>
 * That is S1's exit condition, and it is why the clauses are named constants rather than inline
 * literals: {@code storage/helpers/capture-sql-baseline.py} reads those constants out of this file
 * and substitutes them back into the routed call sites, so the before/after SQL comparison stays a
 * real comparison instead of noticing that text moved.
 *
 * <p>Do not "improve" anything here. An improvement and a dialect bug are indistinguishable once
 * they arrive in the same change.
 */
public final class PostgresDialect implements SqlDialect {

    /** The one instance; the dialect is stateless. */
    public static final PostgresDialect INSTANCE = new PostgresDialect();

    // ---- clause constants. capture-sql-baseline.py parses these; keep them simple literals. ----
    static final String LIMIT_OFFSET_CLAUSE = "LIMIT ? OFFSET ?";
    static final String LIMIT_ONLY_CLAUSE = "LIMIT ?";
    static final String ROW_LIMIT_PREFIX = "LIMIT ";
    static final String CONSTRAINT_EXISTS_SQL = "SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE UPPER(CONSTRAINT_NAME) = UPPER(?) AND UPPER(TABLE_NAME) = UPPER(?)";
    static final String TABLE_EXISTS_SQL = "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema) = LOWER(CURRENT_SCHEMA()) AND LOWER(table_name) = '%s'";

    private static final Set<String> JSON_TYPE_NAMES = Set.of("json", "jsonb");

    /**
     * {@code pg_catalog} is Postgres-only; {@code information_schema} is standard. Both were spelled
     * by hand in SchemaLifecycleExecutor and CurrentSchemaReader before this existed.
     */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog");

    private static final Set<StorageCapability> CAPABILITIES = Set.of(
            StorageCapability.TRANSACTIONS,
            // Postgres is transactional for DDL -- a failed migration rolls back. The schema engine's
            // safety model depends on this being true, and on MySQL it will not be.
            StorageCapability.DDL_IN_TRANSACTION,
            StorageCapability.SCHEMA_EVOLUTION,
            StorageCapability.FOREIGN_KEYS,
            StorageCapability.UNIQUE_CONSTRAINTS,
            StorageCapability.SERVER_SIDE_JOIN,
            StorageCapability.AGGREGATION_PIPELINE,
            StorageCapability.OPTIMISTIC_LOCKING,
            StorageCapability.SNAPSHOT_RESTORE);

    private final UpsertStrategy upsert = new PostgresUpsertStrategy();
    private final ReturningStrategy returning = new PostgresReturningStrategy();

    private PostgresDialect() {
    }

    @Override
    public String name() {
        return "postgres";
    }

    // ------------------------------------------------------------------ identifiers

    @Override
    public String quoteIdentifier(String rawIdentifier) {
        Objects.requireNonNull(rawIdentifier, "rawIdentifier");
        return '"' + rawIdentifier.replace("\"", "\"\"") + '"';
    }

    @Override
    public boolean foldsUnquotedIdentifiersToLowerCase() {
        return true;
    }

    // ------------------------------------------------------------------ DDL types

    @Override
    public String autoIncrementColumn(SqlType type) {
        return switch (type) {
            case INT -> "SERIAL";
            case BIGINT -> "BIGSERIAL";
            default -> throw new IllegalArgumentException(
                    "engine 'postgres': " + type + " cannot be an auto-increment column; use INT or BIGINT");
        };
    }

    @Override
    public String jsonColumnType() {
        return "jsonb";
    }

    @Override
    public boolean isJsonColumnType(String sqlTypeName) {
        return sqlTypeName != null && JSON_TYPE_NAMES.contains(sqlTypeName.trim().toLowerCase(Locale.ROOT));
    }

    /** Postgres can index TEXT with no limit, so this is not a restriction here -- it is agreement.
     *  Returning TEXT would make the same internal column a different type per engine. */
    @Override
    public String keyableTextColumnType() {
        return "VARCHAR(191)";
    }

    @Override
    public String defaultableTextColumnType() {
        // Postgres has no width restriction on a defaulted `text` column.
        return portableColumnType("TEXT");
    }

    @Override
    public String selectForUpdate(String columns, String table, String whereClause) {
        return "SELECT " + columns + " FROM " + table + " WHERE " + whereClause + " FOR UPDATE";
    }

    @Override
    public String renameColumn(String table, String from_, String to) {
        return "ALTER TABLE " + table + " RENAME COLUMN " + from_ + " TO " + to;
    }

    @Override
    public String timestampColumnType() {
        return "TIMESTAMP WITH TIME ZONE";
    }

    @Override
    public String portableColumnType(String declaredSqlType) {
        // Postgres understands every type NPDev's models can declare, JSON and JSONB included and
        // distinct. Returning the declaration unchanged is not a stub -- it is the correct answer,
        // and it is what the emitter did before extraction.
        return declaredSqlType;
    }

    // ------------------------------------------------------------------ DML

    @Override
    public PaginationClause limitOffset() {
        return new PaginationClause(LIMIT_OFFSET_CLAUSE,
                List.of(PaginationClause.Parameter.LIMIT, PaginationClause.Parameter.OFFSET));
    }

    @Override
    public PaginationClause limitOnly() {
        return new PaginationClause(LIMIT_ONLY_CLAUSE, List.of(PaginationClause.Parameter.LIMIT));
    }

    @Override
    public String rowLimit(long rows) {
        if (rows <= 0) {
            throw new IllegalArgumentException("engine 'postgres': rowLimit must be positive, got " + rows);
        }
        return ROW_LIMIT_PREFIX + rows;
    }

    @Override
    public boolean requiresOrderByForPagination() {
        return false;
    }

    @Override
    public UpsertStrategy upsert() {
        return upsert;
    }

    @Override
    public ReturningStrategy returning() {
        return returning;
    }

    @Override
    public String cast(String expression, SqlType type) {
        return "CAST(" + expression + " AS " + castTypeName(type) + ")";
    }

    private static String castTypeName(SqlType type) {
        return switch (type) {
            case TEXT -> "text";
            case INT -> "integer";
            case BIGINT -> "bigint";
            case UUID -> "uuid";
            case BOOLEAN -> "boolean";
            case NUMERIC -> "numeric";
            case TIMESTAMP -> "timestamp with time zone";
            case JSON -> "jsonb";
            case BLOB -> "bytea";
        };
    }

    // ------------------------------------------------------------------ introspection

    @Override
    public Set<String> systemSchemas() {
        return SYSTEM_SCHEMAS;
    }

    @Override
    public String listTablesSql() {
        return "SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = COALESCE(?, current_schema())"
                + " AND table_type = 'BASE TABLE' ORDER BY table_name";
    }

    @Override
    public String listColumnsSql() {
        return "SELECT column_name, data_type, is_nullable, column_default"
                + " FROM information_schema.columns"
                + " WHERE table_schema = COALESCE(?, current_schema()) AND table_name = ?"
                + " ORDER BY ordinal_position";
    }

    @Override
    public String listIndexesSql() {
        // information_schema has no index view; every engine answers this from its own catalog.
        return "SELECT i.relname AS index_name, a.attname AS column_name, ix.indisunique AS is_unique"
                + " FROM pg_class t"
                + " JOIN pg_index ix ON t.oid = ix.indrelid"
                + " JOIN pg_class i ON i.oid = ix.indexrelid"
                + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)"
                + " WHERE n.nspname = COALESCE(?, current_schema()) AND t.relname = ?"
                + " ORDER BY i.relname, a.attnum";
    }

    @Override
    public String tableExistsInCurrentSchemaSql(String tableName) {
        return TABLE_EXISTS_SQL.formatted(escapeLiteral(tableName).toLowerCase(Locale.ROOT));
    }

    @Override
    public String constraintExistsSql() {
        return CONSTRAINT_EXISTS_SQL;
    }

    /*
     * ------------------------------------------------------------------------------------------
     * STOR-5's three guarded idioms. This engine has them natively, so each returns the SAME TEXT
     * the emitter used to write inline -- which is the regression guard: extracting these must not
     * change one byte of Postgres output, and PostgresDialectGoldenSqlTest is what proves it.
     * ------------------------------------------------------------------------------------------
     */

    @Override
    public String guardedCreateTable(String tableName, String createStatement) {
        return SqlDdlGuards.insertAfter(createStatement, "CREATE TABLE", "IF NOT EXISTS");
    }

    @Override
    public String guardedCreateIndex(String indexName, String tableName, String createStatement) {
        return SqlDdlGuards.insertAfterIndexKeyword(createStatement, "IF NOT EXISTS");
    }

    @Override
    public String guardedAddColumn(String tableName, String columnName, String alterStatement) {
        return SqlDdlGuards.insertAfter(alterStatement, "ADD COLUMN", "IF NOT EXISTS");
    }

    @Override
    public String guardedConstraintDdl(String constraintName, String tableName, String ddlStatement) {
        // INFORMATION_SCHEMA.TABLE_CONSTRAINTS is standard SQL available in both PostgreSQL
        // and H2 PostgreSQL-compatibility mode. pg_constraint/pg_class/pg_namespace are
        // PostgreSQL-only system catalogs and must not be used even in the Postgres-only path,
        // to keep both emitters byte-consistent and avoid drift when switching engines.
        return """
                DO $$
                BEGIN
                  IF NOT EXISTS (
                    SELECT 1
                    FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                    WHERE CONSTRAINT_NAME = '%s'
                      AND TABLE_NAME = '%s'
                      AND TABLE_SCHEMA = current_schema()
                  ) THEN
                    %s
                  END IF;
                END $$;
                """.formatted(escapeLiteral(constraintName), escapeLiteral(tableName), ddlStatement);
    }

    private static String schemaPredicate(String schema) {
        return schema == null || schema.isBlank() ? "current_schema()" : "'" + escapeLiteral(schema) + "'";
    }

    private static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    // ------------------------------------------------------------------ honesty

    @Override
    public Set<StorageCapability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public String toString() {
        return "SqlDialect[postgres]";
    }

    /**
     * {@code INSERT ... ON CONFLICT (k) DO UPDATE SET c = EXCLUDED.c}.
     *
     * <p>Keyword case is canonical upper here. Two call sites spelled this statement entirely in
     * lower case before extraction; SQL keywords and the {@code EXCLUDED} pseudo-table are
     * case-insensitive on every target engine, so the normalisation is textual only. It is the one
     * enumerated deviation from S1's byte-identical rule, recorded in
     * {@code storage/evidence/S1_TEXTUAL_DELTAS.md} rather than left for a reader to discover.
     */
    static final class PostgresUpsertStrategy implements UpsertStrategy {
        @Override
        public String statementFor(String table, List<String> keyColumns, List<String> valueColumns) {
            if (keyColumns == null || keyColumns.isEmpty()) {
                throw new IllegalArgumentException("engine 'postgres': upsert needs at least one key column");
            }
            if (valueColumns == null || valueColumns.isEmpty()) {
                throw new IllegalArgumentException("engine 'postgres': upsert needs at least one value column");
            }
            Set<String> keys = new LinkedHashSet<>();
            for (String key : keyColumns) {
                keys.add(key.toLowerCase(Locale.ROOT));
            }
            List<String> updates = new ArrayList<>();
            for (String column : valueColumns) {
                if (!keys.contains(column.toLowerCase(Locale.ROOT))) {
                    updates.add(column + " = EXCLUDED." + column);
                }
            }
            String placeholders = String.join(", ", java.util.Collections.nCopies(valueColumns.size(), "?"));
            StringBuilder sql = new StringBuilder()
                    .append("INSERT INTO ").append(table)
                    .append(" (").append(String.join(", ", valueColumns)).append(")")
                    .append(" VALUES (").append(placeholders).append(")")
                    .append(" ON CONFLICT (").append(String.join(", ", keyColumns)).append(")");
            if (updates.isEmpty()) {
                // Every column is a key column: there is nothing to update, and DO UPDATE SET with an
                // empty list is a syntax error. DO NOTHING is the honest statement, not a silent skip.
                return sql.append(" DO NOTHING").toString();
            }
            return sql.append(" DO UPDATE SET ").append(String.join(", ", updates)).toString();
        }
    }

    /** Postgres returns generated columns inline; there is no second query. */
    static final class PostgresReturningStrategy implements ReturningStrategy {
        @Override
        public boolean isInline() {
            return true;
        }

        @Override
        public String inlineClause(List<String> columns) {
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("engine 'postgres': RETURNING needs at least one column");
            }
            return "RETURNING " + String.join(", ", columns);
        }

        @Override
        public String secondQuerySql() {
            // Not an UnsupportedStorageCapabilityException: this is not a missing capability, it is a
            // caller asking the wrong question of an engine that CAN do the thing inline. Reporting it
            // as a capability gap would put a false entry in front of whoever reads the failure.
            throw new UnsupportedOperationException(
                    "engine 'postgres': generated keys come back inline, so there is no second query. "
                    + "Check isInline() and use inlineClause(...), reading the columns off the insert's "
                    + "own ResultSet.");
        }
    }
}
