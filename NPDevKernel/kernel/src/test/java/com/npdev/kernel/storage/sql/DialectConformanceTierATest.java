package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Tier A of the conformance suite: no database at all.</b>
 *
 * <p>One suite, parameterised by dialect, asserting the things that are pure functions of the
 * dialect -- string generation, declared parameter order, and the refusals. Every engine, in one
 * run, in milliseconds. See {@code storage/PROBE_APPS.md} for why the tiers exist: 16 of the 20
 * behavioural vectors need only a connection, and the four that need a real app are the exception.
 *
 * <p><b>The one rule for the behavioural tiers does not apply here.</b> Tier B and C assert on
 * OBSERVABLE BEHAVIOUR and never on SQL text, because a test asserting {@code ON CONFLICT} passes
 * only for Postgres and has to be rewritten per engine -- which is the duplication the architecture
 * removes. Tier A is the deliberate exception: string generation IS the behaviour here, and these
 * are the assertions that catch a typo before a container ever starts.
 */
@DisplayName("Conformance Tier A -- dialect string generation, no database")
class DialectConformanceTierATest {

    static Stream<SqlDialect> dialects() {
        return SqlDialects.all().stream();
    }

    // ------------------------------------------------------------------ P1/P2: pagination

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("P1/P2: a pagination clause exists and binds exactly two parameters")
    void paginationClauseBindsLimitAndOffset(SqlDialect dialect) {
        PaginationClause page = dialect.limitOffset();
        assertEquals(2, page.parameterCount(), dialect.name() + ": " + page.clause());
        assertTrue(page.parameters().contains(PaginationClause.Parameter.LIMIT));
        assertTrue(page.parameters().contains(PaginationClause.Parameter.OFFSET));
        assertEquals(2, page.clause().chars().filter(c -> c == '?').count(),
                dialect.name() + ": placeholder count must match the declared parameters -- " + page.clause());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("P2: values() follows the DECLARED order, which is not the same on every engine")
    void valuesFollowTheDeclaredOrder(SqlDialect dialect) {
        List<Integer> bound = dialect.limitOffset().values(20, 40);
        List<PaginationClause.Parameter> order = dialect.limitOffset().parameters();
        for (int i = 0; i < order.size(); i++) {
            int expected = order.get(i) == PaginationClause.Parameter.LIMIT ? 20 : 40;
            assertEquals(expected, bound.get(i),
                    dialect.name() + ": parameter " + i + " should carry " + order.get(i));
        }
    }

    @Test
    @DisplayName("P2: SQL Server REVERSES the parameters -- the reason this is a type and not a String")
    void sqlServerReversesPaginationParameters() {
        // The whole justification for PaginationClause. If this ever starts matching Postgres, either
        // SQL Server changed or someone "simplified" the dialect into returning the wrong page.
        assertEquals(List.of(PaginationClause.Parameter.LIMIT, PaginationClause.Parameter.OFFSET),
                PostgresDialect.INSTANCE.limitOffset().parameters());
        assertEquals(List.of(PaginationClause.Parameter.OFFSET, PaginationClause.Parameter.LIMIT),
                SqlServerDialect.INSTANCE.limitOffset().parameters());
        assertEquals(List.of(40, 20), SqlServerDialect.INSTANCE.limitOffset().values(20, 40));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("P3: paginating without an ORDER BY is refused on EVERY engine, not just the one that must")
    void unorderedPaginationIsRefusedEverywhere(SqlDialect dialect) {
        // The pinned decision. Only SQL Server would reject it itself; refusing uniformly is what
        // keeps the engines behaving the same, and an injected arbitrary order would still hand back
        // overlapping pages -- a loud failure traded for a silent wrong answer.
        assertThrows(IllegalArgumentException.class, () -> dialect.paginated("SELECT a FROM t\n"),
                dialect.name() + " accepted an unordered paginated query");
        assertThrows(IllegalArgumentException.class, () -> dialect.limited("SELECT a FROM t\n"),
                dialect.name() + " accepted an unordered limited query");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("P3: an ordered query is accepted, and keeps its own text intact")
    void orderedPaginationIsAccepted(SqlDialect dialect) {
        String base = "SELECT a FROM t ORDER BY a\n";
        String paginated = dialect.paginated(base);
        assertTrue(paginated.startsWith(base),
                dialect.name() + ": the caller's statement must survive verbatim -- " + paginated);
        assertTrue(paginated.contains(dialect.limitOffset().clause()));
    }

    // ------------------------------------------------------------------ A1: auto-increment

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("A1: an auto-increment column is spelled, and a non-integer key is refused")
    void autoIncrementColumn(SqlDialect dialect) {
        assertTrue(!dialect.autoIncrementColumn(SqlType.INT).isBlank());
        assertTrue(!dialect.autoIncrementColumn(SqlType.BIGINT).isBlank());
        // Refused rather than silently producing a column definition that cannot auto-increment.
        assertThrows(IllegalArgumentException.class, () -> dialect.autoIncrementColumn(SqlType.TEXT));
        assertThrows(IllegalArgumentException.class, () -> dialect.autoIncrementColumn(SqlType.UUID));
    }

    @Test
    @DisplayName("A1: each engine spells it its own way -- SERIAL / IDENTITY(1,1) / AUTO_INCREMENT")
    void autoIncrementSpellingsActuallyDiffer() {
        assertEquals("SERIAL", PostgresDialect.INSTANCE.autoIncrementColumn(SqlType.INT));
        assertEquals("INT AUTO_INCREMENT", MySqlDialect.INSTANCE.autoIncrementColumn(SqlType.INT));
        assertEquals("INT IDENTITY(1,1)", SqlServerDialect.INSTANCE.autoIncrementColumn(SqlType.INT));
    }

    // ------------------------------------------------------------------ Q1/Q2: identifiers

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("Q1: a reserved word survives quoting, and the CLOSING delimiter is escaped inside")
    void quoteIdentifier(SqlDialect dialect) {
        String quoted = dialect.quoteIdentifier("order");
        assertTrue(quoted.length() > "order".length(), dialect.name() + ": " + quoted);
        assertTrue(quoted.contains("order"), dialect.name() + ": " + quoted);

        // The property that matters is that an identifier cannot TERMINATE the quoting early -- that
        // is an injection, not a formatting quirk. It is specifically the CLOSING delimiter that must
        // be escaped, which is not always the same character as the opening one: Postgres and MySQL
        // use a symmetric quote, SQL Server uses [ ... ] and only ] needs doubling. An earlier
        // version of this test escaped the OPENING character and failed SQL Server for being
        // correct -- worth keeping in mind, because "the test was wrong" is the less likely diagnosis
        // and therefore the one that gets checked last.
        char closing = quoted.charAt(quoted.length() - 1);
        String hostile = dialect.quoteIdentifier("a" + closing + "b");
        assertEquals(quoted.charAt(0), hostile.charAt(0), dialect.name() + ": " + hostile);
        assertEquals(closing, hostile.charAt(hostile.length() - 1), dialect.name() + ": " + hostile);
        // "a" + closing + "b" is 3 characters; escaped it becomes 4, plus the two delimiters.
        assertEquals(6, hostile.length(),
                dialect.name() + " did not escape a closing delimiter inside the identifier: " + hostile);
    }

    @Test
    @DisplayName("Q1: the three quoting styles are genuinely different")
    void quotingStylesDiffer() {
        assertEquals("\"order\"", PostgresDialect.INSTANCE.quoteIdentifier("order"));
        assertEquals("`order`", MySqlDialect.INSTANCE.quoteIdentifier("order"));
        assertEquals("[order]", SqlServerDialect.INSTANCE.quoteIdentifier("order"));
    }

    @Test
    @DisplayName("Q2: case-folding is PINNED per engine, not probed")
    void caseFoldingIsPinned() {
        // The least portable thing in SQL. Postgres folds; MySQL depends on lower_case_table_names
        // AND the host filesystem; SQL Server depends on collation. NPDev generates lower-case
        // identifiers everywhere so the question never arises -- these answers state the portable
        // assumption rather than a configuration-specific truth, and exist so a future generator
        // change that starts relying on folding fails here rather than on one engine in production.
        assertTrue(PostgresDialect.INSTANCE.foldsUnquotedIdentifiersToLowerCase());
        assertTrue(H2Dialect.INSTANCE.foldsUnquotedIdentifiersToLowerCase());
        assertTrue(!MySqlDialect.INSTANCE.foldsUnquotedIdentifiersToLowerCase());
        assertTrue(!SqlServerDialect.INSTANCE.foldsUnquotedIdentifiersToLowerCase());
    }

    // ------------------------------------------------------------------ U1: upsert

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("U1: an upsert statement names the table, every column, and one placeholder each")
    void upsertStatementShape(SqlDialect dialect) {
        String sql = dialect.upsert().statementFor("t", List.of("id"), List.of("id", "a", "b"));
        assertTrue(sql.contains("t"), dialect.name() + ": " + sql);
        for (String column : List.of("id", "a", "b")) {
            assertTrue(sql.contains(column), dialect.name() + " lost column " + column + ": " + sql);
        }
        assertEquals(3, sql.chars().filter(c -> c == '?').count(),
                dialect.name() + ": one placeholder per value column -- " + sql);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("U1: an upsert with no key column is REFUSED, never degraded to a plain INSERT")
    void upsertWithoutKeyIsRefused(SqlDialect dialect) {
        // Degrading to an INSERT would turn "update the existing row" into "fail on the duplicate",
        // or worse, into a second row. The X0 rule at the smallest scale.
        assertThrows(IllegalArgumentException.class,
                () -> dialect.upsert().statementFor("t", List.of(), List.of("a")));
        assertThrows(IllegalArgumentException.class,
                () -> dialect.upsert().statementFor("t", List.of("id"), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("U1: an all-key upsert produces a legal statement, not an empty SET list")
    void allKeyUpsertIsLegal(SqlDialect dialect) {
        String sql = dialect.upsert().statementFor("t", List.of("id"), List.of("id"));
        assertTrue(!sql.isBlank(), dialect.name());
        // The failure this guards is a dangling "SET" with nothing after it -- a syntax error that
        // only appears for the rare single-column table.
        assertTrue(!sql.trim().toUpperCase(java.util.Locale.ROOT).endsWith("SET"), dialect.name() + ": " + sql);
    }

    @Test
    @DisplayName("U2: SQL Server's MERGE carries HOLDLOCK and its mandatory terminator")
    void sqlServerMergeIsConcurrencySafe() {
        String sql = SqlServerDialect.INSTANCE.upsert().statementFor("t", List.of("id"), List.of("id", "a"));
        // Without HOLDLOCK, two concurrent merges on the same key can both find no match and both
        // insert. It fails in production under load and never in a unit test -- which is exactly why
        // the hint is asserted HERE, where a unit test can see it.
        assertTrue(sql.contains("HOLDLOCK"), sql);
        // MERGE without a terminating semicolon is a syntax error, and the commonest way a
        // hand-written one fails to run at all.
        assertTrue(sql.trim().endsWith(";"), sql);
    }

    @Test
    @DisplayName("U1: the four engines produce four genuinely different statements")
    void upsertStatementsDifferPerEngine() {
        String pg = PostgresDialect.INSTANCE.upsert().statementFor("t", List.of("id"), List.of("id", "a"));
        String h2 = H2Dialect.INSTANCE.upsert().statementFor("t", List.of("id"), List.of("id", "a"));
        String my = MySqlDialect.INSTANCE.upsert().statementFor("t", List.of("id"), List.of("id", "a"));
        String ms = SqlServerDialect.INSTANCE.upsert().statementFor("t", List.of("id"), List.of("id", "a"));
        assertTrue(pg.contains("ON CONFLICT"), pg);
        assertTrue(h2.contains("MERGE INTO"), h2);
        assertTrue(my.contains("ON DUPLICATE KEY UPDATE"), my);
        assertTrue(ms.startsWith("MERGE "), ms);
        assertNotEquals(pg, my);
        assertNotEquals(my, ms);
    }

    // ------------------------------------------------------------------ A2/C1: honest refusals

    @Test
    @DisplayName("A2: MySQL admits it has no RETURNING instead of inventing one")
    void mySqlRefusesReturning() {
        ReturningStrategy returning = MySqlDialect.INSTANCE.returning();
        assertTrue(!returning.isInline());
        // Both directions throw. The two-statement LAST_INSERT_ID() path is deliberately NOT built:
        // zero production sites use RETURNING, and an unexercised path gets trusted the first time
        // it runs. A2 failing is what will say it is needed.
        assertThrows(UnsupportedOperationException.class, () -> returning.inlineClause(List.of("id")));
        assertThrows(UnsupportedOperationException.class, returning::secondQuerySql);
    }

    @Test
    @DisplayName("A2: the engines that CAN return inline do, and say where the clause goes")
    void inlineReturningEngines() {
        assertTrue(PostgresDialect.INSTANCE.returning().isInline());
        assertEquals("RETURNING id", PostgresDialect.INSTANCE.returning().inlineClause(List.of("id")));
        assertTrue(SqlServerDialect.INSTANCE.returning().isInline());
        // OUTPUT INSERTED.x, and it goes BEFORE VALUES rather than after -- a caller that appends it
        // like RETURNING gets a syntax error, which is the better failure but still worth pinning.
        assertEquals("OUTPUT INSERTED.id", SqlServerDialect.INSTANCE.returning().inlineClause(List.of("id")));
    }

    @Test
    @DisplayName("C1: SQL Server refuses a suffix row cap rather than returning a plausible one")
    void sqlServerRefusesSuffixRowLimit() {
        // SELECT TOP n is a PREFIX and the suffix form needs an ORDER BY an existence probe has no
        // reason to carry. Returning something plausible here is the silent-answer defect in the
        // least visible layer; the refusal names the alternative.
        UnsupportedOperationException refusal = assertThrows(UnsupportedOperationException.class,
                () -> SqlServerDialect.INSTANCE.rowLimit(1));
        assertTrue(refusal.getMessage().contains("existsProbe"), refusal.getMessage());
        assertEquals("SELECT TOP 1 1 FROM t WHERE c = ?",
                SqlServerDialect.INSTANCE.existsProbe("t", "c = ?"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("C1: rowLimit(0) is refused -- it reads as 'no rows matched' at every call site")
    void rowLimitRejectsZero(SqlDialect dialect) {
        assertThrows(IllegalArgumentException.class, () -> dialect.rowLimit(0));
        assertThrows(IllegalArgumentException.class, () -> dialect.rowLimit(-5));
    }

    // ------------------------------------------------------------------ C2 / T2: capabilities

    @Test
    @DisplayName("T2: DDL_IN_TRANSACTION is declared per engine, and the answers actually differ")
    void ddlTransactionalityIsDeclaredHonestly() {
        // The most consequential capability in the set. MySQL and H2 commit implicitly on DDL, so a
        // failed migration cannot be rolled back there; Postgres and SQL Server can. If this row ever
        // reads the same for all four, one of them is lying and a half-applied migration will be
        // reported as a clean failure.
        assertTrue(PostgresDialect.INSTANCE.supports(StorageCapability.DDL_IN_TRANSACTION));
        assertTrue(SqlServerDialect.INSTANCE.supports(StorageCapability.DDL_IN_TRANSACTION));
        assertTrue(!H2Dialect.INSTANCE.supports(StorageCapability.DDL_IN_TRANSACTION));
        assertTrue(!MySqlDialect.INSTANCE.supports(StorageCapability.DDL_IN_TRANSACTION));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("C1: require() names the engine AND the capability when it refuses")
    void requireNamesBoth(SqlDialect dialect) {
        StorageCapability absent = Stream.of(StorageCapability.values())
                .filter(capability -> !dialect.supports(capability))
                .findFirst().orElse(null);
        if (absent == null) {
            return; // this engine supports everything; nothing to refuse
        }
        UnsupportedStorageCapabilityException refusal = assertThrows(
                UnsupportedStorageCapabilityException.class, () -> dialect.require(absent));
        assertEquals(dialect.name(), refusal.engine());
        assertEquals(absent, refusal.capability());
        assertTrue(refusal.getMessage().contains(dialect.name()), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(absent.name()), refusal.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("every engine declares the baseline any persisted app needs")
    void baselineCapabilities(SqlDialect dialect) {
        assertTrue(dialect.supports(StorageCapability.TRANSACTIONS), dialect.name());
        assertTrue(dialect.supports(StorageCapability.SCHEMA_EVOLUTION), dialect.name());
        assertTrue(dialect.supports(StorageCapability.UNIQUE_CONSTRAINTS), dialect.name());
    }

    // ------------------------------------------------------------------ J1/I1: types, catalogs

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("J1: the JSON column type is named, and recognised by its own test")
    void jsonColumnTypeIsSelfConsistent(SqlDialect dialect) {
        String type = dialect.jsonColumnType();
        assertTrue(!type.isBlank(), dialect.name());
        if (dialect == SqlServerDialect.INSTANCE) {
            // The documented exception, and the finding worth keeping: SQL Server stores JSON in
            // NVARCHAR(MAX), which is INDISTINGUISHABLE from an ordinary long text column in the
            // catalog. isJsonColumnType cannot answer honestly there, so it says no rather than
            // making the platform try to parse prose. J1 will fail on this engine until the model,
            // not the catalog, supplies the column's JSON-ness.
            assertTrue(!dialect.isJsonColumnType(type), "SQL Server should not claim NVARCHAR(MAX) is JSON");
            return;
        }
        assertTrue(dialect.isJsonColumnType(type),
                dialect.name() + " does not recognise its own JSON type '" + type + "'");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("I1/I2/I3: the introspection statements return the CONTRACTED column names")
    void introspectionResultShapeIsStable(SqlDialect dialect) {
        // The result SHAPE is part of the contract, not just the SQL. If each dialect aliased its
        // catalog differently, the caller would need a per-engine branch to read the answer and the
        // dialect layer would have MOVED the engine switch rather than removed it.
        assertTrue(dialect.listTablesSql(null).contains("table_name"), dialect.name());
        String columns = dialect.listColumnsSql(null, "t");
        for (String expected : List.of("column_name", "data_type", "is_nullable", "column_default")) {
            assertTrue(columns.contains(expected), dialect.name() + " missing " + expected + ": " + columns);
        }
        String indexes = dialect.listIndexesSql(null, "t");
        for (String expected : List.of("index_name", "column_name", "is_unique")) {
            assertTrue(indexes.contains(expected), dialect.name() + " missing " + expected + ": " + indexes);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("Q1: a hostile table name cannot break out of an introspection literal")
    void introspectionEscapesLiterals(SqlDialect dialect) {
        String sql = dialect.listColumnsSql(null, "o'brien");
        assertTrue(sql.contains("o''brien"), dialect.name() + ": unescaped quote in " + sql);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("system schemas never include a name an app would use for its own data")
    void systemSchemasAreEngineSpecific(SqlDialect dialect) {
        assertTrue(dialect.systemSchemas().contains("information_schema"), dialect.name());
        // pg_catalog is Postgres-only -- the hand-written Set.of("information_schema", "pg_catalog")
        // that used to sit in two RuntimeHost files was already wrong for any second engine.
        if (dialect == MySqlDialect.INSTANCE) {
            assertTrue(dialect.systemSchemas().contains("performance_schema"));
            assertTrue(!dialect.systemSchemas().contains("pg_catalog"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("guarded constraint DDL is idempotent-shaped and carries the statement verbatim")
    void guardedConstraintDdl(SqlDialect dialect) {
        String ddl = "ALTER TABLE t ADD CONSTRAINT ux_t_a UNIQUE (a)";
        String guarded = dialect.guardedConstraintDdl("ux_t_a", "t", ddl);
        assertTrue(guarded.contains(ddl), dialect.name() + " dropped the statement: " + guarded);
        assertTrue(guarded.contains("ux_t_a") && guarded.contains("t"), dialect.name());
        // REG-38: this lands in a Flyway *repeatable* migration that re-runs on any checksum change,
        // so a bare ADD CONSTRAINT fails the whole boot the second time. Every engine must guard.
        String upper = guarded.toUpperCase(java.util.Locale.ROOT);
        assertTrue(upper.contains("IF NOT EXISTS") || upper.contains("IF EXISTS") || upper.contains("COUNT(*)"),
                dialect.name() + " emitted an unguarded ADD CONSTRAINT: " + guarded);
    }
}
