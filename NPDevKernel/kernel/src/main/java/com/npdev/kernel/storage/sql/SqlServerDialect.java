package com.npdev.kernel.storage.sql;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Microsoft SQL Server 2016+.
 *
 * <p><b>The awkward engine, and the one the interface was shaped around.</b> Two of its differences
 * are not spellings, and both were designed for in S1 rather than discovered here:
 *
 * <ol>
 *   <li><b>Pagination REVERSES its parameters.</b> {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY} binds
 *       (offset, limit) where every other engine binds (limit, offset). A {@code String}-returning
 *       {@code paginate()} would have let all 23 call sites keep {@code setInt(n, limit);
 *       setInt(n + 1, offset)} -- correct on three engines and silently the WRONG PAGE here. Not a
 *       crash: the query succeeds, the user sees rows that are not theirs and misses rows that are.
 *       {@link PaginationClause} carries the order so the mistake is unrepresentable.</li>
 *   <li><b>{@code OFFSET..FETCH} is a syntax error without {@code ORDER BY}.</b> Conformance P3.
 *       This is the only engine of the four that rejects an unordered paginated query itself -- but
 *       the REFUSAL is uniform across every dialect
 *       ({@link SqlDialect#requireOrderedForPagination(String)}), because injecting an arbitrary
 *       order on the one engine that needs it would hide the difference and still return overlapping
 *       pages. Measured: every paginated statement in the repo already declares an ORDER BY with a
 *       tie-breaker, so this costs nothing today. There is deliberately no per-engine predicate for
 *       this fact -- STOR-13 deleted the one that existed, because an unconditional rule plus a flag
 *       that reads like it gates the rule is worse than the rule alone.</li>
 * </ol>
 *
 * <h2>The unresolved one: {@link #rowLimit(long)}</h2>
 *
 * <p>SQL Server has <b>no suffix row cap.</b> {@code SELECT TOP n} is a PREFIX, and the only suffix
 * form needs an {@code ORDER BY} that an existence probe ({@code SELECT 1 FROM t WHERE c = ? LIMIT 1})
 * does not have and should not need. So {@code rowLimit} <b>throws</b> here rather than returning
 * something plausible.
 *
 * <p>That is deliberate and it is what the S1 write-up predicted. The two probe call sites in
 * {@code PostgresPersistenceCapabilityAdapter} need a dialect-BUILT statement rather than a
 * dialect-built suffix before SQL Server can run them -- see {@link #existsProbe(String, String)},
 * which is the shape that fix takes. Throwing is the honest state: a plausible wrong answer in the
 * least visible layer is the defect this whole seam exists to prevent.
 */
public final class SqlServerDialect implements SqlDialect {

    public static final SqlServerDialect INSTANCE = new SqlServerDialect();

    static final String LIMIT_OFFSET_CLAUSE = "OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
    static final String LIMIT_ONLY_CLAUSE = "OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY";

    /**
     * SQL Server has no JSON type. JSON lives in {@code NVARCHAR(MAX)} with {@code ISJSON()} checks,
     * which means a JSON column is INDISTINGUISHABLE from an ordinary long text column when read back
     * from the catalog. Every site that asks {@link #isJsonColumnType(String)} is asking "should I
     * parse this?", and here the honest answer is "the catalog cannot tell you" -- see that method.
     */
    private static final Set<String> JSON_TYPE_NAMES = Set.of("json");

    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("information_schema", "sys", "db_owner", "db_accessadmin", "db_securityadmin",
                    "db_ddladmin", "db_backupoperator", "db_datareader", "db_datawriter",
                    "db_denydatareader", "db_denydatawriter", "guest");

    private static final Set<StorageCapability> CAPABILITIES = Set.of(
            StorageCapability.TRANSACTIONS,
            // SQL Server IS transactional for DDL -- unlike MySQL and H2. A failed migration rolls
            // back, so the schema engine's original safety model holds here.
            StorageCapability.DDL_IN_TRANSACTION,
            StorageCapability.SCHEMA_EVOLUTION,
            StorageCapability.FOREIGN_KEYS,
            StorageCapability.UNIQUE_CONSTRAINTS,
            StorageCapability.SERVER_SIDE_JOIN,
            StorageCapability.AGGREGATION_PIPELINE,
            StorageCapability.OPTIMISTIC_LOCKING,
            StorageCapability.SNAPSHOT_RESTORE,
            // R8c: no SKIP LOCKED keyword, but WITH (..., READPAST) is the documented, long-
            // standing equivalent -- it skips rows locked by another transaction rather than
            // blocking on them, which is the semantic this capability names.
            StorageCapability.SKIP_LOCKED_READS,
            // R9.3: sp_getapplock with @LockOwner='Session', needing no table.
            StorageCapability.SESSION_ADVISORY_LOCK,
            // R5.4: filtered index (CREATE UNIQUE INDEX ... WHERE ...) -- documented since SQL Server 2008.
            StorageCapability.PARTIAL_UNIQUE_INDEX);

    private final UpsertStrategy upsert = new SqlServerUpsertStrategy();
    private final ReturningStrategy returning = new SqlServerReturningStrategy();

    private SqlServerDialect() {
    }

    @Override
    public String name() {
        return "sqlserver";
    }

    // ------------------------------------------------------------------ identifiers

    @Override
    public String quoteIdentifier(String rawIdentifier) {
        Objects.requireNonNull(rawIdentifier, "rawIdentifier");
        return '[' + rawIdentifier.replace("]", "]]") + ']';
    }

    @Override
    public boolean isReservedIdentifier(String rawIdentifier) {
        return SqlReservedWords.isReserved(name(), rawIdentifier);
    }

    @Override
    public boolean foldsUnquotedIdentifiersToLowerCase() {
        // No folding: SQL Server preserves the case it was given and compares by COLLATION, which is
        // usually case-insensitive but is a per-database (and per-column) setting. As with MySQL,
        // there is no honest single answer, and NPDev does not need one -- it generates lower-case
        // identifiers throughout. False states the portable assumption: do not rely on folding.
        return false;
    }

    // ------------------------------------------------------------------ DDL types

    @Override
    public String autoIncrementColumn(SqlType type) {
        return switch (type) {
            case INT -> "INT IDENTITY(1,1)";
            case BIGINT -> "BIGINT IDENTITY(1,1)";
            default -> throw new IllegalArgumentException(
                    "engine 'sqlserver': " + type + " cannot be an auto-increment column; use INT or BIGINT");
        };
    }

    @Override
    public String jsonColumnType() {
        return "NVARCHAR(MAX)";
    }

    @Override
    public boolean isJsonColumnType(String sqlTypeName) {
        // NOT a lie of omission: `NVARCHAR(MAX)` is deliberately absent from the accepted set even
        // though it is what jsonColumnType() returns. The question this method answers is "is the
        // value in this column JSON I should parse?", and on SQL Server the catalog genuinely cannot
        // say -- every long text column looks identical. Accepting NVARCHAR(MAX) would make the
        // platform try to parse ordinary prose as JSON; rejecting it means a JSON column read back
        // through the catalog is treated as text. The second is wrong in a way that shows up as an
        // escaped string, the first is wrong in a way that shows up as a parse error on user data.
        //
        // Neither is good, which is the real finding: SQL Server needs the column's JSON-ness to come
        // from the MODEL rather than from the catalog. Recorded here; conformance J1 will fail on
        // this engine until it does, and that failure is the correct signal.
        return sqlTypeName != null && JSON_TYPE_NAMES.contains(sqlTypeName.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * SQL Server's index key is capped at 900 bytes, and NVARCHAR(MAX) cannot be indexed at all --
     * the same problem MySQL states more loudly. NVARCHAR, never VARCHAR: VARCHAR here is
     * non-Unicode and loses characters silently, which is conformance J2's own lesson.
     */
    @Override
    public String keyableTextColumnType() {
        return "NVARCHAR(191)";
    }

    @Override
    public String defaultableTextColumnType() {
        // NVARCHAR(MAX) accepts a DEFAULT (unlike the deprecated TEXT type, which SQL Server
        // itself refuses to default and which this dialect never emits).
        return portableColumnType("TEXT");
    }

    @Override
    public String selectForUpdate(String columns, String table, String whereClause) {
        // The hint sits between the table and the WHERE, which is why this method builds the
        // whole statement instead of returning a suffix. UPDLOCK takes the update lock a
        // check-then-act needs; ROWLOCK keeps it from escalating to the page or the table and
        // serialising callers that are not competing for the same row.
        return "SELECT " + columns + " FROM " + table + " WITH (UPDLOCK, ROWLOCK) WHERE " + whereClause;
    }

    @Override
    public String selectForUpdateSkipLocked(
            String columns, String table, String whereClause, String orderBy, int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("engine 'sqlserver': maxRows must be positive, got " + maxRows);
        }
        // READPAST is T-SQL's SKIP LOCKED: it skips rows locked by another transaction rather than
        // blocking on them. TOP (n) is a PREFIX of the select list, not a suffix of the statement
        // -- the same reason selectForUpdate above builds the whole statement, not a suffix.
        return "SELECT TOP (" + maxRows + ") " + columns + " FROM " + table
                + " WITH (UPDLOCK, ROWLOCK, READPAST) WHERE " + whereClause + " ORDER BY " + orderBy;
    }

    @Override
    public String renameColumn(String table, String from_, String to) {
        // Not an ALTER TABLE at all. sp_rename takes the OLD name qualified by its table and
        // the NEW name bare -- passing the new one qualified renames it to a literal string
        // containing a dot, which succeeds and leaves an unusable column.
        return "EXEC sp_rename '" + escapeLiteral(table + "." + from_) + "', '"
                + escapeLiteral(to) + "', 'COLUMN'";
    }

    @Override
    public boolean isUniqueViolation(java.sql.SQLException failure) {
        if (failure == null) {
            return false;
        }
        // Same shape as MySQL: 23000 covers every integrity violation, so the error number
        // is the discriminator. 2627 is a PRIMARY KEY/UNIQUE CONSTRAINT violation, 2601 a
        // unique INDEX violation -- SQL Server reports the two differently and both mean
        // 'that value is already there'. 547 (FOREIGN KEY/CHECK) deliberately does not.
        for (java.sql.SQLException current = failure; current != null;
                current = current.getNextException()) {
            int code = current.getErrorCode();
            if (code == 2627 || code == 2601) {
                return true;
            }
        }
        return false;
    }

    /**
     * SQL Server's timestamp comes back as a DRIVER type, not a JDK one.
     *
     * <p>A {@code datetime} field realizes as {@code DATETIMEOFFSET(6)} here, and mssql-jdbc returns
     * {@code microsoft.sql.DateTimeOffset} for it -- its own class, which is neither a
     * {@code java.time} type nor a {@code java.sql.Timestamp}. Jackson has no idea what it is, so it
     * serializes it as a nested OBJECT and the generated DTO refuses it (STOR-10):
     *
     * <pre>
     *   Unexpected token (START_OBJECT), expected one of [VALUE_STRING, VALUE_NUMBER_INT,
     *   VALUE_NUMBER_FLOAT] for java.time.OffsetDateTime value
     *   (through reference chain: ProbeRecord["recordedAt"])
     * </pre>
     *
     * <p>Reached by REFLECTION on purpose: the kernel must not compile against a JDBC driver, and
     * adding mssql-jdbc to its classpath to convert one type would put a vendor driver in every
     * app's kernel regardless of engine. The class is matched by name and the conversion is the
     * driver's own {@code getOffsetDateTime()}, so nothing is reimplemented.
     *
     * <p>The {@code LocalDateTime} arm stays for the {@code DATETIME2} case -- an inverse that only
     * handles the shapes observed today is exactly how the MySQL half of this bug stayed invisible.
     */
    @Override
    public Object readValue(Object value) {
        if (value == null) {
            return null;
        }
        if ("microsoft.sql.DateTimeOffset".equals(value.getClass().getName())) {
            try {
                return value.getClass().getMethod("getOffsetDateTime").invoke(value);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // Never swallow into a wrong value: if the driver's own accessor is gone, the caller
                // gets the driver object it would have got before, and the DTO binding fails loudly
                // rather than silently producing a timestamp nobody can trace.
                return value;
            }
        }
        return value instanceof java.time.LocalDateTime local
                ? local.atOffset(java.time.ZoneOffset.UTC)
                : value;
    }

    @Override
    public Object bindableValue(Object value) {
        // UNIQUEIDENTIFIER is a real type here, but the mssql-jdbc driver does not bind a
        // java.util.UUID to it -- it wants the string form, and Java-serializes the object
        // otherwise, exactly as MySQL does (STOR-10). So the answer is the same for a
        // different reason, which is why this is per-dialect rather than one shared branch.
        return value instanceof java.util.UUID uuid ? uuid.toString() : value;
    }

    @Override
    public String timestampColumnType() {
        // datetime2, not `timestamp` -- SQL Server's TIMESTAMP is a row-version binary counter with
        // no relationship to time at all, which is a genuinely dangerous false friend.
        return "DATETIME2(6)";
    }

    @Override
    public String portableColumnType(String declaredSqlType) {
        if (declaredSqlType == null) {
            return null;
        }
        String normalized = declaredSqlType.trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        if ("JSONB".equals(upper) || "JSON".equals(upper)) {
            return "NVARCHAR(MAX)";
        }
        if ("TIMESTAMP WITH TIME ZONE".equals(upper) || "TIMESTAMPTZ".equals(upper)) {
            return "DATETIMEOFFSET(6)";
        }
        if ("TIMESTAMP".equals(upper)) {
            return "DATETIME2(6)";
        }
        if ("UUID".equals(upper)) {
            return "UNIQUEIDENTIFIER";
        }
        if ("BOOLEAN".equals(upper) || "BOOL".equals(upper)) {
            return "BIT";
        }
        if ("TEXT".equals(upper)) {
            return "NVARCHAR(MAX)";
        }
        if ("BYTEA".equals(upper) || "BLOB".equals(upper)) {
            return "VARBINARY(MAX)";
        }
        if ("SERIAL".equals(upper)) {
            return "INT IDENTITY(1,1)";
        }
        if ("BIGSERIAL".equals(upper)) {
            return "BIGINT IDENTITY(1,1)";
        }
        if (upper.startsWith("VARCHAR")) {
            // NVARCHAR, not VARCHAR: SQL Server's VARCHAR is single-byte in a non-UTF8 collation, so
            // a name with an accent in it round-trips as mojibake. Same class of silent data loss as
            // MySQL's utf8-vs-utf8mb4 (conformance J2).
            return "N" + normalized;
        }
        return normalized;
    }

    // ------------------------------------------------------------------ DML

    @Override
    public PaginationClause limitOffset() {
        // *** REVERSED. *** See the class javadoc.
        return new PaginationClause(LIMIT_OFFSET_CLAUSE,
                List.of(PaginationClause.Parameter.OFFSET, PaginationClause.Parameter.LIMIT));
    }

    @Override
    public PaginationClause limitOnly() {
        // OFFSET 0 is not noise: FETCH NEXT is only legal as part of an OFFSET clause here.
        return new PaginationClause(LIMIT_ONLY_CLAUSE, List.of(PaginationClause.Parameter.LIMIT));
    }

    @Override
    public String rowLimit(long rows) {
        if (rows <= 0) {
            throw new IllegalArgumentException("engine 'sqlserver': rowLimit must be positive, got " + rows);
        }
        throw new UnsupportedOperationException(
                "engine 'sqlserver': there is no SUFFIX row cap. SELECT TOP " + rows + " is a PREFIX, and "
                + "the only suffix form (OFFSET 0 ROWS FETCH NEXT " + rows + " ROWS ONLY) requires an "
                + "ORDER BY that an existence probe does not have. Use existsProbe(...) to have the "
                + "dialect build the whole statement, or selectTop(...) for a capped SELECT. Returning "
                + "a plausible suffix here would produce a syntax error at best and, if it happened to "
                + "parse, the wrong rows.");
    }

    /**
     * <b>The resolution of the S1/S5 open gap</b> (storage/FULL_SUPPORT_PLAN.md W1.3): a capped
     * statement, built by rewriting {@code SELECT} into {@code SELECT TOP n}.
     *
     * <h2>Why this and not "make callers consult capabilities()"</h2>
     *
     * <p>The plan offered two options -- implement it as a prefix rewrite, or declare the capability
     * absent and make every caller ask first -- and said to <b>find out which call sites need it
     * before choosing.</b> Measured: four, and all four want the same thing.
     *
     * <pre>
     *   PostgresPersistenceCapabilityAdapter x2   SELECT 1 FROM t WHERE c = ?        exists probe
     *   JdbcEventStore x2                          SELECT ... ORDER BY ... , cap 1    first by order
     * </pre>
     *
     * <p>None of them wants a SUFFIX; they want "at most n rows". Suffix-vs-prefix is the engine's
     * business, which is exactly what a dialect is for. Pushing the question out to four call sites
     * would put an {@code if (dialect.supports(...))} branch in each -- the engine switch moved
     * rather than removed, which the {@code SqlDialect} javadoc names as the thing this seam must
     * never become.
     *
     * <p>{@link #rowLimit(long)} still THROWS, and that stays right: there genuinely is no suffix
     * here, and a method that promised one would be lying about the shape. The suffix is the
     * primitive; this is the question callers actually ask.
     *
     * <h2>What it refuses</h2>
     *
     * <p>Only a statement whose first keyword is {@code SELECT} can take a {@code TOP}. A CTE
     * ({@code WITH ...}) needs the {@code TOP} inside its final select, and no amount of string
     * surgery here can know where that is -- so it refuses rather than producing something that
     * parses and caps the wrong thing. {@code DISTINCT} is handled because T-SQL's grammar is
     * {@code SELECT [ALL|DISTINCT] [TOP n]}, in that order; emitting {@code SELECT TOP n DISTINCT}
     * is a syntax error.
     */
    @Override
    public String rowLimited(String sql, long rows) {
        if (rows <= 0) {
            throw new IllegalArgumentException(
                    "engine 'sqlserver': rowLimited needs a positive count, got " + rows
                    + " -- a cap of 0 reads as 'no rows matched' at every call site that uses this.");
        }
        Objects.requireNonNull(sql, "sql");
        String leading = sql.substring(0, sql.length() - sql.stripLeading().length());
        String body = sql.stripLeading();

        java.util.regex.Matcher select = java.util.regex.Pattern
                .compile("^select\\s+(distinct\\s+|all\\s+)?", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(body);
        if (!select.find()) {
            throw new UnsupportedOperationException(
                    "engine 'sqlserver': a row cap is a PREFIX here (SELECT TOP n), so the statement "
                    + "must begin with SELECT. This one does not, so there is nowhere to put the cap "
                    + "-- a CTE needs the TOP inside its own final select, which this cannot locate. "
                    + "Restructure the statement, or build it with selectTop(...). Statement: "
                    + sql.strip());
        }
        return leading + body.substring(0, select.end()) + "TOP " + rows + " "
                + body.substring(select.end());
    }

    /**
     * A complete existence probe: {@code SELECT TOP 1 1 FROM t WHERE <predicate>}.
     *
     * <p>Kept as a named method even though {@link #rowLimited(String, long)} now handles the
     * general case, because an existence probe is the commonest shape and reads better built than
     * rewritten. Nothing depends on it today.
     */
    public String existsProbe(String table, String wherePredicate) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(wherePredicate, "wherePredicate");
        return "SELECT TOP 1 1 FROM " + table + " WHERE " + wherePredicate;
    }

    /** {@code SELECT TOP n <columns> FROM ...} -- the prefix form, for a capped read with no offset. */
    public String selectTop(long rows, String selectListOnwards) {
        if (rows <= 0) {
            throw new IllegalArgumentException("engine 'sqlserver': selectTop needs a positive count");
        }
        return "SELECT TOP " + rows + " " + selectListOnwards;
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
            case TEXT, JSON -> "NVARCHAR(MAX)";
            case INT -> "INT";
            case BIGINT -> "BIGINT";
            case UUID -> "UNIQUEIDENTIFIER";
            case BOOLEAN -> "BIT";
            case NUMERIC -> "DECIMAL(38,10)";
            case TIMESTAMP -> "DATETIME2(6)";
            case BLOB -> "VARBINARY(MAX)";
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
                + " WHERE table_schema = COALESCE(?, SCHEMA_NAME())"
                + " AND table_type = 'BASE TABLE' ORDER BY table_name";
    }

    @Override
    public String listColumnsSql() {
        return "SELECT column_name, data_type, is_nullable, column_default"
                + " FROM information_schema.columns"
                + " WHERE table_schema = COALESCE(?, SCHEMA_NAME()) AND table_name = ?"
                + " ORDER BY ordinal_position";
    }

    @Override
    public String listIndexesSql() {
        // sys.indexes, aliased to the same three column names every other dialect returns.
        return "SELECT i.name AS index_name,"
                + " c.name AS column_name,"
                + " i.is_unique AS is_unique"
                + " FROM sys.indexes i"
                + " JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id"
                + " JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id"
                + " JOIN sys.tables t ON t.object_id = i.object_id"
                + " JOIN sys.schemas s ON s.schema_id = t.schema_id"
                + " WHERE s.name = COALESCE(?, SCHEMA_NAME()) AND t.name = ?"
                + " ORDER BY i.name, ic.key_ordinal";
    }

    @Override
    public String constraintExistsSql() {
        return "SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE UPPER(CONSTRAINT_NAME) = UPPER(?) AND UPPER(TABLE_NAME) = UPPER(?)";
    }

    @Override
    public String tableExistsInCurrentSchemaSql(String tableName) {
        return "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = SCHEMA_NAME()"
                + " AND LOWER(table_name) = '" + escapeLiteral(tableName).toLowerCase(Locale.ROOT) + "'";
    }

    /*
     * ------------------------------------------------------------------------------------------
     * STOR-5. T-SQL has NONE of the three: `CREATE TABLE IF NOT EXISTS` is "Incorrect syntax near
     * '<table>'", measured against a real SQL Server 2022 in CI.
     *
     * It does have cheap catalog functions, so each guard is an ordinary IF wrapping the statement --
     * no prepared statements, no dynamic SQL. CREATE TABLE, CREATE INDEX and ALTER TABLE are all
     * legal inside an IF block (unlike CREATE VIEW/PROCEDURE, which must start their own batch),
     * which is why this stays readable.
     * ------------------------------------------------------------------------------------------
     */

    @Override
    public String guardedCreateTable(String tableName, String createStatement) {
        // OBJECT_ID(..., 'U') is null when no USER TABLE of that name exists. 'U' rather than a bare
        // OBJECT_ID: without it a view or procedure sharing the name would suppress the CREATE and
        // the table would silently never appear.
        if (SqlDdlGuards.alreadyGuarded(createStatement, "IF OBJECT_ID(")) {
            return createStatement;
        }
        return "IF OBJECT_ID(N'" + escapeLiteral(tableName) + "', N'U') IS NULL\nBEGIN\n"
                + indent(SqlDdlGuards.stripIfNotExists(createStatement))
                + "\nEND;\n";
    }

    @Override
    public String guardedCreateIndex(String indexName, String tableName, String createStatement) {
        if (SqlDdlGuards.alreadyGuarded(createStatement,
                "IF NOT EXISTS (SELECT 1 FROM sys.indexes")) {
            return createStatement;
        }
        return "IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'"
                + escapeLiteral(indexName) + "'\n"
                + "               AND object_id = OBJECT_ID(N'" + escapeLiteral(tableName) + "'))\nBEGIN\n"
                + indent(SqlDdlGuards.stripIfNotExists(createStatement))
                + "\nEND;\n";
    }

    @Override
    public String guardedDropIndexIfExists(String indexName, String tableName) {
        // Native since SQL Server 2016 -- this class already targets 2016+ (see trimmedText's own
        // javadoc for the sibling fact about single-argument TRIM), so no sys.indexes IF-wrapper is
        // needed here the way guardedCreateIndex above needs one (SQL Server has no
        // "CREATE INDEX IF NOT EXISTS", but it does have "DROP INDEX IF EXISTS").
        return "DROP INDEX IF EXISTS " + indexName + " ON " + tableName + ";";
    }

    @Override
    public String guardedAddColumn(String tableName, String columnName, String alterStatement) {
        // COL_LENGTH returns null for a column that does not exist -- the cheapest existence test
        // here, and it does not need the schema spelled out.
        //
        // stripAddColumnKeyword is the SECOND incompatibility in this statement: T-SQL is
        // `ALTER TABLE t ADD c TYPE`, with no COLUMN keyword. It sits underneath the IF NOT EXISTS
        // one and would have surfaced as its own CI round after this one was fixed.
        if (SqlDdlGuards.alreadyGuarded(alterStatement, "IF COL_LENGTH(")) {
            return alterStatement;
        }
        return "IF COL_LENGTH(N'" + escapeLiteral(tableName) + "', N'"
                + escapeLiteral(columnName) + "') IS NULL\nBEGIN\n"
                + indent(SqlDdlGuards.stripAddColumnKeyword(SqlDdlGuards.stripIfNotExists(alterStatement)))
                + "\nEND;\n";
    }

    /**
     * R9.3. T-SQL has no {@code CREATE SCHEMA IF NOT EXISTS}, and {@code CREATE SCHEMA} must be the
     * FIRST statement in its batch -- so it cannot simply be wrapped in {@code IF ... BEGIN ... END}
     * the way {@link #guardedCreateTable} wraps a table. {@code EXEC} puts it in a batch of its own,
     * which is the documented way to issue it conditionally.
     */
    @Override
    public String guardedCreateSchema(String schemaName) {
        return "IF SCHEMA_ID(N'" + escapeLiteral(schemaName) + "') IS NULL\nBEGIN\n"
                + indent("EXEC(N'CREATE SCHEMA " + escapeLiteral(schemaName) + "');")
                + "\nEND;\n";
    }

    /** R9.3. SQL Server keys application locks by NAME, not by number as Postgres does. */
    @Override
    public Object advisoryLockKey(String lockName) {
        return lockName;
    }

    /**
     * R9.3. {@code sp_getapplock} with {@code @LockOwner='Session'}, which is what ties the lock to
     * the connection rather than to a transaction -- the migration mutex is held across DDL that
     * commits implicitly on two of the four engines, so a transaction-scoped lock would be dropped
     * mid-migration.
     *
     * <p>Normalised to 1/0 from a PROCEDURE RETURN CODE, which is neither a boolean nor an error:
     * 0 and 1 both mean acquired (granted immediately / granted after waiting), and the failure
     * codes are NEGATIVE (-1 timeout, -3 deadlock victim). A caller testing {@code == 1} would read
     * the ordinary immediate grant, 0, as a refusal.
     */
    @Override
    public String tryAdvisoryLockSql() {
        return "DECLARE @npdevLockResult INT; "
                + "EXEC @npdevLockResult = sp_getapplock @Resource = ?, @LockMode = N'Exclusive', "
                + "@LockOwner = N'Session', @LockTimeout = 0; "
                + "SELECT CASE WHEN @npdevLockResult >= 0 THEN 1 ELSE 0 END;";
    }

    @Override
    public String releaseAdvisoryLockSql() {
        return "DECLARE @npdevUnlockResult INT; "
                + "EXEC @npdevUnlockResult = sp_releaseapplock @Resource = ?, @LockOwner = N'Session'; "
                + "SELECT CASE WHEN @npdevUnlockResult >= 0 THEN 1 ELSE 0 END;";
    }

    private static String indent(String statement) {
        String body = statement.strip();
        if (!body.endsWith(";")) {
            body = body + ";";
        }
        return body.lines().map(line -> "  " + line).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    @Override
    public String guardedConstraintDdl(String constraintName, String tableName, String ddlStatement) {
        // T-SQL has real IF/BEGIN/END at statement level -- no anonymous block needed, and no
        // prepared-statement dance like MySQL's. The DDL still has to be idempotent because it lands
        // in a Flyway repeatable migration (REG-38).
        String statement = ddlStatement.endsWith(";") ? ddlStatement : ddlStatement + ";";
        return "IF NOT EXISTS (\n"
                + "  SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS\n"
                + "  WHERE CONSTRAINT_NAME = '" + escapeLiteral(constraintName) + "'\n"
                + "    AND TABLE_NAME = '" + escapeLiteral(tableName) + "'\n"
                + "    AND TABLE_SCHEMA = SCHEMA_NAME()\n"
                + ")\n"
                + "BEGIN\n"
                + "  " + statement + "\n"
                + "END;\n";
    }

    private static String schemaPredicate(String schema) {
        return schema == null || schema.isBlank() ? "SCHEMA_NAME()" : "'" + escapeLiteral(schema) + "'";
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
        return "SqlDialect[sqlserver]";
    }

    /**
     * {@code MERGE} -- a different STATEMENT, not a different keyword, which is why
     * {@link UpsertStrategy} returns a statement rather than a suffix.
     *
     * <p><b>{@code HOLDLOCK} is not optional.</b> SQL Server's MERGE has documented race conditions
     * under the default isolation level: two concurrent merges on the same key can both find no match
     * and both insert, producing a duplicate-key error or a duplicate row depending on the indexes.
     * The serializable hint on the target closes it. Conformance U2 exists for exactly this, and it is
     * the kind of defect that only appears in production under load -- never in a unit test.
     */
    static final class SqlServerUpsertStrategy implements UpsertStrategy {

        private static String quoted(String rawIdentifier) {
            return SqlServerDialect.INSTANCE.identifier(rawIdentifier);
        }

        private static List<String> quotedAll(List<String> rawIdentifiers) {
            return SqlServerDialect.INSTANCE.identifiers(rawIdentifiers);
        }
        @Override
        public String statementFor(String rawTable, List<String> keyColumns, List<String> valueColumns) {
            // STOR-6: quote HERE, as the text is composed. The caller keeps raw names for
            // its map lookups, and bindColumns() must echo those back unquoted.
            String table = SqlServerDialect.INSTANCE.identifier(rawTable);
            if (keyColumns == null || keyColumns.isEmpty()) {
                throw new IllegalArgumentException("engine 'sqlserver': upsert needs at least one key column");
            }
            if (valueColumns == null || valueColumns.isEmpty()) {
                throw new IllegalArgumentException("engine 'sqlserver': upsert needs at least one value column");
            }
            Set<String> keys = new LinkedHashSet<>();
            for (String key : keyColumns) {
                keys.add(key.toLowerCase(Locale.ROOT));
            }
            String placeholders = String.join(", ", java.util.Collections.nCopies(valueColumns.size(), "?"));
            String sourceColumns = String.join(", ", quotedAll(valueColumns));
            String onClause = keyColumns.stream()
                    .map(key -> "target." + quoted(key) + " = source." + quoted(key))
                    .reduce((a, b) -> a + " AND " + b)
                    .orElseThrow();
            List<String> updates = valueColumns.stream()
                    .filter(column -> !keys.contains(column.toLowerCase(Locale.ROOT)))
                    .map(column -> "target." + quoted(column) + " = source." + quoted(column))
                    .toList();
            String insertColumns = String.join(", ", quotedAll(valueColumns));
            String insertValues = valueColumns.stream()
                    .map(column -> "source." + quoted(column))
                    .reduce((a, b) -> a + ", " + b)
                    .orElseThrow();

            StringBuilder sql = new StringBuilder()
                    .append("MERGE ").append(table).append(" WITH (HOLDLOCK) AS target")
                    .append(" USING (VALUES (").append(placeholders).append(")) AS source (")
                    .append(sourceColumns).append(")")
                    .append(" ON ").append(onClause);
            if (!updates.isEmpty()) {
                sql.append(" WHEN MATCHED THEN UPDATE SET ").append(String.join(", ", updates));
            }
            sql.append(" WHEN NOT MATCHED THEN INSERT (").append(insertColumns)
                    .append(") VALUES (").append(insertValues).append(")")
                    // The terminating semicolon is MANDATORY on MERGE. Omitting it is a syntax error,
                    // and it is the single most common way a hand-written MERGE fails to run at all.
                    .append(";");
            return sql.toString();
        }
    }

    /** SQL Server returns generated columns via {@code OUTPUT}, which is inline. */
    static final class SqlServerReturningStrategy implements ReturningStrategy {
        @Override
        public boolean isInline() {
            return true;
        }

        @Override
        public String inlineClause(List<String> columns) {
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("engine 'sqlserver': OUTPUT needs at least one column");
            }
            // OUTPUT sits BEFORE the VALUES clause, not after it like RETURNING -- a caller that
            // appends this the way it appends RETURNING gets a syntax error rather than a wrong
            // answer, which is the better of the two failures but still worth knowing.
            return "OUTPUT " + columns.stream().map(column -> "INSERTED." + column)
                    .reduce((a, b) -> a + ", " + b).orElseThrow();
        }

        @Override
        public String secondQuerySql() {
            throw new UnsupportedOperationException(
                    "engine 'sqlserver': generated keys come back inline via OUTPUT. Check isInline() "
                    + "and use inlineClause(...) -- note OUTPUT goes BEFORE VALUES, unlike RETURNING.");
        }
    }
}
