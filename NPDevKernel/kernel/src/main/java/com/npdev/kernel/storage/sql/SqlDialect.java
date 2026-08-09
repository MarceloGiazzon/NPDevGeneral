package com.npdev.kernel.storage.sql;

import java.util.Set;

/**
 * The one place a dialect-bound question gets asked.
 *
 * <p><b>What this is not.</b> It is not a query DSL and must never become one. The eight
 * {@code *-postgres} adapters are ~3,900 lines of already-portable SQL; a measured scan
 * ({@code storage/helpers/dialect-site-inventory.py}) found only <b>41 dialect-bound sites across 19
 * files</b>. The job is parameterising a dozen methods, not replacing SQL with an abstraction nobody
 * asked for.
 *
 * <pre>
 *   pagination           23      more than half the job -- and identical on MySQL
 *   json-type             7
 *   introspection         5
 *   upsert                4
 *   identifier-quoting    1
 *   auto-increment        1
 *   returning             0      see ReturningStrategy -- MySQL's hardest gap is not present here
 * </pre>
 *
 * <p><b>Where it lives and why.</b> In {@code :kernel}, because all three consumers can already see
 * it -- the adapters and the generator via {@code implementation project(':kernel')}, and the
 * RuntimeHost via the staged kernel jar. A dedicated Gradle module would add jar-staging surface,
 * which is this repo's most persistent defect family (REG-128, REG-136, REG-137, REG-144).
 *
 * <p><b>The rule every implementation obeys.</b> A method a dialect cannot honour <b>throws
 * {@link UnsupportedStorageCapabilityException}</b>. It never returns the Postgres answer, an empty
 * string, or a no-op. In the storage layer a silent wrong answer is invisible at the call site and
 * surfaces as missing data much later, which is the single most expensive failure this seam can
 * produce.
 *
 * @see StorageCapability for how generation-time refusal is decided
 */
public interface SqlDialect {

    /** Stable machine key: {@code "postgres"}, {@code "mysql"}, {@code "sqlserver"}, {@code "h2"}. Never translated. */
    String name();

    // ------------------------------------------------------------------ identifiers

    /**
     * Quote an identifier so a reserved word survives: {@code "order"} / {@code [order]} / {@code `order`}.
     *
     * <p>A user will eventually name a field {@code order} or {@code group} (conformance Q1). Case
     * sensitivity (Q2) is the least portable thing in SQL -- Postgres folds unquoted identifiers to
     * lower case, SQL Server depends on collation, and MySQL depends on {@code lower_case_table_names}
     * <i>and the host filesystem</i>. See {@link #foldsUnquotedIdentifiersToLowerCase()}.
     */
    String quoteIdentifier(String rawIdentifier);

    /**
     * Does THIS engine reserve {@code rawIdentifier}, so that using it unquoted is a syntax error?
     *
     * <p><b>The question `quoteIdentifier` could not answer.</b> That method has existed and been
     * conformance-tested since S1, with a javadoc saying "a user will eventually name a field
     * {@code order} or {@code group}" -- and the generator never called it once. A user who did
     * exactly that got DDL the engine rejects at first boot (STOR-6):
     *
     * <pre>
     *   Syntax error in SQL statement "CREATE TABLE rows (id UUID, [*]value VARCHAR(255) ...)"  H2
     *   You have an error in your SQL syntax ... near 'rows ('                                 MySQL 8
     * </pre>
     *
     * <p>Two engines, two different words, one bug -- which is why the reserved set is a DIALECT
     * fact rather than a generator constant: the words genuinely differ. {@code rank} is reserved on
     * MySQL and not on Postgres; {@code plan} is reserved on SQL Server and nowhere else.
     *
     * <p><b>Why this exists instead of quoting everything.</b> Universal quoting would change the
     * emitted DDL for every existing app, and on Postgres it would PIN the case of identifiers that
     * are physically lower-case today -- so an already-deployed database would stop matching. Asking
     * per identifier keeps the emitted SQL byte-identical for every model that does not use a
     * reserved word, which measured as 34 of 36 in the corpus. The two that change were already
     * broken on the engine that reserves their word, so nothing can regress.
     *
     * <p>Callers should not test this themselves -- use {@link #identifier(String)}, which asks and
     * quotes in one step, so the two can never disagree.
     */
    boolean isReservedIdentifier(String rawIdentifier);

