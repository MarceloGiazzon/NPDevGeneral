package com.npdev.kernel.storage.sql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two sentences that decide what an operator does after a failed schema change.
 *
 * <p><b>Why a message deserves a test.</b> STOR-2 was a HIGH-severity bug whose entire content was a
 * sentence: three refusals said "the hook's changes were rolled back; nothing persisted" on engines
 * that commit implicitly on DDL. The failure mode is not the un-rolled-back DDL -- it is the platform
 * telling an operator the database is untouched when it is not, which turns a recoverable
 * half-migration into one nobody goes looking for.
 *
 * <p>A sentence with no test is one refactor away from being wrong again, and wrong in a way that
 * looks exactly like right. So these assert the DISTINCTION rather than the wording: an
 * implicit-commit engine must never produce a message that reads as an all-clear.
 *
 * <p>Tier A: no database, no Docker.
 */
@DisplayName("Partial-application truth -- what a failed schema change actually left behind")
class PartialApplicationTruthTest {

    @Test
    @DisplayName("a transactional engine says the rollback undid everything")
    void transactionalEngineClaimsFullRollback() {
        for (SqlDialect dialect : List.of(PostgresDialect.INSTANCE, SqlServerDialect.INSTANCE)) {
            String message = PartialApplicationTruth.afterRollback(dialect);
            assertTrue(message.contains("nothing persisted"),
                    dialect.name() + " has DDL_IN_TRANSACTION and must say so: " + message);
        }
    }

    @Test
    @DisplayName("an implicit-commit engine NEVER claims nothing persisted")
    void implicitCommitEngineNeverClaimsAnAllClear() {
        // The exact regression STOR-2 fixed. Asserted as an absence, because the defect was an
        // all-clear that happened to be false -- not a missing detail.
        for (SqlDialect dialect : List.of(MySqlDialect.INSTANCE, H2Dialect.INSTANCE)) {
            String message = PartialApplicationTruth.afterRollback(dialect);
            assertTrue(!message.contains("nothing persisted"),
                    dialect.name() + " COMMITS IMPLICITLY ON DDL -- claiming nothing persisted is the "
                    + "false all-clear STOR-2 was filed for: " + message);
            assertTrue(message.contains("ALREADY COMMITTED"),
                    dialect.name() + " must say what actually survived: " + message);
        }
    }

    @Test
    @DisplayName("a failed multi-step pass names the items that are already permanent")
    void multiStepFailureNamesTheSurvivors() {
        List<String> items = List.of(
                "RELAX_NOT_NULL invoice.due_date",
                "RELAX_NOT_NULL invoice.customer_ref",
                "RELAX_NOT_NULL invoice.total");

        String mysql = PartialApplicationTruth.afterFailedMultiStep(
                MySqlDialect.INSTANCE, "RELAX_NOT_NULL", items, 2);
        assertTrue(mysql.contains("HALF APPLIED"), mysql);
        assertTrue(mysql.contains("invoice.due_date") && mysql.contains("invoice.customer_ref"),
                "the two items that already landed must be NAMED -- 'the migration failed' is true "
                + "and useless, because the operator's next action depends entirely on what is now "
                + "permanent: " + mysql);
        assertTrue(mysql.contains("invoice.total"), "the failing item must be named: " + mysql);

        String postgres = PartialApplicationTruth.afterFailedMultiStep(
                PostgresDialect.INSTANCE, "RELAX_NOT_NULL", items, 2);
        assertTrue(postgres.contains("NONE of this pass"), postgres);
        assertTrue(!postgres.contains("HALF APPLIED"), postgres);
    }

    @Test
    @DisplayName("an unknown failure index does not invent a list of survivors")
    void unknownIndexDoesNotGuess() {
        // The one place a helpful guess would be actively dangerous: a specific but wrong list of
        // "already permanent" items, in front of an operator who is about to act on it.
        String message = PartialApplicationTruth.afterFailedMultiStep(
                MySqlDialect.INSTANCE, "RELAX_NOT_NULL",
                List.of("RELAX_NOT_NULL a.b", "RELAX_NOT_NULL c.d"), -1);
        assertTrue(message.contains("(unknown item)"), message);
        assertTrue(message.contains("0 of 2"),
                "with no known failure point, nothing may be reported as already applied: " + message);
    }
}
