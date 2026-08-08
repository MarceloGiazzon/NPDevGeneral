package com.npdev.kernel.storage.sql;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * MySQL 8.
 *
 * <p><b>Pagination is free.</b> 23 of the 41 dialect-bound sites are pagination and MySQL spells it
 * exactly as Postgres does, so more than half the job carried over with no work at all. The real
 * work is the other 18 sites, and they are mostly small.
 *
 * <p><b>{@code RETURNING} does not apply here.</b> MySQL's one genuinely structural gap -- it cannot
 * return generated keys inline, needing a second query plus {@code LAST_INSERT_ID()} -- is the thing
 * a text-returning interface cannot hide, because it changes the NUMBER of statements. Measured on
 * 5680551: <b>zero production sites use RETURNING.</b> So {@link MySqlReturningStrategy} declares
 * itself non-inline and refuses both directions rather than shipping a two-statement path nothing
 * exercises. Conformance vector A2 is what will say the day that changes.
 *
 * <h2>The DDL-commit decision</h2>
 *
 * <p><b>MySQL commits implicitly on DDL.</b> Every {@code CREATE}/{@code ALTER}/{@code DROP} ends the
 * current transaction, taking any DML issued before it along with it. So
 * {@link StorageCapability#DDL_IN_TRANSACTION} is <b>absent</b> from {@link #capabilities()}, and the
 * consequence was traced through the schema engine before this class was written rather than after:
 *
 * <ul>
 *   <li>{@code ConversionHookRunner.executeAndVerify} runs a hook's convert SQL -- which contains
 *       {@code ALTER TABLE} -- and its verify in one transaction, rolling back on a verify failure.
 *       Its refusal used to read "the hook's changes were rolled back; nothing persisted". <b>That
 *       sentence is false here</b>, and it was already false on H2 (boundary B11). The message now
 *       comes from the dialect. The behaviour is deliberately unchanged: refusing to run hooks
 *       outright would break every H2 app that uses them today, and the defect was never the
 *       rollback -- it was the platform telling an operator the database was untouched when it was
 *       not. A false all-clear is what turns a recoverable half-migration into one nobody looks
 *       for.</li>
 *   <li>{@code SchemaLifecycleExecutor} does not wrap its passes in an explicit transaction; it
 *       relies on Flyway's per-migration handling and on each statement being individually
 *       idempotent ({@code IF NOT EXISTS}, and REG-38's drop-then-add for constraints). That model
 *       survives an engine without DDL rollback, which is why MySQL does not need it rewritten.</li>
 *   <li>{@code MigrationClaimStore} is pure DML and unaffected.</li>
 * </ul>
 *
 * <p>Conformance vector T2 must be green against a REAL MySQL before this capability set is trusted:
 * H2 in {@code MODE=MySQL} does not reproduce the implicit commit, so a local run cannot prove it.
 */
public final class MySqlDialect implements SqlDialect {

    public static final MySqlDialect INSTANCE = new MySqlDialect();

    static final String LIMIT_OFFSET_CLAUSE = "LIMIT ? OFFSET ?";
    static final String LIMIT_ONLY_CLAUSE = "LIMIT ?";
    static final String ROW_LIMIT_PREFIX = "LIMIT ";

    /** MySQL has exactly one JSON type and no {@code jsonb}. */
    private static final Set<String> JSON_TYPE_NAMES = Set.of("json");

    /**
     * MySQL's system schemas. Note {@code mysql} itself is one -- a user database is never called
     * that -- and {@code sys} is a view layer over {@code performance_schema}. {@code pg_catalog}
     * does not exist here, which is precisely why the two hand-written copies of
     * {@code Set.of("information_schema", "pg_catalog")} could not have survived a second engine.
     */
    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("information_schema", "mysql", "performance_schema", "sys");

    private static final Set<StorageCapability> CAPABILITIES = Set.of(
            StorageCapability.TRANSACTIONS,
            // DDL_IN_TRANSACTION deliberately ABSENT -- see the class javadoc. This is the single
            // most consequential entry in this set.
            StorageCapability.SCHEMA_EVOLUTION,
            StorageCapability.FOREIGN_KEYS,
            StorageCapability.UNIQUE_CONSTRAINTS,
            StorageCapability.SERVER_SIDE_JOIN,
            StorageCapability.AGGREGATION_PIPELINE,
            StorageCapability.OPTIMISTIC_LOCKING);
    // SNAPSHOT_RESTORE absent: mysqldump is an external tool, not something the platform can drive
    // as an engine operation, and declaring it would be a promise the generator trusts wrongly.

    private final UpsertStrategy upsert = new MySqlUpsertStrategy();
    private final ReturningStrategy returning = new MySqlReturningStrategy();

    private MySqlDialect() {
    }

    @Override
    public String name() {
        return "mysql";
    }

    // ------------------------------------------------------------------ identifiers

    @Override
    public String quoteIdentifier(String rawIdentifier) {
        Objects.requireNonNull(rawIdentifier, "rawIdentifier");
        // Backticks, which are NOT ANSI -- a shared quoting assumption breaks quietly here, which is
        // what conformance Q1 exists to catch. A backtick inside an identifier is escaped by
        // doubling it, same rule as the other engines use for their own quote character.
        return '`' + rawIdentifier.replace("`", "``") + '`';
    }

    @Override
    public boolean foldsUnquotedIdentifiersToLowerCase() {
        // Conformance Q2's pinned answer, and the reason it is pinned rather than probed: MySQL's
        // real behaviour depends on lower_case_table_names AND the host filesystem -- the same
        // server is case-sensitive on Linux and case-insensitive on Windows and macOS. There is no
        // honest single answer to "does this engine fold identifiers", so NPDev does not depend on
        // one: every generated identifier is already lower case, which makes the question moot.
        // Answering false here states the portable assumption (do not rely on folding) rather than
        // a configuration-specific truth.
        return false;
    }

    // ------------------------------------------------------------------ DDL types

    @Override
    public String autoIncrementColumn(SqlType type) {
        return switch (type) {
            case INT -> "INT AUTO_INCREMENT";
            case BIGINT -> "BIGINT AUTO_INCREMENT";
            default -> throw new IllegalArgumentException(
                    "engine 'mysql': " + type + " cannot be an auto-increment column; use INT or BIGINT");
        };
    }

    @Override
    public String jsonColumnType() {
        return "JSON";
    }

    @Override
    public boolean isJsonColumnType(String sqlTypeName) {
        return sqlTypeName != null && JSON_TYPE_NAMES.contains(sqlTypeName.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String timestampColumnType() {
        // DATETIME, not TIMESTAMP: MySQL's TIMESTAMP is a 32-bit epoch that ends in 2038 and silently
        // converts to and from the session time zone. DATETIME(6) stores what it was given, at
        // microsecond precision, which is what the platform's timestamps mean.
        return "DATETIME(6)";
    }

    @Override
    public String portableColumnType(String declaredSqlType) {
        if (declaredSqlType == null) {
            return null;
        }
        String normalized = declaredSqlType.trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if ("JSONB".equals(upper) || "JSON".equals(upper)) {
            return "JSON";
        }
        if ("TIMESTAMP WITH TIME ZONE".equals(upper) || "TIMESTAMPTZ".equals(upper)) {
            return "DATETIME(6)";
        }
        if ("UUID".equals(upper)) {
            // MySQL has no UUID type. CHAR(36) stores the canonical hyphenated form the platform
            // already writes, so no value conversion is needed anywhere else. BINARY(16) would be
            // smaller and would break every query that compares an id to a string.
            return "CHAR(36)";
        }
        if ("BOOLEAN".equals(upper) || "BOOL".equals(upper)) {
            // MySQL's BOOLEAN is already an alias for TINYINT(1); spelling it out avoids depending
            // on the alias surviving a future version.
            return "TINYINT(1)";
        }
        if ("BYTEA".equals(upper)) {
            return "LONGBLOB";
        }
        if ("TEXT".equals(upper)) {
            return "LONGTEXT";
        }
        return normalized;
    }

    // ------------------------------------------------------------------ DML

    @Override
    public PaginationClause limitOffset() {
        // Identical to Postgres. MySQL also accepts `LIMIT offset, count` -- deliberately NOT used:
        // it reverses the parameter order for no benefit, and one spelling across two engines is
        // one fewer thing to get wrong.
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
            throw new IllegalArgumentException("engine 'mysql': rowLimit must be positive, got " + rows);
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
            // MySQL's CAST accepts a restricted type list -- VARCHAR and INT are not among the
            // spellings it takes, which is exactly the sort of thing that looks portable and is not.
            case TEXT, UUID -> "CHAR";
            case INT, BIGINT -> "SIGNED";
            case BOOLEAN -> "UNSIGNED";
            case NUMERIC -> "DECIMAL";
            case TIMESTAMP -> "DATETIME";
            case JSON -> "JSON";
            case BLOB -> "BINARY";
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
                + " WHERE table_schema = COALESCE(?, DATABASE())"
                + " AND table_type = 'BASE TABLE' ORDER BY table_name";
    }

    @Override
    public String listColumnsSql() {
        return "SELECT column_name, data_type, is_nullable, column_default"
                + " FROM information_schema.columns"
                + " WHERE table_schema = COALESCE(?, DATABASE()) AND table_name = ?"
                + " ORDER BY ordinal_position";
    }

    @Override
    public String listIndexesSql() {
        // information_schema.STATISTICS, not pg_index. NON_UNIQUE is inverted relative to the column
        // name every other dialect returns, so it is flipped HERE rather than at the caller --
        // getting index uniqueness backwards is REG-129's exact bug class, and a caller that has to
        // remember which engine inverts it is one that will eventually forget.
        return "SELECT index_name AS index_name,"
                + " column_name AS column_name,"
                + " (non_unique = 0) AS is_unique"
                + " FROM information_schema.statistics"
                + " WHERE table_schema = COALESCE(?, DATABASE()) AND table_name = ?"
                + " ORDER BY index_name, seq_in_index";
    }

    @Override
    public String constraintExistsSql() {
        return "SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE UPPER(CONSTRAINT_NAME) = UPPER(?) AND UPPER(TABLE_NAME) = UPPER(?)";
    }

    @Override
    public String tableExistsInCurrentSchemaSql(String tableName) {
        // DATABASE(), not CURRENT_SCHEMA(): MySQL has no CURRENT_SCHEMA, and a schema IS a database
        // here. This is why the whole statement is a dialect method rather than just the catalog name.
        return "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = DATABASE()"
                + " AND LOWER(table_name) = '" + escapeLiteral(tableName).toLowerCase(Locale.ROOT) + "'";
    }

    @Override
    public String guardedConstraintDdl(String constraintName, String tableName, String ddlStatement) {
        // MySQL has no anonymous DO block and no ADD CONSTRAINT IF NOT EXISTS. It DOES have
        // DROP INDEX / ALTER TABLE ... DROP CONSTRAINT but not conditionally, so the H2 shape does
        // not port either. What does work, and is genuinely idempotent, is a prepared statement
        // built from a catalog lookup: a no-op SELECT when the constraint is already there.
        //
        // This runs in a Flyway *repeatable* migration that re-executes whenever its checksum
        // changes, so idempotence is not optional -- a bare ADD CONSTRAINT fails the whole boot the
        // second time (REG-38, learned on H2).
        String statement = ddlStatement.endsWith(";") ? ddlStatement : ddlStatement + ";";
        String constraint = escapeLiteral(constraintName);
        String table = escapeLiteral(tableName);
        return "SET @npdev_ddl := (SELECT IF(COUNT(*) > 0, 'SELECT 1',\n"
                + "    " + quoteSqlLiteral(statement) + ")\n"
                + "  FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS\n"
                + "  WHERE CONSTRAINT_NAME = '" + constraint + "'\n"
                + "    AND TABLE_NAME = '" + table + "'\n"
                + "    AND CONSTRAINT_SCHEMA = DATABASE());\n"
                + "PREPARE npdev_stmt FROM @npdev_ddl;\n"
                + "EXECUTE npdev_stmt;\n"
                + "DEALLOCATE PREPARE npdev_stmt;\n";
    }

    /** Wrap {@code value} as a MySQL string literal, escaping what the parser would otherwise eat. */
    private static String quoteSqlLiteral(String value) {
        return "'" + value.replace(chr(92), chr(92) + chr(92)).replace("'", "''") + "'";
    }

    private static String chr(int codePoint) {
        return String.valueOf((char) codePoint);
    }

    private static String schemaPredicate(String schema) {
        return schema == null || schema.isBlank() ? "DATABASE()" : "'" + escapeLiteral(schema) + "'";
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
        return "SqlDialect[mysql]";
    }

    /**
     * {@code INSERT ... ON DUPLICATE KEY UPDATE c = VALUES(c)}.
     *
     * <p>Note what MySQL does NOT let you say: there is no "on conflict with THIS key". The clause
     * fires for a clash on <i>any</i> unique index on the table, so a table with a second unique
     * column can have an upsert keyed on the id update a row the caller never named. Nothing in
     * NPDev's generated schema puts a second unique index on a table it also upserts by id today,
     * and the divergence is recorded here rather than discovered later.
     */
    static final class MySqlUpsertStrategy implements UpsertStrategy {
        @Override
        public String statementFor(String table, List<String> keyColumns, List<String> valueColumns) {
            if (keyColumns == null || keyColumns.isEmpty()) {
                throw new IllegalArgumentException("engine 'mysql': upsert needs at least one key column");
            }
            if (valueColumns == null || valueColumns.isEmpty()) {
                throw new IllegalArgumentException("engine 'mysql': upsert needs at least one value column");
            }
            Set<String> keys = new LinkedHashSet<>();
            for (String key : keyColumns) {
                keys.add(key.toLowerCase(Locale.ROOT));
            }
            List<String> updates = valueColumns.stream()
                    .filter(column -> !keys.contains(column.toLowerCase(Locale.ROOT)))
                    .map(column -> column + " = VALUES(" + column + ")")
                    .toList();
            String placeholders = String.join(", ", java.util.Collections.nCopies(valueColumns.size(), "?"));
            String head = "INSERT INTO " + table
                    + " (" + String.join(", ", valueColumns) + ")"
                    + " VALUES (" + placeholders + ")";
            if (updates.isEmpty()) {
                // Every column is a key column. MySQL has no DO NOTHING; the idiom is to assign a key
                // column to itself, which is a no-op the parser accepts. INSERT IGNORE would also
                // work and is deliberately not used -- it swallows unrelated errors too.
                String key = keyColumns.get(0);
                return head + " ON DUPLICATE KEY UPDATE " + key + " = " + key;
            }
            return head + " ON DUPLICATE KEY UPDATE " + String.join(", ", updates);
        }
    }

    /**
     * MySQL cannot return generated columns from an insert.
     *
     * <p>Both methods throw. That is the whole point: a {@code secondQuerySql()} returning
     * {@code SELECT LAST_INSERT_ID()} would be a code path no caller exercises (zero sites use
     * RETURNING), maintained on speculation, and trusted the first time someone did. When
     * conformance A2 fails, implement it then -- and the failure will say exactly which site needs it.
     */
    static final class MySqlReturningStrategy implements ReturningStrategy {
        @Override
        public boolean isInline() {
            return false;
        }

        @Override
        public String inlineClause(List<String> columns) {
            throw new UnsupportedOperationException(
                    "engine 'mysql': there is no RETURNING clause. Generated keys need a second query "
                    + "(SELECT LAST_INSERT_ID()), which changes the number of statements a caller runs "
                    + "-- check isInline() first. No production site uses RETURNING today, which is why "
                    + "the two-statement path is not built; conformance vector A2 is what will say it "
                    + "is needed.");
        }

        @Override
        public String secondQuerySql() {
            throw new UnsupportedOperationException(
                    "engine 'mysql': the two-statement generated-key path is deliberately NOT "
                    + "implemented -- zero production sites use RETURNING (measured on 5680551), and an "
                    + "unexercised path would be trusted the first time it ran. Implement it when "
                    + "conformance A2 fails, together with the call site that needs it.");
        }
    }
}