    /**
     * An identifier ready to embed in SQL for this engine: quoted only if it has to be.
     *
     * <p>The one call every emitter and every store should make. Testing
     * {@link #isReservedIdentifier} at the call site and quoting separately is the shape that lets
     * the DDL side and the query side drift apart -- and that drift is worse than the bug it fixes,
     * because the app builds, boots, and cannot find its own tables.
     *
     * <p><b>Lower-cased before quoting when the engine folds unquoted identifiers.</b> On Postgres a
     * column created unquoted as {@code Order} is physically {@code order}, so quoting it as
     * {@code "Order"} would name a column that does not exist. {@link #foldsUnquotedIdentifiersToLowerCase()}
     * is exactly the question that makes this safe, and it has been on this interface, unused, the
     * whole time.
     */
    /**
     * {@link #identifier(String)} over a list, for the composition sites that embed a whole column
     * list at once.
     *
     * <p>Returned for TEXT only. A bind-order list keeps the raw names, because the caller looks its
     * values up by them -- {@code UpsertPlan.bindColumns()} is read as map keys, not printed.
     */
    default java.util.List<String> identifiers(java.util.List<String> rawIdentifiers) {
        return rawIdentifiers.stream().map(this::identifier).toList();
    }

    default String identifier(String rawIdentifier) {
        if (rawIdentifier == null || rawIdentifier.isEmpty() || !isReservedIdentifier(rawIdentifier)) {
            return rawIdentifier;
        }
        String toQuote = foldsUnquotedIdentifiersToLowerCase()
                ? rawIdentifier.toLowerCase(java.util.Locale.ROOT)
                : rawIdentifier;
        return quoteIdentifier(toQuote);
    }

    /**
     * Whether an unquoted identifier is folded to lower case by this engine.
     *
     * <p>Q2's pinned decision, exposed rather than assumed: NPDev generates lower-case identifiers
     * everywhere, so the question never arises in practice -- but a dialect that answers this
     * wrongly would let a future generator change break silently on one engine only.
     */
    boolean foldsUnquotedIdentifiersToLowerCase();

    // ------------------------------------------------------------------ DDL types

    /** The column definition for a generated key: {@code SERIAL} / {@code IDENTITY(1,1)} / {@code AUTO_INCREMENT}. */
    String autoIncrementColumn(SqlType type);

    /** The engine's JSON column type: {@code jsonb} / {@code JSON} / {@code nvarchar(max)}. */
    String jsonColumnType();

    /**
     * Whether {@code sqlTypeName} (as reported by the catalog or written in a model) is this engine's
     * JSON type.
     *
     * <p>Four of the seven json-type sites are exactly this test, spelled by hand as
     * {@code "JSON".equalsIgnoreCase(t) || "JSONB".equalsIgnoreCase(t)}. Hand-spelling it once per
     * site is how one of them ends up missing a spelling the others have.
     */
    boolean isJsonColumnType(String sqlTypeName);

    /** {@code timestamptz} / {@code datetime2} / {@code DATETIME(6)}. */
    String timestampColumnType();

    /**
     * The text type that is safe to put in a PRIMARY KEY or an INDEX.
     *
     * <p><b>Not the same question as "what is this engine's text type", and the difference is a
     * boot failure.</b> Found by the first application-level probe to get past the driver
     * (CI run 31273275129, MySQL 8.4):
     *
     * <pre>
     *   Error Code : 1170
     *   Message    : BLOB/TEXT column 'execution_id' used in key specification without a key length
     * </pre>
     *
     * <p>NPDev's internal tables declare {@code execution_id} as {@code TEXT} and make it a primary
     * key. Postgres and H2 accept that happily; MySQL refuses to index a {@code TEXT} column without
     * a prefix length, and SQL Server cannot index {@code NVARCHAR(MAX)} either -- its index key is
     * limited to 900 bytes. So the schema realized fine on two engines and could not be created at
     * all on the other two, at Flyway migration time, on first boot.
     *
     * <p>A bounded type everywhere rather than only where it is required: an engine-conditional
     * width would make the SAME column a different type per engine, which is exactly the kind of
     * divergence that turns a later schema diff into a per-engine puzzle.
     *
     * <p><b>The width is 191, and that number is not arbitrary.</b> InnoDB caps an index key at 3072
     * bytes and utf8mb4 costs 4 bytes per character, so VARCHAR(255) is 1020 bytes and a four-column
     * index over such columns is 4080 -- MySQL error 1071, measured in CI run 31284112143. 191*4 =
     * 764 fits four columns into 3056. Every engine uses the same width so the same column is the
     * same type everywhere, which is the property that keeps a later schema diff readable.
     */
    String keyableTextColumnType();

