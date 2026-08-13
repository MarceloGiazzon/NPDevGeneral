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
            // NEEDS_HOOK collapses two cases classify treats oppositely: a required NON-additive column
            // (a bond, or a manifest-marked non-additive) cannot be added -> classify DESTRUCTIVE; a
            // merely required-with-no-default column that IS additive-eligible is SAFE_ADDITIVE (the
            // backfill pass refuses it later). classify tests businessTableAdditiveColumns membership,
            // so the desired column's additiveEligible flag is the exact disambiguator.
            case NEEDS_HOOK -> isNonAdditiveEligible(item, desired)
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
            // classify is COLUMN-level over the DESIRED tables' columns (it iterates
            // businessTableColumns) -- it never even looks at a live table the model no longer declares,
            // so an orphan / renamed-away table contributes nothing to the classification (its removal is
            // an ownership-gated decision made by a separate pass). A dropped CONCEPT is likewise absent
            // from businessTableColumns, so classify never returns DESTRUCTIVE from a table drop either.
            case DESTRUCTIVE_DROP_TABLE ->
                    SchemaLifecycleExecutor.SchemaChangeClassification.SAFE_ADDITIVE;
            case DESTRUCTIVE_DROP_COLUMN, MANUAL_REVIEW ->
                    SchemaLifecycleExecutor.SchemaChangeClassification.DESTRUCTIVE;
        };
    }

    private static boolean isNonAdditiveEligible(SchemaDiffItem item, DesiredSchema desired) {
        if (item.column() == null) {
            return false;
        }
        DesiredTable table = desired.tables().get(item.table());
        if (table == null) {
            return false;
        }
        DesiredColumn column = table.columns().get(item.column());
        return column != null && !column.additiveEligible();
    }
}
