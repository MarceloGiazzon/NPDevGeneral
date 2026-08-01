package com.npdev.generator.schemaevolution;

import java.util.ArrayList;
import java.util.List;

/**
 * REG-102 fix (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 1.2, LC-C1/AC-3): the coarse, AI-authoring-
 * facing classification {@code npdev migration diff} / MCP {@code npdev_migration_diff} advertise
 * (SAFE_ADDITIVE / BACKFILL_REQUIRED / MANUAL_REVIEW), plus METADATA_ONLY (LC-C1's own addition --
 * the diff touches no concept, field, index, or anything else feeding the schema fingerprint).
 *
 * <p><b>Deliberately NOT a new diff engine.</b> {@link MigrationPlanEmitter#compute} already IS the
 * offline, pure, no-database, model-vs-model diff (see its own class javadoc: "(A) share, not (B)
 * duplicate"). This class only maps its {@link PlanItem.Kind} vocabulary down to the coarser
 * 4-level one the CLI/MCP surface advertises -- one diff engine, one grammar, two views of it.
 *
 * <p>{@code METADATA_ONLY} is definitionally exact, not approximated: {@link MigrationPlanEmitter}
 * diffs exactly the concept/field shape that feeds
 * {@code UserDatabaseDefinitionLoader#fingerprintInputs}'s business-table lines, so an empty item
 * list (on a non-fresh-install) means the schema fingerprint provably did not move -- the same
 * property {@code RuntimeMetadataService} already documents ("covers table/column/type/required/
 * unique shape only ... will NOT change for a panel/permission/flow edit").
 */
public final class ModelChangeClassifier {

    private ModelChangeClassifier() {
    }

    /** Ordered least-to-most severe: a numerically higher level from {@link #levelOf} always wins. */
    public enum Level {
        METADATA_ONLY,
        SAFE_ADDITIVE,
        BACKFILL_REQUIRED,
        MANUAL_REVIEW
    }

    public record Classification(Level level, List<String> reasons) {
        public Classification {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public static Classification classify(MigrationPlan plan) {
        if (plan.freshInstall()) {
            // Every table is newly created; no existing data is at risk. Distinct from
            // METADATA_ONLY (MigrationPlan's own javadoc: "a consumer must not confuse" the two --
            // there was no previous model to diff against at all, so "nothing changed" is not a
            // claim this branch can make).
            return new Classification(Level.SAFE_ADDITIVE,
                    List.of("fresh install: no previous model to diff against; every table is newly created"));
        }
        if (plan.items().isEmpty()) {
            return new Classification(Level.METADATA_ONLY,
                    List.of("no concept/field/index change: the compiled schema fingerprint is unchanged"));
        }
        Level worst = Level.SAFE_ADDITIVE;
        List<String> reasons = new ArrayList<>();
        for (PlanItem item : plan.items()) {
            Level itemLevel = levelOf(item);
            reasons.add("[" + itemLevel.name() + "] " + item.description());
            if (itemLevel.ordinal() > worst.ordinal()) {
                worst = itemLevel;
            }
        }
        return new Classification(worst, List.copyOf(reasons));
    }

    private static Level levelOf(PlanItem item) {
        return switch (item.kind()) {
            case ADD_TABLE, ADD_COLUMN, RENAME_TABLE, RENAME_COLUMN, WIDEN_TYPE, ADD_UNIQUE_CONSTRAINT ->
                    Level.SAFE_ADDITIVE;
            case ADD_COLUMN_BACKFILL -> Level.BACKFILL_REQUIRED;
            case DROP_COLUMN, DROP_TABLE, NARROW_TYPE, UNKNOWN -> Level.MANUAL_REVIEW;
        };
    }
}