    /**
     * The text type that is safe to give a {@code DEFAULT}.
     *
     * <p><b>The third role a text column can play, and the third boot failure.</b> "Payload",
     * "key" and "defaulted" are three different questions, and MySQL answers them differently:
     *
     * <pre>
     *   Error Code : 1101
     *   Message    : BLOB, TEXT, GEOMETRY or JSON column 'state' can't have a default value
     * </pre>
     *
     * <p>Measured in CI run 31284450437 on MySQL 8.4, at Flyway line 417 of the realization script:
     * {@code npdev_circuit_breakers.state} is declared {@code TEXT DEFAULT 'CLOSED'}. Postgres, H2
     * and SQL Server all accept a default on their unbounded text type; MySQL refuses outright. The
     * column is not in any key, so {@link #keyableTextColumnType()} was never consulted for it --
     * which is why fixing the key case did not fix this one, and why it is a separate method rather
     * than a widened meaning of that one.
     *
     * <p>Most dialects answer exactly what {@link #portableColumnType(String) portableColumnType("TEXT")}
     * answers; only the engine that cannot carry a default on unbounded text narrows.
     */
    String defaultableTextColumnType();

    /**
     * Rewrite a DECLARED column type into this engine's nearest supported spelling, or return it
     * unchanged when the engine already understands it.
     *
     * <p>This is the generation-time question, and it is not the same as {@link #jsonColumnType()}.
     * Postgres has BOTH {@code JSON} and {@code JSONB} as distinct types, so a model that declares
     * {@code JSON} must keep {@code JSON} there -- mapping every JSON-ish declaration onto the
     * engine's preferred spelling would silently retype a user's column. H2 has no {@code JSONB} at
     * all, so there the narrowing is real and necessary. MySQL will need more of these ({@code UUID}
     * has no native type; {@code TIMESTAMP WITH TIME ZONE} is spelled differently), which is exactly
     * why the decision belongs to the dialect rather than to an {@code if (engine == H2)} in an
     * emitter.
     */
    String portableColumnType(String declaredSqlType);

    // ------------------------------------------------------------------ DML

    /**
     * The suffix for "page N of this query", with the order its placeholders must be bound in.
     *
     * <p>The biggest single group of sites, and free on MySQL -- {@code LIMIT ? OFFSET ?} is
     * identical there. SQL Server is the awkward one: {@code OFFSET ? ROWS FETCH NEXT ? ROWS ONLY}
     * reverses the parameters, which is why this returns {@link PaginationClause} rather than a
     * String.
     */
    PaginationClause limitOffset();

    /** The suffix for "at most N rows" with no offset. */
    PaginationClause limitOnly();

    /**
     * A literal row cap, for existence probes like {@code SELECT 1 FROM t WHERE c = ? LIMIT 1} where
     * the bound is a constant rather than a caller's page size.
     *
     * @throws IllegalArgumentException if {@code rows} is not positive -- {@code LIMIT 0} is a
     *         caller bug that reads as "no rows matched" at every call site that uses this
     */
    String rowLimit(long rows);

    /**
     * <b>SQL Server's {@code OFFSET..FETCH} is a syntax error without {@code ORDER BY}.</b> Postgres
     * and MySQL accept an unordered paginated query happily, so the same statement works on two
     * engines and fails on the third.
     *
     * <p>This is conformance vector P3, and the pinned decision is: <b>refuse, on every engine,
     * rather than inject an order on the one that needs it.</b> Injecting silently would make the
     * engines behave differently in a way nobody can see from the model -- and an arbitrary
     * injected order still returns overlapping pages, so it would trade a loud failure for a silent
     * wrong answer. Callers that paginate must order.
     *
     * <p>Returns true when this engine would reject the unordered query itself; the refusal is
     * enforced uniformly by {@link #requireOrderedForPagination(String)} regardless.
     */
    boolean requiresOrderByForPagination();

