package com.finalexec.db.datamobility;

/**
 * The three-way outcome of {@link DataMobilityStructureCheck}: can a target's live schema receive
 * a source's data as-is, before any row is written?
 *
 * <p>Direction-agnostic on purpose -- this enum says nothing about WHICH side (source/target) drove
 * the verdict, only what the caller should do about it. See {@link DataMobilityStructureCheck} for
 * how a {@code SchemaDiffItem}'s {@code SafetyClass} is reduced to one of these three values.
 */
public enum StructureVerdict {
    /** Source and target schemas are structurally identical (modulo the diff engine's own
     *  normalization) -- no differences were found at all. */
    EQUAL,
    /** The schemas differ, but every difference is harmless for writing source data into target:
     *  a surplus table/column on the target, or a target column with strictly more capacity than
     *  the source's. */
    COMPATIBLE,
    /** At least one difference would block or corrupt a write: the target is missing something the
     *  source has (and no DDL was requested to create it), a shared column's target type cannot
     *  hold every value the source type allows, or the target requires a value the source cannot
     *  guarantee. */
    INCOMPATIBLE
}
