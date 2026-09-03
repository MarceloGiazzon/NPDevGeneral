package com.finalexec.db;

import com.npdev.kernel.storage.sql.H2Dialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 (REAL_LIFT_PLAN_2026-09-03, B11 "real lift"): pure classification tests for {@link
 * ConversionHookPhaseSplitter} -- no database, just proving each recognized shape is rewritten (or
 * left as-is) correctly and that an unrecognized DDL shape blocks rather than guesses.
 */
class ConversionHookPhaseSplitterTest {

    @Test
    void addColumnIsRewrittenToItsGuardedIdempotentForm() {
        ConversionHookPhaseSplitter.SplitResult result = ConversionHookPhaseSplitter.split(
                "ALTER TABLE t ADD COLUMN c VARCHAR(20)", H2Dialect.INSTANCE);
        assertTrue(result.isSplittable(), () -> String.valueOf(result.blocked()));
        assertEquals(1, result.phases().size());
        ConversionHookPhaseSplitter.Phase phase = result.phases().get(0);
        assertEquals(ConversionHookPhaseSplitter.PhaseKind.DDL, phase.kind());
        assertTrue(phase.executableSql().toUpperCase(java.util.Locale.ROOT).contains("IF NOT EXISTS"),
                phase.executableSql());
    }

    @Test
    void createTableIsRewrittenToItsGuardedIdempotentForm() {
        ConversionHookPhaseSplitter.SplitResult result = ConversionHookPhaseSplitter.split(
                "CREATE TABLE t (id BIGINT PRIMARY KEY)", H2Dialect.INSTANCE);
        assertTrue(result.isSplittable());
        assertTrue(result.phases().get(0).executableSql().toUpperCase(java.util.Locale.ROOT)
                .contains("IF NOT EXISTS"));
    }

    @Test
    void createIndexIsRewrittenToItsGuardedIdempotentForm() {
        ConversionHookPhaseSplitter.SplitResult result = ConversionHookPhaseSplitter.split(
                "CREATE INDEX ix_t_c ON t (c)", H2Dialect.INSTANCE);
        assertTrue(result.isSplittable());
        assertTrue(result.phases().get(0).executableSql().toUpperCase(java.util.Locale.ROOT)
                .contains("IF NOT EXISTS"));
    }

    @Test
    void setNotNullIsIdempotentByNatureAndNeedsNoRewrite() {
        String statement = "ALTER TABLE t ALTER COLUMN c SET NOT NULL";
        ConversionHookPhaseSplitter.SplitResult result = ConversionHookPhaseSplitter.split(statement, H2Dialect.INSTANCE);
        assertTrue(result.isSplittable());
        assertEquals(statement, result.phases().get(0).executableSql());
    }

    @Test
    void dmlStatementsPassThroughUnchangedAsDmlPhases() {
        ConversionHookPhaseSplitter.SplitResult result = ConversionHookPhaseSplitter.split(
                "UPDATE t SET c = 'x' WHERE c IS NULL", H2Dialect.INSTANCE);
        assertTrue(result.isSplittable());
        ConversionHookPhaseSplitter.Phase phase = result.phases().get(0);
        assertEquals(ConversionHookPhaseSplitter.PhaseKind.DML, phase.kind());
        assertEquals("UPDATE t SET c = 'x' WHERE c IS NULL", phase.executableSql());
    }

    @Test
    void anUnrecognizedDdlShapeBlocksRatherThanGuesses() {
        ConversionHookPhaseSplitter.SplitResult result = ConversionHookPhaseSplitter.split(
                "ALTER TABLE t ADD COLUMN c VARCHAR(20); ALTER TABLE t DROP COLUMN legacy",
                H2Dialect.INSTANCE);
        assertFalse(result.isSplittable());
        assertNotNull(result.blocked());
        assertEquals(1, result.blocked().ordinal());
        assertTrue(result.blocked().statement().contains("DROP COLUMN legacy"), result.blocked().statement());
        assertTrue(result.phases().isEmpty(), "a blocked split must return no phases at all");
    }

    @Test
    void multiStatementHookPreservesOriginalOrder() {
        ConversionHookPhaseSplitter.SplitResult result = ConversionHookPhaseSplitter.split(
                "ALTER TABLE t ADD COLUMN c VARCHAR(20);\n"
                        + "UPDATE t SET c = 'x' WHERE c IS NULL;\n"
                        + "ALTER TABLE t ALTER COLUMN c SET NOT NULL;",
                H2Dialect.INSTANCE);
        assertTrue(result.isSplittable(), () -> String.valueOf(result.blocked()));
        assertEquals(3, result.phases().size());
        assertEquals(ConversionHookPhaseSplitter.PhaseKind.DDL, result.phases().get(0).kind());
        assertEquals(ConversionHookPhaseSplitter.PhaseKind.DML, result.phases().get(1).kind());
        assertEquals(ConversionHookPhaseSplitter.PhaseKind.DDL, result.phases().get(2).kind());
        assertEquals(0, result.phases().get(0).ordinal());
        assertEquals(1, result.phases().get(1).ordinal());
        assertEquals(2, result.phases().get(2).ordinal());
    }

    @Test
    void statementHashIsOverTheOriginalTextNotTheRewrittenGuardedForm() {
        // The rewritten form (with IF NOT EXISTS inserted) differs from the original -- the journal
        // must key on what the AUTHOR wrote, so editing convert.sql is detectable even though the
        // guarded rewrite of the OLD and NEW text could otherwise coincide in unlucky cases.
        String original = "ALTER TABLE t ADD COLUMN c VARCHAR(20)";
        ConversionHookPhaseSplitter.Phase phase =
                ConversionHookPhaseSplitter.split(original, H2Dialect.INSTANCE).phases().get(0);
        assertFalse(phase.executableSql().equals(original), "sanity: the guard must actually rewrite something");
        assertEquals(64, phase.statementHash().length(), "sha256 hex digest length");
    }
}
