package com.finalexec.db;

import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffItem;

/**
 * Reduces a {@link SchemaDiff} to the live executor's coarse {@code SchemaChangeClassification}
 * (schema-engine rebuild, Phase 4). This is the shared bridge that lets {@code classify()} (P4.8) — and,
 * transitively, the additive decision (P4.1) — read the diff instead of re-deriving the classification
 * from raw manifest maps. Pure; wired NOWHERE until it is proven equivalent to the live {@code classify}
 * across the H2 + Postgres proof matrices.
 *
 * <p>The mapping mirrors {@code classify}'s worst-wins aggregation over per-column outcomes. The one
 * non-obvious case is {@code NEEDS_HOOK}: the diff collapses "required non-bond column with no literal
 * default" (which {@code classify} treats as additive-eligible → {@code SAFE_ADDITIVE}, refused only
 * later by the backfill pass) and "required bond column" (which {@code classify} cannot add → falls to
 * {@code DESTRUCTIVE}). The {@link DesiredSchema}'s per-column {@code bond} flag disambiguates them.
 */
public final class ClassificationReducer {

    private ClassificationReducer() {
    }

    public static SchemaLifecycleExecutor.SchemaChangeClassification reduce(SchemaDiff diff, DesiredSchema desired) {
        // Worst-wins over per-item contributions, exactly like classify()'s worse() aggregation. The
        // enum declaration order (SAFE_ADDITIVE < RENAME_DETECTED < TYPE_CHANGE_DETECTED < DESTRUCTIVE)
        // is the severity order, so ordinal() gives the same ranking classify's severity field does.
        SchemaLifecycleExecutor.SchemaChangeClassification worst =
                SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE;
        for (SchemaDiffItem item : diff.items()) {
            SchemaLifecycleExecutor.SchemaChangeClassification contribution = contribution(item, desired);
            if (contribution.ordinal() > worst.ordinal()) {
                worst = contribution;
            }
        }
        return worst;
    }

    private static SchemaLifecycleExecutor.SchemaChangeClassification contribution(
            SchemaDiffItem item, DesiredSchema desired) {
        return switch (item.safetyClass()) {
            // Additive-eligible in classify's terms: a new nullable column, a new table, a required
            // column backfilled from a literal default, a platform tighten. classify ignores pure
            // nullability relaxations entirely (no column added/removed), so SAFE_RELAX contributes
            // nothing worse than the SAFE_ADDITIVE baseline either.
            case SAFE_ADDITIVE, SAFE_RELAX, NEEDS_BACKFILL ->
                    SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE;
            // NEEDS_HOOK collapses two cases classify treats oppositely: a required NON-bond column with
            // no literal default is additive-eligible (SAFE_ADDITIVE; the backfill pass refuses it
            // later), but a required BOND cannot be added at all (DESTRUCTIVE). The desired column's
            // bond flag disambiguates.
            case NEEDS_HOOK -> isBond(item, desired)
                    ? SchemaLifecycleExecutor.SchemaChangeClassification.DESTRUCTIVE
                    : SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE;
            case SAFE_RENAME -> SchemaLifecycleExecutor.SchemaChangeClassification.RENAME_DETECTED;
            // classify flags ANY shared-column type difference as TYPE_CHANGE_DETECTED and defers the
            // narrow-vs-widen destructiveness to attemptInPlaceTypeWidenings' fall-through -- so a
            // narrowing is TYPE_CHANGE_DETECTED at the classify level, not DESTRUCTIVE.
            case SAFE_WIDEN, DESTRUCTIVE_NARROW_TYPE ->
                    SchemaLifecycleExecutor.SchemaChangeClassification.TYPE_CHANGE_DETECTED;
            // A brand-new table is not safe-additive EVIDENCE either way in classify (it hits the
            // `actual.isEmpty() -> continue` guard) -- it leaves the classification at its baseline.
            case SAFE_TABLE_CREATE ->
                    SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE;
            // NB (Phase 4 reconciliation item): DESTRUCTIVE_DROP_TABLE cannot be reduced from the pure
            // schema diff -- classify's handling is ownership-gated (an orphan NPDev cannot prove it
            // created is left alone => SAFE; a proven dropped concept => DESTRUCTIVE). Mapped to
            // DESTRUCTIVE here as the conservative default; the ownership signal must be threaded in
            // before classify is switched to the reducer. Tracked in the classify self-check divergences.
            case DESTRUCTIVE_DROP_COLUMN, DESTRUCTIVE_DROP_TABLE, MANUAL_REVIEW ->
                    SchemaLifecycleExecutor.SchemaChangeClassification.DESTRUCTIVE;
        };
    }

    private static boolean isBond(SchemaDiffItem item, DesiredSchema desired) {
        if (item.column() == null) {
            return false;
        }
        DesiredTable table = desired.tables().get(item.table());
        if (table == null) {
            return false;
        }
        DesiredColumn column = table.columns().get(item.column());
        return column != null && column.bond();
    }
}
