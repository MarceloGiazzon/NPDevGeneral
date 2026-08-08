package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier A for STOR-5's three guarded idioms: no database, no Docker, milliseconds.
 *
 * <h2>Why this suite is worth more than its size</h2>
 *
 * <p>Every defect in this family cost a ~12-minute CI round to find, because Flyway stops at the
 * FIRST statement it cannot execute: fix one, push, wait, learn the next. Three rounds bought three
 * facts. These assertions buy the same facts in milliseconds, and they are the reason the next one
 * fails on a laptop instead of on someone else's push.
 *
 * <p>The assertions are deliberately about SHAPE, not about exact text -- except for
 * PostgreSQL and H2, where exact text IS the requirement: extracting these idioms out of the emitter
 * must not change one byte of what those engines were already getting.
 */
@DisplayName("Guarded DDL -- the three idioms MySQL and SQL Server do not share (STOR-5)")
class GuardedDdlConformanceTest {

    static Stream<SqlDialect> dialects() {
        return SqlDialects.all().stream();
    }

    private static final String CREATE_TABLE = "CREATE TABLE npdev_flow_instance (\n  id VARCHAR(36)\n)";
    private static final String CREATE_INDEX =
            "CREATE INDEX idx_npdev_flow_instance_status ON npdev_flow_instance (status)";
    private static final String CREATE_UNIQUE_INDEX =
            "CREATE UNIQUE INDEX ux_patients_mrn ON patients (tenant_id, mrn)";
    private static final String ADD_COLUMN =
            "ALTER TABLE npdev_flow_instance ADD COLUMN execution_id VARCHAR(255)";

