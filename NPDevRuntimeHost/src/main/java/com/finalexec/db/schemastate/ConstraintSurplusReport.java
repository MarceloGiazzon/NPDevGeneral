package com.finalexec.db.schemastate;

import java.util.List;

/**
 * S8 Wave 2: the advisory, report-only output of surplus FK/index classification — ADVISORY ONLY,
 * permanently (docs/ACCEPTED_BOUNDARIES.md B3; B8's own "NPDev only drops what it can prove it created"
 * principle, applied to constraints). Deliberately NOT a {@link SchemaDiffItem}/{@link SafetyClass} —
 * keeping this off that vocabulary is what guarantees no existing pass (backfill, conversion-hook claim
 * matching, destructive acknowledgment) can ever treat a surplus finding as something to resolve. There
 * is no drop path here, not even a stub.
 *
 * <p><b>Wired, as of B3.2 (boundaries-2026-08-12 plan):</b> {@code SchemaImpactFacade}/{@code
 * ImpactReportWriter} compute this alongside the ordinary missing-only diff (same {@code CurrentSchema}/
 * {@code DesiredSchema} inputs, zero extra DB round-trips) and {@code ImpactReportText}/{@code
 * ImpactReportJson} render it as a clearly-separated informational section — never affecting {@code
 * ImpactReport.verdict()} and never emitting DDL. Still exactly as advisory as the day this shipped.
 *
 * @param surplus     every live FK/index classified
 *                    {@link ConstraintSurplusClassifier.Classification#FOREIGN} — the only class ever
 *                    reported
 * @param abstentions one entry when the whole desired schema could not be classified at all (X0 rule:
 *                    an explicit reason, never silence and never a default classification) — empty
 *                    whenever classification actually ran
 */
public record ConstraintSurplusReport(List<SurplusConstraint> surplus, List<String> abstentions) {

    /** The no-op report: no manifest, no physical database, nothing to classify. */
    public static final ConstraintSurplusReport EMPTY = new ConstraintSurplusReport(List.of(), List.of());

    public boolean isEmpty() {
        return surplus.isEmpty() && abstentions.isEmpty();
    }
}
