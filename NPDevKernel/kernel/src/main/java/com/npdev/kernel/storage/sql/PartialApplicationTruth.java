package com.npdev.kernel.storage.sql;

import java.util.List;

/**
 * What a failed schema change actually left behind, in words that are true on THIS engine.
 *
 * <h2>Why this is a class and not a sentence</h2>
 *
 * <p>STOR-2 found three refusal messages saying, verbatim, <i>"the hook's changes were rolled back;
 * nothing persisted"</i> -- false on H2, the engine every NPDev dev app runs on, and false on MySQL,
 * which was about to be added. Both COMMIT IMPLICITLY ON DDL: an {@code ALTER TABLE} ends the
 * transaction the moment it runs, taking any DML issued before it along with it.
 *
 * <p><b>The failure mode is not the un-rolled-back DDL. It is the platform telling an operator the
 * database is untouched when it is not.</b> A false all-clear is what turns a recoverable
 * half-migration into one nobody goes looking for: the operator reads "nothing persisted", fixes the
 * model, and re-runs against a schema that already moved.
 *
 * <p>STOR-2 corrected one call site. That leaves the next one free to make the same mistake, which is
 * this repo's most repeated defect shape. So the sentence lives here, once, derived from
 * {@link StorageCapability#DDL_IN_TRANSACTION} rather than from what the author assumed the engine
 * does -- and {@code scripts/quality/check-rollback-claims.py} fails the knowledge gate when a
 * storage-surface message claims a rollback without going through it.
 *
 * <p><b>This class never decides behaviour.</b> It produces a message. Refusing to run conversion
 * hooks on an implicit-commit engine would break every H2 app that uses them today and is a far
 * larger change than the defect warrants -- so the behaviour is unchanged and the CLAIM is corrected.
 * That is the X0 rule applied to a message rather than to a code path.
 */
public final class PartialApplicationTruth {

    private PartialApplicationTruth() {
    }

    /**
     * What a rollback issued after a failed schema change actually undid, on the ACTIVE engine.
     *
     * <p>The clause is written to drop into an existing sentence in parentheses, which is how
     * {@code ConversionHookRunner}'s three refusals already read.
     */
    public static String afterRollback() {
        return afterRollback(SqlDialects.active());
    }

    /** {@link #afterRollback()} against an explicit dialect -- the testable form. */
    public static String afterRollback(SqlDialect dialect) {
        if (dialect.supports(StorageCapability.DDL_IN_TRANSACTION)) {
            return "the hook's changes were rolled back; nothing persisted";
        }
        // B11.2 (boundaries-2026-08-12 plan): the remedy docs/ACCEPTED_BOUNDARIES.md B11 has always
        // named -- "split destructive DDL and data movement into separate hooks/boots" -- used to live
        // only in that doc and in a pre-run warning an operator could scroll past. It belongs in the
        // sentence an operator actually reads at the moment of failure, not filed away for later.
        return "engine '" + dialect.name() + "' COMMITS IMPLICITLY ON DDL, so the hook's "
                + "schema changes (and any data change made before them) are ALREADY COMMITTED and were "
                + "NOT rolled back -- only data changes made after the last DDL statement were undone. "
                + "Inspect the schema before re-running. Next time, split destructive DDL and data "
                + "movement into separate hooks/boots (or run this conversion on an engine with "
                + "transactional DDL, e.g. Postgres/SQL Server) so a verify failure can't leave the "
                + "schema and data in this state -- see docs/ACCEPTED_BOUNDARIES.md B11";
    }

    /**
     * What a MULTI-STEP schema pass left behind when one of its steps failed.
     *
     * <p>The half-applied migration is the one storage failure that <b>corrupts instead of failing
     * loudly</b>, and it is engine-specific in a way no caller should have to remember:
     *
     * <pre>
     *   Postgres, SQL Server   DDL is transactional -- nothing from this pass survives
     *   MySQL, H2              DDL commits implicitly -- every step BEFORE the failure is PERMANENT
     * </pre>
     *
     * <p>On an implicit-commit engine the correct operator action is the opposite of the intuitive
     * one: <b>do not just fix and re-run</b>, because the database is now in a state neither version
     * of the model describes. So the message names the steps that are known to have landed rather
     * than describing the failure in the abstract.
     *
     * @param stepName    the pass, e.g. {@code RELAX_NOT_NULL}
     * @param items       every item the pass intended to apply, in order
     * @param failedIndex the zero-based index of the item that threw, or {@code -1} if unknown
     */
    public static String afterFailedMultiStep(String stepName, List<String> items, int failedIndex) {
        return afterFailedMultiStep(SqlDialects.active(), stepName, items, failedIndex);
    }

    /** {@link #afterFailedMultiStep(String, List, int)} against an explicit dialect. */
    public static String afterFailedMultiStep(SqlDialect dialect, String stepName,
                                              List<String> items, int failedIndex) {
        List<String> safeItems = items == null ? List.of() : items;
        String failed = failedIndex >= 0 && failedIndex < safeItems.size()
                ? safeItems.get(failedIndex)
                : "(unknown item)";

        if (dialect.supports(StorageCapability.DDL_IN_TRANSACTION)) {
            return "schema pass '" + stepName + "' failed at " + failed + ". Engine '" + dialect.name()
                    + "' rolls DDL back, so NONE of this pass's " + safeItems.size()
                    + " item(s) survive. Fix the cause and re-run.";
        }

        // The dangerous half. Naming the survivors is the whole point: "the migration failed" is true
        // and useless, because the operator's next action depends entirely on what is now permanent.
        List<String> applied = failedIndex > 0
                ? safeItems.subList(0, Math.min(failedIndex, safeItems.size()))
                : List.of();
        return "schema pass '" + stepName + "' failed at " + failed + ". Engine '" + dialect.name()
                + "' COMMITS IMPLICITLY ON DDL, so this pass is HALF APPLIED and cannot be undone by "
                + "re-running: " + applied.size() + " of " + safeItems.size() + " item(s) are ALREADY "
                + "PERMANENT" + (applied.isEmpty() ? "" : " -- " + applied)
                + ". The database is now in a state neither the old nor the new model describes. "
                + "Inspect the schema (and the PARTIAL-CRASH row in npdev_schema_history) before "
                + "doing anything else; do NOT assume a re-run starts from the old schema.";
    }
}
