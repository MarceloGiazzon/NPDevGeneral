package com.finalexec.db.schemastate;

/**
 * The safety class of one {@link SchemaDiffItem} — THE central vocabulary of the schema-engine rebuild
 * (Phase 2). It deliberately merges the live executor's classification outcomes with the dead
 * {@code com.finalexec.npdev.migration.MigrationRiskAssessmentBuilder} taxonomy (safe / backfill /
 * manual / breaking), so Phase 9 can retire that lineage without losing its idea. Every diff item
 * carries exactly one of these; the passes (Phase 4) and the Impact Report (Phase 6) act on it.
 */
public enum SafetyClass {
    /** A brand-new table — no existing data at risk (REG-40 as a first-class diff item). */
    SAFE_TABLE_CREATE,
    /** A new nullable column, or an additive constraint on an existing table. */
    SAFE_ADDITIVE,
    /** A column going required → optional (relaxing NOT NULL never loses data). */
    SAFE_RELAX,
    /** A declared {@code renamedFrom} applied in place, preserving data. */
    SAFE_RENAME,
    /** A {@link com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix.Classification#WIDENING} type change. */
    SAFE_WIDEN,
    /** A new required column with a literal default — backfilled automatically. */
    NEEDS_BACKFILL,
    /** A new required column with no literal default (expression-only) — an operator hook must supply the data. */
    NEEDS_HOOK,
    /** Dropping a column — destroys that column's data. */
    DESTRUCTIVE_DROP_COLUMN,
    /** Dropping a table — destroys all its rows. */
    DESTRUCTIVE_DROP_TABLE,
    /** A NARROWING / INCOMPARABLE type change (may truncate or lose data). */
    DESTRUCTIVE_NARROW_TYPE,
    /** The engine cannot decide automatically (e.g. ambiguous default drift) — operator must review. */
    MANUAL_REVIEW
}