    /**
     * Enforce P3's pinned decision: a paginated statement must carry an explicit order.
     *
     * @throws IllegalArgumentException naming the engine and the statement when it does not
     */
    default void requireOrderedForPagination(String sql) {
        if (sql == null || !sql.toUpperCase(java.util.Locale.ROOT).contains("ORDER BY")) {
            throw new IllegalArgumentException(
                    "engine '" + name() + "': a paginated query must declare ORDER BY. Without one the "
                    + "engine may return overlapping or missing rows across pages, and SQL Server "
                    + "rejects OFFSET..FETCH outright. Add an ORDER BY with a tie-breaker column. "
                    + "Statement: " + (sql == null ? "<null>" : sql.strip()));
        }
    }

    /*
     * The three shapes below exist so the ~20 paginated statements in the adapters do not each grow a
     * private copy of "append the clause, remember the newline, enforce P3". They take and return the
     * WHOLE statement, and they preserve the trailing newline a Java text block ends with -- which is
     * what makes the assembled SQL byte-identical to the literal they replaced:
     *
     *     String sql = dialect.paginated("""
     *             SELECT ...
     *             ORDER BY updated_at DESC, execution_id DESC
     *             """);
     *
     * Statements built with a StringBuilder use limitOffset().clause() directly instead, because they
     * control their own separators.
     */

    /** {@code sql} plus this engine's LIMIT/OFFSET suffix, P3-checked. Binds {@link #limitOffset()}. */
    default String paginated(String sql) {
        requireOrderedForPagination(sql);
        return sql + limitOffset().clause() + "\n";
    }

    /** {@code sql} plus this engine's "at most N rows" suffix, P3-checked. Binds {@link #limitOnly()}. */
    default String limited(String sql) {
        requireOrderedForPagination(sql);
        return sql + limitOnly().clause() + "\n";
    }

    /**
     * {@code sql} plus a literal row cap. Binds nothing -- see {@link #rowLimit(long)}.
     *
     * <p><b>Deliberately NOT P3-checked.</b> The commonest use is an existence probe
     * ({@code SELECT 1 FROM t WHERE c = ? LIMIT 1}), where any matching row answers the question and
     * an order would be meaningless work. Sites that want "the FIRST row by some order" must supply
     * the ORDER BY themselves, as the event store's two do.
     *
     * <p><b>Known S5 gap.</b> SQL Server has no suffix row cap: {@code SELECT TOP n} is a PREFIX, and
     * its only suffix form ({@code OFFSET 0 ROWS FETCH NEXT n ROWS ONLY}) requires ORDER BY -- which
     * an existence probe does not have. So {@code SqlServerDialect} cannot answer this as a suffix
     * and the two probe call sites will need a dialect-built statement rather than a dialect-built
     * suffix. Recorded here rather than discovered when SQL Server first runs.
     */
    default String rowLimited(String sql, long rows) {
        return sql + rowLimit(rows) + "\n";
    }

    /**
     * A {@code SELECT} that takes a write lock on the rows it reads, so a check-then-act is atomic.
     *
     * <p><b>Built here rather than suffixed, because the lock is not always a suffix.</b> Three
     * engines write {@code SELECT ... FROM t WHERE ... FOR UPDATE}; T-SQL has no {@code FOR UPDATE}
     * outside a cursor and puts its lock in a TABLE HINT, before the {@code WHERE}:
     *
     * <pre>
     *   SELECT instance_id FROM t WITH (UPDLOCK, ROWLOCK) WHERE claim_key = ?
     * </pre>
     *
     * <p>Measured in CI run 31285509636 -- {@code MigrationClaimStore} spelled the suffix inline and
     * SQL Server refused the app's very first boot with "Line 1: FOR UPDATE clause allowed only for
     * DECLARE CURSOR". This is the same shape as {@link #rowLimited(String, long)}'s known gap: an
     * idiom that is a suffix on three engines and a different POSITION on the fourth cannot be a
     * suffix method, so the dialect assembles the whole statement.
     *
     * @param columns    the select list, already safe (e.g. {@code "instance_id"})
     * @param table      the table name, already safe
     * @param whereClause the predicate WITHOUT the {@code WHERE} keyword, e.g. {@code "claim_key = ?"}
     */
    String selectForUpdate(String columns, String table, String whereClause);