    // ------------------------------------------------------------------ the property that matters

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("every engine can guard all three idioms -- none of them throws")
    void everyEngineGuardsAllThree(SqlDialect dialect) {
        // The whole point of STOR-5: NPDev's own first migration must be expressible on every engine
        // it offers. Before this, two of the four could not run it at all.
        assertTrue(!dialect.guardedCreateTable("npdev_flow_instance", CREATE_TABLE).isBlank());
        assertTrue(!dialect.guardedCreateIndex("idx_npdev_flow_instance_status",
                "npdev_flow_instance", CREATE_INDEX).isBlank());
        assertTrue(!dialect.guardedAddColumn("npdev_flow_instance", "execution_id", ADD_COLUMN)
                .isBlank());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("no guarded statement leaks an idiom the engine cannot parse")
    void noGuardLeaksAnUnsupportedIdiom(SqlDialect dialect) {
        // The assertion the portability linter makes against the emitted script, made here against
        // the dialect itself -- so a dialect that regresses is caught before anything is generated.
        String name = dialect.name();
        for (String guarded : new String[] {
                dialect.guardedCreateIndex("i", "t", CREATE_INDEX),
                dialect.guardedAddColumn("t", "c", ADD_COLUMN)}) {
            String upper = guarded.toUpperCase(Locale.ROOT);
            if ("mysql".equals(name) || "sqlserver".equals(name)) {
                assertTrue(!upper.contains("INDEX IF NOT EXISTS"),
                        name + " cannot parse `INDEX IF NOT EXISTS`: " + guarded);
                assertTrue(!upper.contains("ADD COLUMN IF NOT EXISTS"),
                        name + " cannot parse `ADD COLUMN IF NOT EXISTS`: " + guarded);
            }
        }
        if ("sqlserver".equals(name)) {
            assertTrue(!dialect.guardedCreateTable("t", CREATE_TABLE).toUpperCase(Locale.ROOT)
                            .contains("CREATE TABLE IF NOT EXISTS"),
                    "T-SQL has no CREATE TABLE IF NOT EXISTS");
            // The quieter one underneath: T-SQL is `ALTER TABLE t ADD c TYPE`, no COLUMN keyword.
            assertTrue(!dialect.guardedAddColumn("t", "c", ADD_COLUMN).toUpperCase(Locale.ROOT)
                            .contains("ADD COLUMN"),
                    "T-SQL has no COLUMN keyword in ALTER TABLE ... ADD");
        }
    }

    // ------------------------------------------------------------------ no drift on the natives

    @Test
    @DisplayName("PostgreSQL and H2 output is byte-identical to the inline form it replaced")
    void nativeEnginesAreUnchanged() {
        // THE REGRESSION GUARD for the extraction. If this changes, every existing Postgres/H2
        // database's migration checksum changes with it -- and a repeatable migration whose text
        // moved re-runs on every boot.
        for (SqlDialect dialect : new SqlDialect[] {PostgresDialect.INSTANCE, H2Dialect.INSTANCE}) {
            assertEquals("CREATE TABLE IF NOT EXISTS npdev_flow_instance (\n  id VARCHAR(36)\n)",
                    dialect.guardedCreateTable("npdev_flow_instance", CREATE_TABLE), dialect.name());
            assertEquals("CREATE INDEX IF NOT EXISTS idx_npdev_flow_instance_status "
                            + "ON npdev_flow_instance (status)",
                    dialect.guardedCreateIndex("idx_npdev_flow_instance_status",
                            "npdev_flow_instance", CREATE_INDEX), dialect.name());
            assertEquals("CREATE UNIQUE INDEX IF NOT EXISTS ux_patients_mrn "
                            + "ON patients (tenant_id, mrn)",
                    dialect.guardedCreateIndex("ux_patients_mrn", "patients", CREATE_UNIQUE_INDEX),
                    dialect.name() + ": UNIQUE must be handled too -- guarding only the plain form "
                    + "is the half-fix that looks complete");
            assertEquals("ALTER TABLE npdev_flow_instance ADD COLUMN IF NOT EXISTS "
                            + "execution_id VARCHAR(255)",
                    dialect.guardedAddColumn("npdev_flow_instance", "execution_id", ADD_COLUMN),
                    dialect.name());
        }
    }

    // ------------------------------------------------------------------ idempotence

    @ParameterizedTest(name = "{0}")
    @MethodSource("dialects")
    @DisplayName("guarding twice is the same as guarding once")
    void guardingIsIdempotent(SqlDialect dialect) {
        // The additive script is a Flyway REPEATABLE migration: it re-runs whenever its checksum
        // changes, so a doubled `IF NOT EXISTS IF NOT EXISTS` would fail the boot -- and only on the
        // second boot, which is the worst time to find out (REG-38's shape).
        String once = dialect.guardedCreateTable("t", CREATE_TABLE);
        assertEquals(once, dialect.guardedCreateTable("t", once), dialect.name());
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("a statement of an unexpected shape is REFUSED, not returned unguarded")
    void unexpectedShapeIsRefused() {
        // Returning it unchanged would put an UNGUARDED CREATE TABLE into a repeatable migration --
        // fine on the first boot, fatal on the second. A generation-time throw is strictly better
        // than a runtime failure nobody can trace back here.
        assertThrows(IllegalArgumentException.class,
                () -> PostgresDialect.INSTANCE.guardedCreateTable("t", "SELECT 1"));
        assertThrows(IllegalArgumentException.class,
                () -> PostgresDialect.INSTANCE.guardedCreateIndex("i", "t", "SELECT 1"));
    }

    @Test
    @DisplayName("MySQL's guards are idempotent through a catalog lookup, not a keyword")
    void mysqlUsesACatalogLookup() {
        String guarded = MySqlDialect.INSTANCE.guardedCreateIndex(
                "idx_x", "t", CREATE_INDEX);
        assertTrue(guarded.contains("INFORMATION_SCHEMA.STATISTICS"), guarded);
        assertTrue(guarded.contains("PREPARE npdev_stmt"), guarded);
        assertTrue(guarded.contains("DEALLOCATE PREPARE npdev_stmt"), guarded);
    }

    @Test
    @DisplayName("SQL Server's guards are plain IF blocks -- no dynamic SQL")
    void sqlServerUsesIfBlocks() {
        assertTrue(SqlServerDialect.INSTANCE.guardedCreateTable("t", CREATE_TABLE)
                .startsWith("IF OBJECT_ID("));
        assertTrue(SqlServerDialect.INSTANCE.guardedCreateIndex("i", "t", CREATE_INDEX)
                .contains("sys.indexes"));
        assertTrue(SqlServerDialect.INSTANCE.guardedAddColumn("t", "c", ADD_COLUMN)
                .startsWith("IF COL_LENGTH("));
    }

    @Test
    @DisplayName("an identifier carrying a quote cannot break out of a guard's literal")
    void identifiersAreEscaped() {
        // Not reachable from a model today (identifiers are sanitised upstream), and asserted anyway:
        // the guard builds SQL by concatenation, and every such site in this repo that skipped this
        // question eventually needed it.
        String mysql = MySqlDialect.INSTANCE.guardedCreateIndex("it's", "t", CREATE_INDEX);
        assertTrue(mysql.contains("it''s"), mysql);
        String sqlserver = SqlServerDialect.INSTANCE.guardedCreateIndex("it's", "t", CREATE_INDEX);
        assertTrue(sqlserver.contains("it''s"), sqlserver);
    }
}
