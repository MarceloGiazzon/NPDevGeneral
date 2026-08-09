package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("I3: the keyable text type is BOUNDED on every engine, so a PK on it can be created")
    void keyableTextTypeIsBounded(SqlDialect dialect) {
        // storage/FULL_SUPPORT_PLAN.md gap A. NPDev's own npdev_flow_instances makes the TEXT column
        // `execution_id` its PRIMARY KEY. Postgres and H2 index TEXT happily; MySQL refuses without a
        // prefix length (error 1170) and SQL Server cannot index NVARCHAR(MAX) -- so the platform's
        // own schema could not be CREATED on two of its four engines. Found by the first
        // application-level probe to get that far (CI run 31273275129), inside Flyway, on first boot.
        String type = dialect.keyableTextColumnType();
        assertTrue(type.matches("(?i)N?VARCHAR\\(\\d+\\)"),
                dialect.name() + ": a keyable text type must be BOUNDED -- an unbounded one cannot be "
                + "indexed on MySQL or SQL Server. Got: " + type);
        // Bounded on EVERY engine, not only where required: an engine-conditional width would make
        // the same internal column a different type per engine, and a later schema diff would then
        // have to explain the difference rather than ignore it.
        assertTrue(!type.equalsIgnoreCase("TEXT"), dialect.name() + ": " + type);
    }

    @Test
    @DisplayName("I3: SQL Server keys on NVARCHAR -- plain VARCHAR there loses characters silently")
    void sqlServerKeyableTextIsUnicode() {
        // Conformance J2's lesson applied to a different method: SQL Server's VARCHAR is non-Unicode.
        // A primary key that silently drops characters would be worse than one that fails to create.
        assertTrue(SqlServerDialect.INSTANCE.keyableTextColumnType().toUpperCase(java.util.Locale.ROOT)
                        .startsWith("NVARCHAR"),
                SqlServerDialect.INSTANCE.keyableTextColumnType());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("I4: a text column that carries a DEFAULT is a type this engine will accept one on")
    void defaultableTextTypeAcceptsADefault(SqlDialect dialect) {
        // STOR-7, the THIRD role a text column plays. The keyable question (I3 above) was asked and
        // answered; this one was not, and npdev_circuit_breakers.state is declared
        // `TEXT DEFAULT 'CLOSED'`:
        //
        //     Error Code : 1101
        //     BLOB, TEXT, GEOMETRY or JSON column 'state' can't have a default value   (MySQL 8.4)
        //
        // Measured at Flyway line 417 on first boot, CI run 31284450437. The column is in no key, so
        // fixing I3 did nothing for it -- which is exactly why it is a separate method and a separate
        // vector rather than a widened meaning of the keyable one.
        String type = dialect.defaultableTextColumnType();
        assertTrue(type != null && !type.isBlank(), dialect.name() + ": must answer something");
        if ("mysql".equals(dialect.name())) {
            assertTrue(type.matches("(?i)N?VARCHAR\\(\\d+\\)"),
                    "MySQL cannot put a DEFAULT on unbounded text at all, so the answer must be a "
                    + "BOUNDED type. Got: " + type);
        } else {
            // Everyone else keeps the payload answer. Asserted rather than assumed, because
            // narrowing an engine that never had the problem would silently retype every internal
            // text column on it -- a schema diff for nothing, on the two engines that were working.
            assertEquals(dialect.portableColumnType("TEXT"), type,
                    dialect.name() + ": accepts a DEFAULT on unbounded text, so it must not narrow");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("L1: EVERY engine can lock the rows it reads, whatever shape its lock takes")
    void everyDialectCanLockTheRowsItReads(SqlDialect dialect) {
        // STOR-9, and the same lesson as C1 one layer down: the gap was never "SQL Server cannot lock
        // a row", it is that the lock is not a SUFFIX there. T-SQL has no FOR UPDATE outside a cursor
        // and puts the lock in a table hint BEFORE the WHERE:
        //
        //     Line 1: FOR UPDATE clause allowed only for DECLARE CURSOR.   (CI run 31285509636)
        //
        // MigrationClaimStore spelled the suffix inline, so every app's FIRST boot on SQL Server died
        // taking the migration lock -- after the schema had realized correctly, which is what made it
        // look like a new bug rather than the same one.
        String sql = dialect.selectForUpdate("instance_id", "npdev_schema_migration_claim",
                "claim_key = ?");
        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        assertTrue(upper.startsWith("SELECT INSTANCE_ID FROM NPDEV_SCHEMA_MIGRATION_CLAIM"), sql);
        assertTrue(sql.contains("claim_key = ?"), sql);
        if ("sqlserver".equals(dialect.name())) {
            assertTrue(upper.contains("WITH (UPDLOCK, ROWLOCK)"), sql);
            assertFalse(upper.contains("FOR UPDATE"),
                    "T-SQL has no FOR UPDATE outside a cursor: " + sql);
            // Position is the whole point -- a hint AFTER the WHERE is a syntax error, so asserting
            // its mere presence would pass on a statement the engine still refuses.
            assertTrue(upper.indexOf("UPDLOCK") < upper.indexOf("WHERE"),
                    "the hint must precede the WHERE: " + sql);
        } else {
            assertTrue(upper.endsWith("FOR UPDATE"), sql);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("R1: EVERY engine can rename a column, and one of them does not use ALTER TABLE")
    void everyDialectCanRenameAColumn(SqlDialect dialect) {
        // STOR-10 finding 5. ColumnRenamePass chose between TWO spellings with
        // `"Postgres".equals(engine) ? ... : ...`, so MySQL silently got H2's -- and SQL Server does
        // not use ALTER TABLE for this at all, so the two-way shape could never have covered four
        // engines. Measured on a real MySQL 8.4: the rename pass half-applied, on an engine that
        // commits implicitly on DDL, which is the one migration where being wrong loses data.
        String sql = dialect.renameColumn("books", "isbn", "isbn13");
        assertTrue(sql.contains("isbn") && sql.contains("isbn13") && sql.contains("books"), sql);
        switch (dialect.name()) {
            case "sqlserver" -> {
                assertTrue(sql.startsWith("EXEC sp_rename"), sql);
                // The OLD name is qualified by its table, the NEW one is bare. Passing the new name
                // qualified renames the column to a literal string containing a dot -- which
                // SUCCEEDS, and leaves a column nothing can address.
                assertTrue(sql.contains("'books.isbn'"), sql);
                assertTrue(sql.contains("'isbn13'") && !sql.contains("'books.isbn13'"), sql);
                assertTrue(sql.endsWith("'COLUMN'"), sql);
            }
            case "h2" -> assertEquals("ALTER TABLE books ALTER COLUMN isbn RENAME TO isbn13", sql);
            // MySQL shares Postgres's spelling here, not H2's -- which is exactly the pairing the
            // old two-way branch got backwards.
            default -> assertEquals("ALTER TABLE books RENAME COLUMN isbn TO isbn13", sql);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("C1: EVERY engine can cap a statement, whatever shape its cap takes")
    void everyDialectCanCapAStatement(SqlDialect dialect) {
        // storage/FULL_SUPPORT_PLAN.md W1.3. The gap was never "SQL Server cannot cap rows" -- it is
        // that it cannot cap them with a SUFFIX. All four real call sites (two existence probes in
        // PostgresPersistenceCapabilityAdapter, two first-by-order reads in JdbcEventStore) want
        // "at most n rows", and suffix-vs-prefix is the engine's business, which is what a dialect is
        // for. Pushing the question out to the call sites would have MOVED the engine switch rather
        // than removed it -- the thing SqlDialect's own javadoc says this seam must never become.
        String capped = dialect.rowLimited("select 1 from t where c = ?", 1);
        assertTrue(capped.toLowerCase(java.util.Locale.ROOT).contains("select"), capped);
        assertTrue(capped.contains("1"), capped);
        if ("sqlserver".equals(dialect.name())) {
            // Case-preserving on purpose: the rewrite must not reformat a caller's statement, only
            // insert the cap. Asserting on the caller's own casing is how that stays true.
            assertTrue(capped.regionMatches(true, 0, "select TOP 1 ", 0, "select TOP 1 ".length()),
                    "SQL Server caps with a PREFIX: " + capped);
        }
    }

    @Test
    @DisplayName("C1: SQL Server's cap goes AFTER DISTINCT, and refuses what it cannot place")
    void sqlServerPrefixCapHandlesDistinctAndRefusesCtes() {
        // T-SQL's grammar is SELECT [ALL|DISTINCT] [TOP n] -- `SELECT TOP 1 DISTINCT` is a syntax
        // error, and one that would only appear the day a caller wrote DISTINCT.
        // No trailing newline, unlike the SUFFIX form: the default rowLimited appends a clause to a
        // text block and re-adds the newline it consumed. A prefix rewrite consumes nothing, so
        // adding one would be inventing whitespace inside the caller's statement.
        assertEquals("select distinct TOP 1 a from t",
                SqlServerDialect.INSTANCE.rowLimited("select distinct a from t", 1));

        // A CTE needs the TOP inside its own final select, which no string surgery here can locate.
        // Refusing beats emitting something that parses and caps the wrong thing -- the silent wrong
        // answer this seam exists to prevent.
        UnsupportedOperationException refusal = assertThrows(UnsupportedOperationException.class,
                () -> SqlServerDialect.INSTANCE.rowLimited("with x as (select 1) select * from x", 1));
        assertTrue(refusal.getMessage().contains("must begin with SELECT"), refusal.getMessage());
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
        assertTrue(dialect.listTablesSql().contains("table_name"), dialect.name());
        String columns = dialect.listColumnsSql();
        for (String expected : List.of("column_name", "data_type", "is_nullable", "column_default")) {
            assertTrue(columns.contains(expected), dialect.name() + " missing " + expected + ": " + columns);
        }
        String indexes = dialect.listIndexesSql();
        for (String expected : List.of("index_name", "column_name", "is_unique")) {
            assertTrue(indexes.contains(expected), dialect.name() + " missing " + expected + ": " + indexes);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("a hostile table name cannot break out of an introspection statement -- because none is spliced")
    void introspectionBindsRatherThanSplices(SqlDialect dialect) {
        // The first version of these three methods took (schema, table) and embedded them, escaped.
        // The security-pattern sweep flagged all twelve, and it was right to: escaping-then-splicing
        // works until the day a fourth dialect forgets the escape helper, and a bind parameter
        // cannot forget. In information_schema (and pg_catalog, and sys.*) a table name is compared
        // as a VALUE, so it binds on every engine and there was never a reason to build it in.
        for (String sql : List.of(dialect.listTablesSql(), dialect.listColumnsSql(),
                                  dialect.listIndexesSql())) {
            assertTrue(sql.contains("?"), dialect.name() + ": expected bind parameters in " + sql);
        }
        assertEquals(1, dialect.listTablesSql().chars().filter(c -> c == '?').count(), dialect.name());
        assertEquals(2, dialect.listColumnsSql().chars().filter(c -> c == '?').count(), dialect.name());
        assertEquals(2, dialect.listIndexesSql().chars().filter(c -> c == '?').count(), dialect.name());
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