    /**
     * A value ready to hand to {@code PreparedStatement.setObject} for THIS engine.
     *
     * <p>Today this settles exactly one question -- <b>how a {@code java.util.UUID} is bound</b> --
     * and it is a question with no safe default in either direction:
     *
     * <ul>
     *   <li>Postgres and H2 have a native {@code uuid} column type. Binding the STRING form fails:
     *       <i>"column id is of type uuid but expression is of type character varying"</i>.</li>
     *   <li>MySQL and SQL Server do not get a {@code uuid} column from
     *       {@link #portableColumnType(String)} -- they get {@code CHAR}/{@code NVARCHAR}. Binding a
     *       {@code UUID} OBJECT there makes the driver fall back to Java serialization, and MySQL
     *       reports the result as a charset problem:
     *       <pre>Incorrect string value: '\xAC\xED\x00\x05sr...' for column 'id' at row 1</pre>
     *       {@code 0xACED0005} is the Java serialization stream header, not text at all.</li>
     * </ul>
     *
     * <p>Measured against a real MySQL 8.4 container (STOR-10): every write returned 500, after a
     * clean boot and a correctly realized schema. The persistence adapter coerced ids to
     * {@code UUID} objects because that is what the two engines anyone had run needed -- the same
     * two-engine assumption that produced the wrong upsert one layer up.
     *
     * <p>Deliberately a value-shaping hook rather than a boolean capability flag: the caller should
     * not have to know WHICH types need shaping, only that the dialect gets to shape them. A future
     * engine that needs an array or an interval bound differently extends this method instead of
     * adding a second flag every call site must learn about.
     */
    default Object bindableValue(Object value) {
        return value;
    }

    /**
     * The inverse of {@link #bindableValue}: a value just read out of a {@code ResultSet}, restored
     * to the shape the rest of the platform expects.
     *
     * <p>Today this settles one question -- <b>a timestamp that came back without its zone</b>. The
     * DSL's {@code datetime} compiles to {@code java.time.OffsetDateTime} on every engine, but only
     * Postgres and H2 have a column type that keeps an offset. MySQL's {@code DATETIME(6)} does not,
     * so mysql-connector returns a {@code LocalDateTime} and the response then fails to bind:
     *
     * <pre>
     *   Cannot deserialize value of type `java.time.OffsetDateTime` from String
     *   "2026-08-08T12:00:00": Text could not be parsed at index 19
     * </pre>
     *
     * <p>Measured against a real MySQL 8.4 (STOR-10): the row was WRITTEN correctly -- the
     * persistence capability reported SUCCESS -- and the request still returned 400 while mapping the
     * saved record back to its DTO. A write that succeeds and then reports failure is worse than one
     * that fails, because the row is really there.
     *
     * <p>UTC is the right offset to restore, not a guess: the generated JDBC URL pins
     * {@code serverTimezone=UTC}, so the instant the driver stored IS the UTC instant. Reading it
     * back as UTC is the exact inverse of writing it.
     */
    default Object readValue(Object value) {
        return value;
    }

    /**
     * Rename a column, in this engine's own spelling.
     *
     * <p>Four engines, three syntaxes, and one of them is not an {@code ALTER TABLE} at all:
     *
     * <pre>
     *   Postgres, MySQL 8+   ALTER TABLE t RENAME COLUMN old TO new
     *   H2                   ALTER TABLE t ALTER COLUMN old RENAME TO new
     *   SQL Server           EXEC sp_rename 't.old', 'new', 'COLUMN'
     * </pre>
     *
     * <p>{@code ColumnRenamePass} chose between the first two with
     * {@code "Postgres".equals(engine) ? ... : ...} -- so MySQL got H2's spelling and SQL Server got
     * it too. Measured on a real MySQL 8.4 (STOR-10), and the failure is the worst shape this layer
     * produces:
     *
     * <pre>
     *   schema pass 'COLUMN_RENAME' failed at RENAME_COLUMN books.isbn -&gt; isbn13.
     *   Engine 'mysql' COMMITS IMPLICITLY ON DDL, so this pass is HALF APPLIED
     * </pre>
     *
     * <p>A rename is the one migration where getting it wrong loses data rather than time -- the
     * fallback for an unhandled rename is drop-plus-add, which silently empties the column.
     *
     * @param table the table, already safe
     * @param from  the current column name, already safe
     * @param to    the new column name, already safe
     */
    String renameColumn(String table, String from, String to);

