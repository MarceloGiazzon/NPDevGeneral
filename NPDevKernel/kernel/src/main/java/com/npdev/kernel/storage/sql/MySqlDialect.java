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
    public boolean isReservedIdentifier(String rawIdentifier) {
        return SqlReservedWords.isReserved(name(), rawIdentifier);
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

    /**
     * THE ENGINE THAT FORCED THIS METHOD. MySQL refuses to index a TEXT/BLOB column without a
     * prefix length (error 1170), so an internal table whose primary key is declared TEXT cannot
     * be CREATED at all -- the failure lands in Flyway on first boot, not at generation time.
     *
     * <p><b>191, not 255, and the difference is measured.</b> InnoDB caps an index key at 3072
     * bytes. On utf8mb4 a character costs 4, so VARCHAR(255) is 1020 bytes and a FOUR-column index
     * over such columns is 4080 -- error 1071, "Specified key was too long", in CI run 31284112143,
     * after the guarded-DDL fix finally let the script get that far. 191*4 = 764, so four columns
     * are 3056 and fit. 191 is the conventional utf8mb4 answer for exactly this reason.
     */
    @Override
    public String keyableTextColumnType() {
        return "VARCHAR(191)";
    }

    @Override
    public String defaultableTextColumnType() {
        // The one engine that refuses: MySQL error 1101, "BLOB, TEXT, GEOMETRY or JSON column
        // can't have a default value". A bounded VARCHAR can carry one, so this is the same
        // width the keyable answer uses -- one width for every narrowed text column keeps the
        // same column the same type wherever it appears.
        return keyableTextColumnType();
    }

    @Override
    public String selectForUpdate(String columns, String table, String whereClause) {
        return "SELECT " + columns + " FROM " + table + " WHERE " + whereClause + " FOR UPDATE";
    }

    @Override
    public String renameColumn(String table, String from_, String to) {
        // MySQL 8.0 added RENAME COLUMN; it shares Postgres's spelling, NOT H2's. It used to
        // get H2's by falling through a `"Postgres".equals(engine) ? ... : ...` (STOR-10).
        return "ALTER TABLE " + identifier(table) + " RENAME COLUMN " + identifier(from_)
                + " TO " + identifier(to);
    }

    @Override
    public boolean isUniqueViolation(java.sql.SQLException failure) {
        if (failure == null) {
            return false;
        }
        // SQLSTATE 23000 is MySQL's ENTIRE integrity class -- duplicate key, foreign key,
        // NOT NULL, CHECK. Only the vendor error number distinguishes them, so this asks for
        // 1062 (ER_DUP_ENTRY) and 1586 (ER_DUP_ENTRY_WITH_KEY_NAME) and nothing else. Testing
        // 23000 alone would call a foreign-key failure a duplicate and swallow it.
        for (java.sql.SQLException current = failure; current != null;
                current = current.getNextException()) {
            int code = current.getErrorCode();
            if (code == 1062 || code == 1586) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object readValue(Object value) {
        // DATETIME(6) carries no offset, so mysql-connector hands back a LocalDateTime and the
        // platform's `datetime` type (OffsetDateTime) cannot bind it. serverTimezone=UTC in the
        // generated JDBC URL means the stored instant IS UTC, so this is the exact inverse of the
        // write rather than an assumed zone (STOR-10).
        return value instanceof java.time.LocalDateTime local
                ? local.atOffset(java.time.ZoneOffset.UTC)
                : value;
    }

    @Override
    public Object bindableValue(Object value) {
        // No native UUID column type here -- portableColumnType("UUID") answers CHAR(36) -- so a
        // java.util.UUID must arrive as text. Handing the OBJECT to setObject makes the driver
        // Java-serialize it into the column (STOR-10).
        return value instanceof java.util.UUID uuid ? uuid.toString() : value;
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

    /*
     * ------------------------------------------------------------------------------------------
     * STOR-5. MySQL has CREATE TABLE IF NOT EXISTS and NOTHING else in this family: an index or a
     * column guarded that way is error 1064, a plain syntax error. Measured on a real MySQL 8.4 in
     * CI, which is where an app first got past the driver and stopped here instead.
     *
     * The working shape is the one guardedConstraintDdl already uses on this engine: look the object
     * up in INFORMATION_SCHEMA, build either the real statement or a no-op SELECT as a string, and
     * PREPARE/EXECUTE it. Genuinely idempotent, and it needs no DO block MySQL does not have.
     * ------------------------------------------------------------------------------------------
     */

    @Override
    public String guardedCreateTable(String tableName, String createStatement) {
        // Native, and identical to Postgres here -- the only one of the three MySQL supports.
        return SqlDdlGuards.insertAfter(createStatement, "CREATE TABLE", "IF NOT EXISTS");
    }

    @Override
    public String guardedCreateIndex(String indexName, String tableName, String createStatement) {
        return preparedGuard(
                "INFORMATION_SCHEMA.STATISTICS",
                "INDEX_NAME = '" + escapeLiteral(indexName) + "'\n"
                + "    AND TABLE_NAME = '" + escapeLiteral(tableName) + "'\n"
                + "    AND TABLE_SCHEMA = DATABASE()",
                createStatement);
    }

    @Override
    public String guardedAddColumn(String tableName, String columnName, String alterStatement) {
        return preparedGuard(
                "INFORMATION_SCHEMA.COLUMNS",
                "COLUMN_NAME = '" + escapeLiteral(columnName) + "'\n"
                + "    AND TABLE_NAME = '" + escapeLiteral(tableName) + "'\n"
                + "    AND TABLE_SCHEMA = DATABASE()",
                // MySQL accepts ADD COLUMN, but the statement is about to be wrapped in a string
                // literal, so any IF NOT EXISTS the caller left in would be a syntax error inside it.
                SqlDdlGuards.stripIfNotExists(alterStatement));
    }

    /**
     * The catalog-lookup + PREPARE/EXECUTE shape, shared by the two idioms MySQL cannot guard.
     *
     * <p>Identical in structure to {@link #guardedConstraintDdl}, deliberately: three sites doing the
     * same thing three slightly different ways is how one of them ends up missing an escape.
     */
    private static String preparedGuard(String catalogTable, String predicate, String statement) {
        if (SqlDdlGuards.alreadyGuarded(statement, "SET @npdev_ddl")) {
            return statement;
        }
        String ddl = statement.strip();
        if (!ddl.endsWith(";")) {
            ddl = ddl + ";";
        }
        return "SET @npdev_ddl := (SELECT IF(COUNT(*) > 0, 'SELECT 1',\n"
                + "    " + quoteSqlLiteral(ddl) + ")\n"
                + "  FROM " + catalogTable + "\n"
                + "  WHERE " + predicate + ");\n"
                + "PREPARE npdev_stmt FROM @npdev_ddl;\n"
                + "EXECUTE npdev_stmt;\n"
                + "DEALLOCATE PREPARE npdev_stmt;\n";
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
     * column can have an upsert keyed on the id update a row the caller never named.
     *
     * <p><b>This javadoc used to end "nothing in NPDev's generated schema puts a second unique index
     * on a table it also upserts by id today, and the divergence is recorded here rather than
     * discovered later." That was wrong, and it was discovered later (STOR-11).</b> Any model field
     * declaring {@code unique: true} produces exactly that shape. Measured against a real MySQL 8.4:
     * the {@code p4-constraints} probe declares {@code email} unique, and POSTing a second account
     * with the same email returned <b>200</b> -- MySQL took the clash on {@code ux_accounts_email} as
     * a signal to UPDATE the existing row. On Postgres and H2 the same request is rejected, because
     * {@code ON CONFLICT (id)} names the key it reacts to.
     *
     * <p>So on MySQL a create that violates a unique constraint silently overwrites the row that
     * already held that value, instead of failing. Filed rather than patched here: the fix changes
     * the semantics of every write on the engine, and doing it inside a fix for something else is
     * how a subtle write-path change ships unreviewed.
     */
    static final class MySqlUpsertStrategy implements UpsertStrategy {

        private static String quoted(String rawIdentifier) {
            return MySqlDialect.INSTANCE.identifier(rawIdentifier);
        }

        private static List<String> quotedAll(List<String> rawIdentifiers) {
            return MySqlDialect.INSTANCE.identifiers(rawIdentifiers);
        }

        /**
         * UPDATE by key, then INSERT only if that matched nothing -- because MySQL's native upsert
         * cannot be told WHICH key to react to (STOR-11).
         *
         * <p>Chosen over the two alternatives, both considered:
         *
         * <ul>
         *   <li><b>Refusing {@code unique: true} on MySQL</b> -- rejected. It is an ordinary
         *       declaration, and an engine that cannot honour it is second-class. A capability
         *       regression stated honestly is still a capability regression, and engine parity is
         *       the requirement this whole workstream exists to meet.</li>
         *   <li><b>INSERT first, then UPDATE-by-id on error 1062</b> -- deferred, not rejected. It
         *       avoids the extra round trip on the create path, but it puts vendor error-code
         *       branching in the write path to save a cost nobody has measured. Revisit with
         *       numbers.</li>
         * </ul>
         *
         * <p>The four cases, all verified against a real MySQL 8.4:
         *
         * <pre>
         *   create, no clash          UPDATE 0 rows -> INSERT succeeds
         *   create, unique clash      UPDATE 0 rows -> INSERT raises 1062 -> 409, row untouched
         *   save to an existing id    UPDATE 1 row  -> done, INSERT never runs
         *   save, new value clashes   UPDATE raises 1062 -> 409, holder untouched
         * </pre>
         *
         * <p>A concurrent create loses the race on its INSERT and gets a real unique violation.
         * Correct, not a defect: the row it was told to create now exists.
         */
        @Override
        public UpsertPlan planFor(String table, List<String> keyColumns, List<String> valueColumns) {
            List<String> keys = normalizedKeys(keyColumns);
            List<String> assignable = valueColumns.stream()
                    .filter(column -> !keys.contains(column.toLowerCase(Locale.ROOT)))
                    .toList();
            if (assignable.isEmpty()) {
                // Every column is a key column, so there is nothing an UPDATE could set. A plain
                // INSERT carries the whole meaning, and a clash is then correctly a unique violation.
                return UpsertPlan.single(insertStatement(table, valueColumns), valueColumns);
            }
            String setClause = String.join(", ", assignable.stream().map(c -> quoted(c) + " = ?").toList());
            String whereClause = String.join(" AND ", keyColumns.stream().map(c -> quoted(c) + " = ?").toList());
            // Bind order is the whole reason UpsertPlan carries it: UPDATE binds the assignable
            // columns and THEN the keys, while INSERT binds every column in declaration order. A
            // caller assuming one order for both would write the key into a value column.
            List<String> updateBindings = new java.util.ArrayList<>(assignable);
            updateBindings.addAll(keyColumns);
            return UpsertPlan.updateThenInsert(
                    new UpsertPlan.Step("UPDATE " + quoted(table) + " SET " + setClause + " WHERE " + whereClause,
                            updateBindings),
                    new UpsertPlan.Step(insertStatement(table, valueColumns), valueColumns));
        }

        private static String insertStatement(String table, List<String> valueColumns) {
            String placeholders = String.join(", ", java.util.Collections.nCopies(valueColumns.size(), "?"));
            return "INSERT INTO " + quoted(table) + " (" + String.join(", ", quotedAll(valueColumns)) + ")"
                    + " VALUES (" + placeholders + ")";
        }

        private static List<String> normalizedKeys(List<String> keyColumns) {
            if (keyColumns == null || keyColumns.isEmpty()) {
                throw new IllegalArgumentException("engine 'mysql': upsert needs at least one key column");
            }
            return keyColumns.stream().map(key -> key.toLowerCase(Locale.ROOT)).toList();
        }

        @Override
        public String statementFor(String rawTable, List<String> keyColumns, List<String> valueColumns) {
            // STOR-6: quote HERE, as the text is composed. The caller keeps raw names for
            // its map lookups, and bindColumns() must echo those back unquoted.
            String table = MySqlDialect.INSTANCE.identifier(rawTable);
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
                    .map(column -> quoted(column) + " = VALUES(" + quoted(column) + ")")
                    .toList();
            String placeholders = String.join(", ", java.util.Collections.nCopies(valueColumns.size(), "?"));
            String head = "INSERT INTO " + table
                    + " (" + String.join(", ", quotedAll(valueColumns)) + ")"
                    + " VALUES (" + placeholders + ")";
            if (updates.isEmpty()) {
                // Every column is a key column. MySQL has no DO NOTHING; the idiom is to assign a key
                // column to itself, which is a no-op the parser accepts. INSERT IGNORE would also
                // work and is deliberately not used -- it swallows unrelated errors too.
                String key = keyColumns.get(0);
                return head + " ON DUPLICATE KEY UPDATE " + quoted(key) + " = " + quoted(key);
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
