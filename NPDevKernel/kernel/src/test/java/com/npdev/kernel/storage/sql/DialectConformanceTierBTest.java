package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>Tier B of the conformance suite: a connection and a hand-made table.</b> No model, no
 * generation, no Spring Boot, no app -- the shape all 16 existing Postgres adapter tests already
 * use. Seconds, not minutes, and no Docker.
 *
 * <h2>The one rule</h2>
 *
 * <p><b>Assert on OBSERVABLE BEHAVIOUR, never on generated SQL text.</b> A test asserting the string
 * {@code ON CONFLICT} passes only for Postgres and must be rewritten for every engine -- which is the
 * duplication the whole dialect layer exists to remove. Assert that upserting twice leaves ONE row;
 * every dialect can satisfy that in its own spelling. (Tier A is where text is asserted, deliberately
 * and separately.)
 *
 * <h2>What a green run here does and does not mean</h2>
 *
 * <p>Locally these run against H2 in each engine's compatibility MODE. That catches syntax and shape.
 * It does not prove the real engine behaves the same, and this codebase already learned that with
 * H2-in-PostgreSQL-mode (REG-36, REG-50). Vectors whose behaviour H2 cannot be trusted for are
 * SKIPPED WITH A REASON rather than passed -- see {@link DialectTestSupport#enforces}. A skip is
 * visible in the runner; a false pass is not.
 */
@DisplayName("Conformance Tier B -- behaviour against a real connection")
class DialectConformanceTierBTest {

    /**
     * Every registered dialect, on every backend.
     *
     * <p>The local matrix is NOT narrowed here. Narrowing it by engine would be the coarse answer,
     * and it would be wrong in both directions: H2 in {@code MODE=MySQL} really can run MySQL's
     * upsert, while H2 in {@code MODE=PostgreSQL} really cannot run Postgres's. What a local backend
     * can do varies by CONSTRUCT, not by engine, so each vector asks
     * {@link DialectTestSupport#canExecuteLocally} for the construct it actually needs and records a
     * SKIP WITH A REASON when the answer is no.
     *
     * <p>A skip is visible in the runner and names what did not run. A narrowed matrix is invisible.
     */
    static Stream<SqlDialect> locallyRunnableDialects() {
        // Filters on the BACKEND (container mode cannot serve a dialect with no image -- h2) and on
        // -Dnpdev.dialect.only (CI scopes each job to one engine). Neither filter can hide a real
        // failure: a dialect that is filtered out produces NO test cases at all rather than passing
        // ones, so it cannot show up as a green tick for something that never ran.
        return SqlDialects.all().stream().filter(DialectTestSupport::shouldRun);
    }

    private static void requireRunnable(SqlDialect dialect, DialectTestSupport.Construct construct) {
        assumeTrue(DialectTestSupport.canExecuteLocally(dialect, construct),
                DialectTestSupport.whyNotRunnable(dialect, construct));
    }

    // ------------------------------------------------------------------ U1

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("U1: upserting the same key twice leaves exactly ONE row, with the second value")
    void upsertTwiceYieldsOneRow(SqlDialect dialect) throws SQLException {
        requireRunnable(dialect, DialectTestSupport.Construct.UPSERT);
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            execute(connection, "CREATE TABLE t (id VARCHAR(36) PRIMARY KEY, v INT NOT NULL)");

            String sql = dialect.upsert().statementFor("t", List.of("id"), List.of("id", "v"));
            upsert(connection, sql, "A", 1);
            upsert(connection, sql, "A", 2);

            assertEquals(1, count(connection, "SELECT COUNT(*) FROM t"),
                    dialect.name() + ": upserting one key twice must leave one row");
            assertEquals(2, count(connection, "SELECT v FROM t WHERE id = 'A'"),
                    dialect.name() + ": the second upsert must win");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("U1: two DIFFERENT keys produce two rows -- the upsert is not collapsing everything")
    void upsertDistinctKeysYieldsTwoRows(SqlDialect dialect) throws SQLException {
        // Without this, an upsert that matched every row would pass the test above perfectly.
        requireRunnable(dialect, DialectTestSupport.Construct.UPSERT);
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            execute(connection, "CREATE TABLE t (id VARCHAR(36) PRIMARY KEY, v INT NOT NULL)");
            String sql = dialect.upsert().statementFor("t", List.of("id"), List.of("id", "v"));
            upsert(connection, sql, "A", 1);
            upsert(connection, sql, "B", 2);
            assertEquals(2, count(connection, "SELECT COUNT(*) FROM t"), dialect.name());
        }
    }

    // ------------------------------------------------------------------ P1/P2/P3

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("P1: limit returns exactly N rows")
    void limitReturnsExactlyN(SqlDialect dialect) throws SQLException {
        requireRunnable(dialect, DialectTestSupport.Construct.PAGINATION);
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            seedTenRows(connection);
            String sql = dialect.paginated("SELECT n FROM t ORDER BY n\n");
            assertEquals(3, readPage(connection, sql, dialect, 3, 0).size(), dialect.name());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("P2: offset skips, and consecutive pages neither overlap nor gap")
    void pagesDoNotOverlapOrGap(SqlDialect dialect) throws SQLException {
        requireRunnable(dialect, DialectTestSupport.Construct.PAGINATION);
        // Off-by-one here is SILENT: it shows up as missing records in a UI, never as an error. It is
        // also exactly what a reversed (limit, offset) binding produces on SQL Server, which is why
        // this vector matters more than its simplicity suggests.
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            seedTenRows(connection);
            String sql = dialect.paginated("SELECT n FROM t ORDER BY n\n");
            List<Integer> first = readPage(connection, sql, dialect, 3, 0);
            List<Integer> second = readPage(connection, sql, dialect, 3, 3);

            assertEquals(List.of(0, 1, 2), first, dialect.name() + ": first page");
            assertEquals(List.of(3, 4, 5), second, dialect.name() + ": second page");
            Set<Integer> union = new LinkedHashSet<>(first);
            union.addAll(second);
            assertEquals(6, union.size(), dialect.name() + ": pages overlapped -- " + first + " " + second);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("P2: an offset past the end returns nothing rather than wrapping")
    void offsetPastTheEndReturnsNothing(SqlDialect dialect) throws SQLException {
        requireRunnable(dialect, DialectTestSupport.Construct.PAGINATION);
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            seedTenRows(connection);
            String sql = dialect.paginated("SELECT n FROM t ORDER BY n\n");
            assertTrue(readPage(connection, sql, dialect, 3, 100).isEmpty(), dialect.name());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("P1: limitOnly caps without an offset")
    void limitOnlyCaps(SqlDialect dialect) throws SQLException {
        requireRunnable(dialect, DialectTestSupport.Construct.PAGINATION);
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            seedTenRows(connection);
            String sql = dialect.limited("SELECT n FROM t ORDER BY n\n");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (int value : dialect.limitOnly().values(4, 0)) {
                    statement.setInt(index++, value);
                }
                assertEquals(4, drain(statement).size(), dialect.name());
            }
        }
    }

    // ------------------------------------------------------------------ J1/J2

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("J1: a nested JSON value round-trips structurally")
    void jsonRoundTrips(SqlDialect dialect) throws SQLException {
        requireRunnable(dialect, DialectTestSupport.Construct.BASIC_DML);
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            // Stored as text on purpose: this vector is about the VALUE surviving, and asserting on
            // Postgres jsonb's own normalisation (it reorders keys and drops whitespace) would be
            // asserting on the engine rather than on the platform.
            //
            // The type comes from the DIALECT even so -- see textColumn(). This vector was passing
            // by LUCK: its document is ASCII, so SQL Server's non-Unicode VARCHAR never had to
            // represent a character it cannot, and only J2 (which uses an emoji) failed. Fixing only
            // the vector that happened to fail would leave the next person who adds an accent here
            // with the same failure and no explanation attached to it.
            execute(connection, "CREATE TABLE t (id VARCHAR(36) PRIMARY KEY, doc " + textColumn(dialect) + ")");
            String document = "{\"a\":{\"b\":[1,2,3]},\"c\":\"x\"}";
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO t (id, doc) VALUES (?, ?)")) {
                insert.setString(1, "A");
                insert.setString(2, document);
                insert.executeUpdate();
            }
            assertEquals(document, readString(connection, "SELECT doc FROM t WHERE id = 'A'"), dialect.name());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("J2: quotes and non-BMP unicode survive")
    void jsonWithQuotesAndUnicodeSurvives(SqlDialect dialect) throws SQLException {
        assumeTrue(DialectTestSupport.enforces(dialect, DialectTestSupport.Behaviour.CHARSET_FIDELITY),
                DialectTestSupport.whyNotVerified(dialect, DialectTestSupport.Behaviour.CHARSET_FIDELITY));
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            execute(connection, "CREATE TABLE t (id VARCHAR(36) PRIMARY KEY, doc " + textColumn(dialect) + ")");
            // The emoji is the point. MySQL's legacy three-byte "utf8" cannot represent it and
            // truncates or replaces SILENTLY -- the insert succeeds and the data is already wrong.
            // SQL Server's non-Unicode VARCHAR does the same thing, which is what run 31264977219
            // caught: this vector stored "cafe [U+2615]" and read back "cafe ?".
            String document = "{\"a\":\"say \\\"hi\\\"\",\"b\":\"café ☕\",\"c\":\"🚀\"}";
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO t (id, doc) VALUES (?, ?)")) {
                insert.setString(1, "A");
                insert.setString(2, document);
                insert.executeUpdate();
            }
            assertEquals(document, readString(connection, "SELECT doc FROM t WHERE id = 'A'"), dialect.name());
        }
    }

    // ------------------------------------------------------------------ Q1

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("Q1: a column literally named 'order' can be written and read")
    void reservedWordColumnWorks(SqlDialect dialect) throws SQLException {
        // A user WILL name a field `order` or `group`. This is the vector that proves quoteIdentifier
        // is not merely well-formed but actually accepted by the engine.
        requireRunnable(dialect, DialectTestSupport.Construct.IDENTIFIER_QUOTING);
        assumeTrue(DialectTestSupport.enforces(dialect, DialectTestSupport.Behaviour.IDENTIFIER_QUOTING),
                DialectTestSupport.whyNotVerified(dialect, DialectTestSupport.Behaviour.IDENTIFIER_QUOTING));
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            String column = dialect.quoteIdentifier("order");
            execute(connection, "CREATE TABLE t (id VARCHAR(36) PRIMARY KEY, " + column + " INT NOT NULL)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t (id, " + column + ") VALUES (?, ?)")) {
                insert.setString(1, "A");
                insert.setInt(2, 7);
                insert.executeUpdate();
            }
            assertEquals(7, count(connection, "SELECT " + column + " FROM t WHERE id = 'A'"), dialect.name());
        }
    }

    // ------------------------------------------------------------------ T1/T2

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("T1: a rolled-back DML transaction persists nothing")
    void dmlRollsBack(SqlDialect dialect) throws SQLException {
        requireRunnable(dialect, DialectTestSupport.Construct.TRANSACTIONS);
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            execute(connection, "CREATE TABLE t (id VARCHAR(36) PRIMARY KEY)");
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO t (id) VALUES ('A')");
            connection.rollback();
            connection.setAutoCommit(true);
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM t"), dialect.name());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("T2: DDL in a transaction behaves as the dialect DECLARES -- do not assume")
    void ddlTransactionalityMatchesTheDeclaration(SqlDialect dialect) throws SQLException {
        // THE vector that gates whether MySQL can be called safe. A migration that half-applies and
        // reports success is the worst failure this system can produce, so the declaration must be
        // red before it is trusted -- not green afterwards.
        assumeTrue(DialectTestSupport.enforces(dialect, DialectTestSupport.Behaviour.DDL_TRANSACTIONALITY),
                DialectTestSupport.whyNotVerified(dialect, DialectTestSupport.Behaviour.DDL_TRANSACTIONALITY));
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            connection.setAutoCommit(false);
            execute(connection, "CREATE TABLE rollback_probe (id INT)");
            connection.rollback();
            connection.setAutoCommit(true);
            boolean survived = tableExists(connection, "rollback_probe");
            assertEquals(!dialect.supports(StorageCapability.DDL_IN_TRANSACTION), survived,
                    dialect.name() + " declares DDL_IN_TRANSACTION="
                    + dialect.supports(StorageCapability.DDL_IN_TRANSACTION)
                    + " but the table " + (survived ? "SURVIVED" : "did not survive")
                    + " a rollback. The declaration and the engine disagree, and the declaration is "
                    + "what the schema engine trusts.");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("T3: a HALF-APPLIED two-step migration leaves exactly what the dialect predicts")
    void halfAppliedMigrationMatchesTheDeclaration(SqlDialect dialect) throws SQLException {
        // storage/FULL_SUPPORT_PLAN.md W3, ranked FIRST among the behavioural work because it is the
        // only item that CORRUPTS instead of failing loudly.
        //
        // T2 above proves the DECLARATION matches the engine for one statement. This is the shape a
        // real migration actually has, and the one that decides an operator's next action: two DDL
        // steps in one transaction where the SECOND must fail. On Postgres/SQL Server neither column
        // survives and re-running is correct. On MySQL/H2 the first column is ALREADY PERMANENT, and
        // an operator who re-runs after reading "the migration failed" is re-running against a schema
        // that already moved -- which is STOR-2's false all-clear, one layer down.
        //
        // The assertion is deliberately not "the migration failed". It is: what the catalog CONTAINS
        // afterwards matches what DDL_IN_TRANSACTION says it should. A dialect that declares wrongly
        // fails here, on the real engine, in CI.
        assumeTrue(DialectTestSupport.enforces(dialect, DialectTestSupport.Behaviour.DDL_TRANSACTIONALITY),
                DialectTestSupport.whyNotVerified(dialect, DialectTestSupport.Behaviour.DDL_TRANSACTIONALITY));
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            // v1: a table WITH ROWS -- the state that makes a migration failure matter at all.
            execute(connection, "CREATE TABLE halfapply (id VARCHAR(36) PRIMARY KEY)");
            execute(connection, "INSERT INTO halfapply (id) VALUES ('A')");

            connection.setAutoCommit(false);
            execute(connection, "ALTER TABLE halfapply ADD step_one INT");
            boolean stepTwoFailed = false;
            try {
                // STEP 2 RE-ADDS STEP 1'S COLUMN, and the reason is a finding in its own right.
                //
                // The obvious step 2 -- "a NOT NULL column with no default, on a table with rows" --
                // is what storage/FULL_SUPPORT_PLAN.md §5 proposed, on the stated assumption that it
                // "must fail everywhere". Measured against a real MySQL 8.4 in CI run 31270440804:
                // IT SUCCEEDS. MySQL fills the type's implicit default (0 for INT) instead of
                // refusing, where Postgres and SQL Server reject the statement outright.
                //
                // A version of this vector without the guard below would have gone GREEN on MySQL
                // having measured nothing at all: no failure means no half-application to observe,
                // and the catalog assertions would then have been checking an ordinary successful
                // migration. That is the exact "a fix that silently does nothing" shape this plan
                // warns about, arriving in the test rather than in the fix.
                //
                // A duplicate column name fails on all four engines, needs no data-dependent
                // behaviour, and -- usefully -- can only fail if step 1 really executed.
                execute(connection, "ALTER TABLE halfapply ADD step_one INT");
            } catch (SQLException expected) {
                stepTwoFailed = true;
            }
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // An engine that already committed may have nothing to roll back. That is the
                // behaviour under measurement, not an error -- the catalog read below is the verdict.
            }
            connection.setAutoCommit(true);

            // THE GUARD THAT EARNED ITS KEEP. It is what turned "MySQL accepts a NOT NULL column
            // with no default" from a silent green into a named finding.
            assertTrue(stepTwoFailed,
                    dialect.name() + ": step 2 was expected to FAIL on every engine. It did not, so "
                    + "there was no half-application to observe and this vector measured NOTHING -- "
                    + "the probe itself needs fixing before its result means anything.");

            Set<String> columns = columnsOf(connection, dialect, "halfapply");
            boolean transactional = dialect.supports(StorageCapability.DDL_IN_TRANSACTION);
            assertEquals(!transactional, columns.contains("step_one"),
                    dialect.name() + " declares DDL_IN_TRANSACTION=" + transactional + ", so step 1 "
                    + (transactional ? "must NOT have survived" : "MUST have survived (implicit commit)")
                    + " the failure of step 2 -- but the catalog reports columns " + columns + ". "
                    + "The engine and the declaration disagree, and the declaration is what the schema "
                    + "engine's failure message trusts when it tells an operator what persisted.");
            assertTrue(!columns.contains("step_two"),
                    dialect.name() + ": step 2 FAILED, so its column must be absent on every engine. "
                    + "Catalog reports " + columns);
        }
    }

    /**
     * The columns of {@code table}, read through the DIALECT's own introspection SQL.
     *
     * <p>Not {@code DatabaseMetaData}: the point of the vector above is whether the dialect's answer
     * about this engine is true, and reading the catalog through a JDBC abstraction would be asking a
     * different question. This is also conformance I2's statement, exercised incidentally.
     */
    private static Set<String> columnsOf(Connection connection, SqlDialect dialect, String table)
            throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(dialect.listColumnsSql())) {
            statement.setString(1, null);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString("column_name").toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return columns;
    }

    // ------------------------------------------------------------------ A1

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("A1: an auto-increment column produces distinct, increasing keys")
    void autoIncrementProducesIncreasingKeys(SqlDialect dialect) throws SQLException {
        requireRunnable(dialect, DialectTestSupport.Construct.AUTO_INCREMENT);
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            execute(connection, "CREATE TABLE t (id " + dialect.autoIncrementColumn(SqlType.INT)
                    + " PRIMARY KEY, v INT NOT NULL)");
            for (int i = 0; i < 3; i++) {
                execute(connection, "INSERT INTO t (v) VALUES (" + i + ")");
            }
            List<Integer> keys = readAll(connection, "SELECT id FROM t ORDER BY id");
            assertEquals(3, new LinkedHashSet<>(keys).size(), dialect.name() + ": keys not distinct " + keys);
            for (int i = 1; i < keys.size(); i++) {
                assertTrue(keys.get(i) > keys.get(i - 1), dialect.name() + ": not increasing " + keys);
            }
        }
    }

    // ------------------------------------------------------------------ C1 (uniqueness)

    @ParameterizedTest(name = "{0}")
    @MethodSource("locallyRunnableDialects")
    @DisplayName("UNIQUE_CONSTRAINTS is declared, so a duplicate must actually be rejected")
    void declaredUniquenessIsEnforced(SqlDialect dialect) throws SQLException {
        // C2 in miniature: a declared capability that does not work is worse than an absent one,
        // because the generator accepts models against it.
        assumeTrue(dialect.supports(StorageCapability.UNIQUE_CONSTRAINTS));
        requireRunnable(dialect, DialectTestSupport.Construct.UNIQUE_CONSTRAINT);
        assumeTrue(DialectTestSupport.enforces(dialect, DialectTestSupport.Behaviour.UNIQUENESS),
                DialectTestSupport.whyNotVerified(dialect, DialectTestSupport.Behaviour.UNIQUENESS));
        try (Connection connection = DialectTestSupport.connectionFor(dialect)) {
            execute(connection, "CREATE TABLE t (id VARCHAR(36) PRIMARY KEY, email VARCHAR(100) UNIQUE)");
            execute(connection, "INSERT INTO t (id, email) VALUES ('A', 'x@example.com')");
            boolean rejected = false;
            try {
                execute(connection, "INSERT INTO t (id, email) VALUES ('B', 'x@example.com')");
            } catch (SQLException expected) {
                rejected = true;
            }
            assertTrue(rejected, dialect.name() + " declares UNIQUE_CONSTRAINTS but accepted a duplicate");
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A text column type from the DIALECT, for any column whose CONTENT this suite asserts.
     *
     * <p><b>The rule, and the reason it is a rule.</b> A conformance vector that hand-writes its DDL
     * is testing its own SQL rather than the dialect's -- exactly the trap {@code PLAN.md} §6 named
     * for probe apps, which Tier B then walked into. Run 31264977219 is the proof: J2 declared
     * {@code VARCHAR(4000)}, and SQL Server's {@code VARCHAR} is NON-UNICODE, so
     * {@code "cafe \u2615"} came back as {@code "cafe ?"}. Silent, per-character data loss.
     * {@code SqlServerDialect.portableColumnType} already answered {@code NVARCHAR(4000)} and was
     * never asked.
     *
     * <p><b>Why {@code portableColumnType} and not {@code jsonColumnType} -- this is the whole
     * fix.</b> {@code jsonColumnType()} is the obvious-looking choice and it is wrong here: on
     * Postgres it yields {@code jsonb}, which REORDERS KEYS and DROPS WHITESPACE, so J1's exact-text
     * assertion would start failing on Postgres. It would trade one red for another.
     * {@code portableColumnType("VARCHAR(4000)")} returns the declaration UNCHANGED on Postgres,
     * MySQL and H2, and {@code NVARCHAR(4000)} on SQL Server -- the only one of the two that fixes
     * J2 without touching J1's meaning.
     *
     * <h4>The audit of the other eight hand-written CREATE TABLEs</h4>
     *
     * All ten were reviewed against one question: <b>does the vector assert this column's content
     * fidelity?</b> Only J1 and J2 do, and both now use this helper. The rest keep their literal
     * types deliberately, because the rule is about what a vector ASSERTS, not about purity:
     *
     * <ul>
     *   <li>{@code id VARCHAR(36)} (U1 x2, Q1, T1, uniqueness) -- an ASCII key, only ever compared
     *       for equality against a value this file wrote. No engine mangles {@code 'A'}.</li>
     *   <li>{@code v INT}, {@code n INT}, {@code rollback_probe(id INT)} -- integers; the assertions
     *       are counts, ordering and rollback, none of which is a text-encoding question.</li>
     *   <li>{@code email VARCHAR(100) UNIQUE} -- the assertion is that a DUPLICATE IS REJECTED, not
     *       that the address round-trips. Uniqueness is enforced identically for ASCII in VARCHAR
     *       and NVARCHAR.</li>
     *   <li>A1's key column already asks {@code dialect.autoIncrementColumn(...)} -- it was doing
     *       the right thing before this fix and is the precedent for it.</li>
     * </ul>
     *
     * <p>If a future vector starts asserting content on any of those columns, it must move to this
     * helper first.
     */
    private static String textColumn(SqlDialect dialect) {
        return dialect.portableColumnType("VARCHAR(4000)");
    }

    private static void seedTenRows(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE t (n INT PRIMARY KEY)");
        for (int i = 0; i < 10; i++) {
            execute(connection, "INSERT INTO t (n) VALUES (" + i + ")");
        }
    }

    private static List<Integer> readPage(Connection connection, String sql, SqlDialect dialect,
                                          int limit, int offset) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            // Bound through the dialect's DECLARED order -- the whole point. A hardcoded
            // (limit, offset) here would pass on three engines and quietly page wrongly on the fourth.
            for (int value : dialect.limitOffset().values(limit, offset)) {
                statement.setInt(index++, value);
            }
            return drain(statement);
        }
    }

    private static List<Integer> drain(PreparedStatement statement) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                out.add(rows.getInt(1));
            }
        }
        return out;
    }

    private static void upsert(Connection connection, String sql, String id, int value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setInt(2, value);
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private static String readString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static List<Integer> readAll(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<Integer> out = new ArrayList<>();
            while (rows.next()) {
                out.add(rows.getInt(1));
            }
            return out;
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(
                null, null, table.toUpperCase(java.util.Locale.ROOT), null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = connection.getMetaData().getTables(null, null, table, null)) {
            return tables.next();
        }
    }
}