    /**
     * Whether this failure is a UNIQUE/primary-key violation, as opposed to any other integrity
     * violation.
     *
     * <p><b>SQLSTATE alone cannot answer this, and that is the whole point.</b> Postgres and H2 have
     * a dedicated code; MySQL and SQL Server report the generic ANSI class and distinguish the cause
     * only by their own error number:
     *
     * <pre>
     *   Postgres, H2   SQLSTATE 23505
     *   MySQL          SQLSTATE 23000, error 1062 / 1586   (23000 is also FK, NOT NULL, CHECK)
     *   SQL Server     SQLSTATE 23000, error 2627 / 2601   (likewise)
     * </pre>
     *
     * <p>{@code MigrationClaimStore} tested {@code "23505".equals(state)}, so on MySQL and SQL Server
     * the ordinary "the canonical row is already there" case was reported as a hard failure and the
     * app refused to boot (STOR-12) -- with a message that confidently said the opposite of the
     * truth: <i>"This is NOT a duplicate-row race, so the row is genuinely absent"</i>.
     *
     * <p><b>Narrow on purpose, on every engine.</b> Widening to the whole {@code 23} class would be
     * the easy fix and the wrong one: {@code 23502} (NOT NULL) and {@code 23503} (foreign key) are
     * real failures that leave the table WITHOUT the row the caller was ensuring, which is exactly
     * the state REG-91 wedged on. So each dialect names the codes that mean UNIQUE and no others.
     */
    boolean isUniqueViolation(java.sql.SQLException failure);

    /** Insert-or-update. See {@link UpsertStrategy} for why this is a strategy and not a template. */
    UpsertStrategy upsert();

    /** How a generated key comes back. See {@link ReturningStrategy} -- zero sites use it today. */
    ReturningStrategy returning();

    /** Postgres {@code ::} shorthand vs {@code CAST(x AS t)}. Zero sites today; here so the first one has somewhere to go. */
    String cast(String expression, SqlType type);

    // ------------------------------------------------------------------ introspection

    /**
     * Schemas whose tables are never part of an app's model.
     *
     * <p>Spelled {@code Set.of("information_schema", "pg_catalog")} by hand in two places today --
     * and {@code pg_catalog} is Postgres-only, so both copies are wrong the moment a second engine
     * exists. MySQL's are {@code mysql} / {@code performance_schema} / {@code sys}.
     */
    Set<String> systemSchemas();

    /*
     * THE RESULT SHAPE IS PART OF THE CONTRACT, not just the SQL text.
     *
     * Every engine's catalog is spelled differently -- Postgres answers indexes from pg_index, H2
     * from INFORMATION_SCHEMA.INDEXES, MySQL from STATISTICS, SQL Server from sys.indexes. If each
     * dialect also returned differently-NAMED columns, the caller would need a per-engine branch to
     * read the answer, and the dialect layer would have moved the engine switch rather than removed
     * it. So the column names below are fixed, and each dialect aliases its catalog to them.
     */

    /*
     * BIND, DO NOT SPLICE. All three take their schema and table as PARAMETERS -- the statements
     * below carry `?` placeholders and the caller binds. In information_schema (and pg_catalog, and
     * sys.*) a table or schema name is compared as a VALUE, not used as an identifier, so it binds
     * cleanly on every engine and there is no reason to build the name into the text.
     *
     * Escaping-then-splicing would also have worked and is what the first version did. Binding is
     * better for the boring reason: an escape helper is one edit away from being forgotten on a
     * fourth dialect, and a bind parameter cannot be.
     */

    /**
     * Every base table in a schema. Columns: {@code table_name}.
     *
     * <p>Binds: 1 = schema name, or NULL for the current schema.
     */
    String listTablesSql();

    /**
     * Every column of {@code table} -- conformance I2.
     *
     * <p>Columns: {@code column_name}, {@code data_type}, {@code is_nullable}, {@code column_default}.
     * The schema DIFF depends on all four: nullability coming back wrong makes evolution propose
     * changes that are not needed, or miss ones that are.
     *
     * <p>Binds: 1 = schema name (NULL for the current schema), 2 = table name.
     */
    String listColumnsSql();

    /**
     * Every index of {@code table} -- conformance I3, and REG-129's exact bug class.
     *
     * <p>Columns: {@code index_name}, {@code column_name}, {@code is_unique}, one row per indexed
     * column in ordinal order.
     *
     * <p>Binds: 1 = schema name (NULL for the current schema), 2 = table name.
     */
    String listIndexesSql();

    /** Whether a named constraint exists; binds (constraintName, tableName) in that order. */
    String constraintExistsSql();

