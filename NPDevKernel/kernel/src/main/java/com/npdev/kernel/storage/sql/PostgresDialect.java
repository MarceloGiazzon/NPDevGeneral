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
            StorageCapability.SNAPSHOT_RESTORE,
            // R8c: native "FOR UPDATE SKIP LOCKED" since Postgres 9.5 (2016).
            StorageCapability.SKIP_LOCKED_READS,
            // R9.3: pg_advisory_lock, session-scoped and needing no table -- so it can protect a
            // FIRST-EVER boot, before any NPDev table exists to lock.
            StorageCapability.SESSION_ADVISORY_LOCK);

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
    public boolean isReservedIdentifier(String rawIdentifier) {
        return SqlReservedWords.isReserved(name(), rawIdentifier);
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
    public String selectForUpdateSkipLocked(
            String columns, String table, String whereClause, String orderBy, int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("engine 'postgres': maxRows must be positive, got " + maxRows);
        }
        return "SELECT " + columns + " FROM " + table + " WHERE " + whereClause
                + " ORDER BY " + orderBy + " LIMIT " + maxRows + " FOR UPDATE SKIP LOCKED";
    }

    @Override
    public String renameColumn(String table, String from_, String to) {
        // STOR-6: quoted here, as the syntax is composed. SqlServerDialect deliberately does NOT
        // do this -- its sp_rename takes both names inside a string LITERAL.
        return "ALTER TABLE " + identifier(table) + " RENAME COLUMN " + identifier(from_)
                + " TO " + identifier(to);
    }

    @Override
    public boolean isUniqueViolation(java.sql.SQLException failure) {
        if (failure == null) {
            return false;
        }
        // 23505 = unique_violation. NOT the whole 23 class: 23502 (NOT NULL) and 23503
        // (foreign key) leave the row genuinely absent, which is a real failure.
        for (java.sql.SQLException current = failure; current != null;
                current = current.getNextException()) {
            if ("23505".equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
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
    public String guardedCreateSchema(String schemaName) {
        return "CREATE SCHEMA IF NOT EXISTS " + schemaName;
    }

    /**
     * R9.3. {@code bigint}, not a name: Postgres keys advisory locks numerically, so a name has to
     * be hashed to reach one.
     *
     * <p><b>FNV-1a 64-bit, not {@link String#hashCode()}.</b> Both are deterministic across JVMs, so
     * either would be reproducible -- the difference is collision width, and it matters because the
     * two callers have very different key counts. The migration mutex has exactly ONE key, where a
     * collision is meaningless. {@code PostgresAdvisoryBulkheadStore} derives a key per
     * {@code tenant|capability|operation|slot} and can hold thousands, where a collision silently
     * merges two unrelated bulkheads into one -- a concurrency limit applied to the wrong operation,
     * with nothing to observe. That store already used FNV-1a for exactly this reason and predates
     * this method; adopting its derivation here is what lets it route through the dialect with its
     * lock ids BYTE-IDENTICAL to what it computes today, rather than silently re-keying every
     * bulkhead in a live system on upgrade.
     *
     * <p>The migration mutex's own key does change from the {@code String.hashCode} value
     * {@code MigrationClaimStore} used while it spelled this lock inline. That is only visible to
     * two NPDev builds straddling this commit booting against one database at the same instant, and
     * the cost of getting it wrong there (one interleaved migration) is smaller than the cost of a
     * silent bulkhead collision, which is permanent.
     */
    @Override
    public Object advisoryLockKey(String lockName) {
        return fnv1a64(lockName);
    }

    /** FNV-1a 64-bit -- stable across process restarts and JVM versions by construction (it is
     *  arithmetic on char values, not a JDK-specified algorithm that could be retuned). */
    private static long fnv1a64(String input) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < input.length(); index++) {
            hash ^= input.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** R9.3. Normalised to 1/0 -- {@code pg_try_advisory_lock} returns a boolean, and the two other
     * advisory-lock engines return neither a boolean nor the same numbers. */
    @Override
    public String tryAdvisoryLockSql() {
        return "SELECT CASE WHEN pg_try_advisory_lock(?) THEN 1 ELSE 0 END";
    }

    @Override
    public String releaseAdvisoryLockSql() {
        return "SELECT CASE WHEN pg_advisory_unlock(?) THEN 1 ELSE 0 END";
    }

    @Override
    public String guardedConstraintDdl(String constraintName, String tableName, String ddlStatement) {
        // INFORMATION_SCHEMA.TABLE_CONSTRAINTS is standard SQL available in both PostgreSQL
        // and H2 PostgreSQL-compatibility mode. pg_constraint/pg_class/pg_namespace are
        // PostgreSQL-only system catalogs and must not be used even in the Postgres-only path,
        // to keep both emitters byte-consistent and avoid drift when switching engines.
        //
        // REG-147: the statement sits inside a PL/pgSQL IF ... THEN <statement> END IF; block, where
        // the semicolon is a mandatory statement terminator, not an optional trailing character --
        // unlike a flat top-level script, PL/pgSQL will not tolerate a missing one even when the
        // statement is the last thing before END IF. Every caller in SchemaRealizationEmitter builds
        // its ALTER TABLE text WITHOUT a trailing ";" (the same raw text is also handed to H2/MySQL/
        // SQL Server, whose own guardedConstraintDdl each normalize it below), so this dialect must
        // normalize it too instead of assuming the caller already did.
        String statement = ddlStatement.endsWith(";") ? ddlStatement : ddlStatement + ";";
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
                """.formatted(escapeLiteral(constraintName), escapeLiteral(tableName), statement);
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

        private static String quoted(String rawIdentifier) {
            return PostgresDialect.INSTANCE.identifier(rawIdentifier);
        }

        private static List<String> quotedAll(List<String> rawIdentifiers) {
            return PostgresDialect.INSTANCE.identifiers(rawIdentifiers);
        }
        @Override
        public String statementFor(String rawTable, List<String> keyColumns, List<String> valueColumns) {
            // STOR-6: quote HERE, as the text is composed. The caller keeps raw names for
            // its map lookups, and bindColumns() must echo those back unquoted.
            String table = PostgresDialect.INSTANCE.identifier(rawTable);
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
                    updates.add(quoted(column) + " = EXCLUDED." + quoted(column));
                }
            }
            String placeholders = String.join(", ", java.util.Collections.nCopies(valueColumns.size(), "?"));
            StringBuilder sql = new StringBuilder()
                    .append("INSERT INTO ").append(table)
                    .append(" (").append(String.join(", ", quotedAll(valueColumns))).append(")")
                    .append(" VALUES (").append(placeholders).append(")")
                    .append(" ON CONFLICT (").append(String.join(", ", quotedAll(keyColumns))).append(")");
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
