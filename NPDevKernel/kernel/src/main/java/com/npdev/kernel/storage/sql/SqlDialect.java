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
     */
    String keyableTextColumnType();

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