    /**
     * A {@code COUNT(*)} that is &gt; 0 exactly when {@code tableName} exists in the CURRENT schema.
     *
     * <p>"The current schema" is itself dialect-bound -- {@code CURRENT_SCHEMA()} on Postgres and H2,
     * {@code DATABASE()} on MySQL, {@code SCHEMA_NAME()} on SQL Server -- which is why the whole
     * statement lives here rather than just the catalog name.
     */
    String tableExistsInCurrentSchemaSql(String tableName);

    /**
     * Wrap a DDL statement so it is applied only when {@code constraintName} is absent from
     * {@code tableName}.
     *
     * <p>Postgres does this with an anonymous {@code DO $$ ... $$} block -- a construct MySQL and SQL
     * Server have no equivalent for, which is why this is a dialect question rather than a string
     * the emitter builds.
     */
    String guardedConstraintDdl(String constraintName, String tableName, String ddlStatement);

    /*
     * ------------------------------------------------------------------------------------------
     * THE THREE GUARDED-DDL IDIOMS -- ledger STOR-5.
     *
     * `SchemaRealizationEmitter` writes the script that creates NPDev's own internal tables and the
     * app's business tables. It wrote them in PostgreSQL/H2 guarded DDL, unconditionally, whatever
     * engine the app was for:
     *
     *     CREATE TABLE IF NOT EXISTS ...          Postgres yes   H2 yes   MySQL yes   SQL Server NO
     *     CREATE INDEX IF NOT EXISTS ...          Postgres yes   H2 yes   MySQL NO    SQL Server NO
     *     ALTER TABLE ... ADD COLUMN IF NOT EXISTS  Postgres yes H2 yes   MySQL NO    SQL Server NO
     *
     * So NPDev's own first migration could not run on two of its four engines. Measured one CI round
     * at a time (Flyway stops at the first statement it cannot execute) until
     * check-emitted-sql-portability.py made the whole set visible in one local scan.
     *
     * These are siblings of guardedConstraintDdl above, and for its stated reason: "a construct MySQL
     * and SQL Server have no equivalent for, which is why this is a dialect question rather than a
     * string the emitter builds."
     *
     * EVERY implementation must be IDEMPOTENT. The additive script is a Flyway *repeatable*
     * migration that re-runs whenever its checksum changes, so a bare CREATE fails the whole boot the
     * second time -- REG-38, learned on H2.
     *
     * Each takes the PLAIN statement and returns the guarded form, so the emitter never spells a
     * guard itself and Postgres/H2 output stays byte-identical to what it was before the extraction.
     * ------------------------------------------------------------------------------------------
     */

    /**
     * {@code createStatement} ({@code CREATE TABLE t (...)}) made idempotent.
     *
     * @param tableName the table, for engines that need a catalog lookup rather than a keyword
     */
    String guardedCreateTable(String tableName, String createStatement);

    /**
     * {@code createStatement} ({@code CREATE [UNIQUE] INDEX i ON t (...)}) made idempotent.
     *
     * <p>The one MySQL rejects outright: it has {@code CREATE TABLE IF NOT EXISTS} but no
     * {@code CREATE INDEX IF NOT EXISTS} (error 1064), which is why an engine that got past table
     * creation still stopped here.
     */
    String guardedCreateIndex(String indexName, String tableName, String createStatement);

    /**
     * {@code alterStatement} ({@code ALTER TABLE t ADD COLUMN c TYPE}) made idempotent.
     *
     * <p><b>Also normalises the keyword.</b> T-SQL has no {@code COLUMN} in {@code ALTER TABLE t ADD
     * c TYPE}, so an implementation for SQL Server must drop it -- a second, quieter incompatibility
     * that sits underneath the {@code IF NOT EXISTS} one and would have surfaced as its own CI round.
     */
    String guardedAddColumn(String tableName, String columnName, String alterStatement);

    // ------------------------------------------------------------------ honesty

    /**
     * What this engine can actually do. Never declare a capability whose conformance vector is not
     * green -- the generator refuses user models against this set.
     */
    Set<StorageCapability> capabilities();

    /** True when {@code capability} is in {@link #capabilities()}. */
    default boolean supports(StorageCapability capability) {
        return capabilities().contains(capability);
    }

    /**
     * Assert support, or refuse loudly.
     *
     * @throws UnsupportedStorageCapabilityException naming this engine and the capability
     */
    default void require(StorageCapability capability) {
        if (!supports(capability)) {
            throw new UnsupportedStorageCapabilityException(name(), capability);
        }
    }
}
