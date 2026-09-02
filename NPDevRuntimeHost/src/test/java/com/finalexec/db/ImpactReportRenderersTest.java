package com.finalexec.db;

import com.finalexec.db.schemastate.ConstraintSurplusReport;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.finalexec.db.schemastate.SurplusConstraint;
import com.npdev.dsl.v1.schemaevolution.RenameCandidateScorer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic (no-DB) unit test for the two {@link ImpactReport} renderers (P6.2), against a fixed
 * probed fixture: {@link ImpactReportText} (aligned table, {@code !!} destructive prefix, summary footer,
 * token only when destructive) and {@link ImpactReportJson} (schema-shaped, strict escaping, token only
 * when destructive).
 */
class ImpactReportRenderersTest {

    private static ImpactReport fixture() {
        return ImpactReport.ofProbedItems(List.of(
                new ImpactReport.Item(SchemaDiffItem.of("ADD_REQUIRED_COLUMN:widgets:status", "widgets", "status",
                        SafetyClass.NEEDS_BACKFILL, null, "VARCHAR(10)"), 3L, ""),
                new ImpactReport.Item(SchemaDiffItem.of("DROP_COLUMN:widgets:legacy_flag:BOOLEAN", "widgets",
                        "legacy_flag", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null), 2L, "")));
    }

    @Test
    void textRendersAlignedTableWithDestructiveMarkerAndFooter() {
        String text = ImpactReportText.render(fixture(), "sha256:old", "sha256:new", "TOKEN123");
        assertTrue(text.contains("verdict:     DESTRUCTIVE"), text);
        assertTrue(text.contains("!!"), "destructive item must be flagged: " + text);
        assertTrue(text.contains("legacy_flag"), text);
        assertTrue(text.contains("0 safe / 1 attention / 1 destructive"), text);
        assertTrue(text.contains("acknowledgment token: TOKEN123"), text);
    }

    @Test
    void textOmitsTokenWhenNotDestructive() {
        ImpactReport safe = ImpactReport.ofProbedItems(List.of(new ImpactReport.Item(
                SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note", SafetyClass.SAFE_ADDITIVE, null,
                        "VARCHAR(20)"), 0L, "")));
        String text = ImpactReportText.render(safe, "a", "b", "SHOULD_NOT_APPEAR");
        assertEquals(ImpactReport.Verdict.SAFE, safe.verdict());
        assertFalse(text.contains("SHOULD_NOT_APPEAR"), "no token line unless destructive: " + text);
    }

    @Test
    void jsonMatchesSchemaShapeAndEscapesStrings() {
        String json = ImpactReportJson.render(fixture(), "2026-07-24T00:00:00Z", "sha256:old", "sha256:new", "TOK\"EN");
        assertTrue(json.contains("\"verdict\": \"DESTRUCTIVE\""), json);
        assertTrue(json.contains("\"generatedAt\": \"2026-07-24T00:00:00Z\""), json);
        assertTrue(json.contains("\"itemKey\": \"DROP_COLUMN:widgets:legacy_flag:BOOLEAN\""), json);
        assertTrue(json.contains("\"rowsAffected\": 2"), json);
        assertTrue(json.contains("\"resolution\": \"UNRESOLVED\""), json);
        assertTrue(json.contains("\"proposedConversionSql\": null"), json);
        // token present (destructive) and correctly escaped
        assertTrue(json.contains("\"acknowledgmentToken\": \"TOK\\\"EN\""), json);
    }

    @Test
    void jsonOmitsTokenWhenNotDestructiveAndIsStable() {
        ImpactReport safe = ImpactReport.ofProbedItems(List.of(new ImpactReport.Item(
                SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note", SafetyClass.SAFE_ADDITIVE, null,
                        "VARCHAR(20)"), 0L, "")));
        String first = ImpactReportJson.render(safe, "2026-07-24T00:00:00Z", "a", "b", "X");
        String second = ImpactReportJson.render(safe, "2026-07-24T00:00:00Z", "a", "b", "X");
        assertEquals(first, second, "same report + envelope must serialise byte-identically");
        assertFalse(first.contains("acknowledgmentToken"), "no token key unless destructive: " + first);
    }

    // ---- B3.2: surplus FK/index constraints (advisory, never affects verdict) ----

    @Test
    void emptySurplusAddsNoSectionToEitherRenderer() {
        ImpactReport safe = ImpactReport.ofProbedItems(List.of(new ImpactReport.Item(
                SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note", SafetyClass.SAFE_ADDITIVE, null,
                        "VARCHAR(20)"), 0L, "")));
        String text = ImpactReportText.render(safe, "a", "b", null, ConstraintSurplusReport.EMPTY);
        String json = ImpactReportJson.render(safe, "2026-07-24T00:00:00Z", "a", "b", null, ConstraintSurplusReport.EMPTY);
        assertFalse(text.contains("surplus"), text);
        assertFalse(json.contains("surplusConstraints"), json);
    }

    @Test
    void foreignSurplusRendersAsAdvisorySectionAndNeverChangesVerdict() {
        ImpactReport safe = ImpactReport.ofProbedItems(List.of(new ImpactReport.Item(
                SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note", SafetyClass.SAFE_ADDITIVE, null,
                        "VARCHAR(20)"), 0L, "")));
        ConstraintSurplusReport surplus = new ConstraintSurplusReport(
                List.of(new SurplusConstraint("widgets", "INDEX", "idx_widgets_created_at",
                        List.of("created_at"), false, null)),
                List.of());

        String text = ImpactReportText.render(safe, "a", "b", null, surplus);
        assertEquals(ImpactReport.Verdict.SAFE, safe.verdict(), "surplus must never influence the diff's own verdict");
        assertTrue(text.contains("surplus FK/index constraints"), text);
        assertTrue(text.contains("idx_widgets_created_at"), text);
        assertTrue(text.contains("never proposed for drop"), text);
        assertFalse(text.toUpperCase(java.util.Locale.ROOT).contains("DROP INDEX"),
                "the surplus section must never emit a drop statement: " + text);

        String json = ImpactReportJson.render(safe, "2026-07-24T00:00:00Z", "a", "b", null, surplus);
        assertTrue(json.contains("\"surplusConstraints\""), json);
        assertTrue(json.contains("\"kind\": \"INDEX\""), json);
        assertTrue(json.contains("\"liveName\": \"idx_widgets_created_at\""), json);
        assertTrue(json.contains("\"abstained\": null"), json);
    }

    @Test
    void surplusAbstentionRendersItsReasonInsteadOfFindings() {
        ImpactReport noChanges = ImpactReport.ofProbedItems(List.of());
        ConstraintSurplusReport abstained = new ConstraintSurplusReport(List.of(),
                List.of("cannot classify: the desired schema declares no foreign keys or indexes anywhere"));

        String text = ImpactReportText.render(noChanges, "a", "b", null, abstained);
        assertTrue(text.contains("cannot classify"), text);

        String json = ImpactReportJson.render(noChanges, "2026-07-24T00:00:00Z", "a", "b", null, abstained);
        assertTrue(json.contains("\"abstained\": \"cannot classify"), json);
    }

    // ---- B1 (boundary lift plan 2026-09-02, package 2.2): possible-rename hint, now ranked and
    // scored by the shared com.npdev.dsl.v1.schemaevolution.RenameCandidateScorer instead of the old
    // same-table/same-type-only heuristic. ImpactReportText only ever RENDERS an already-scored
    // List<RenameCandidateScorer.Candidate> (computed upstream by RenameCandidateAnalysis, which needs
    // the full CurrentSchema/DesiredSchema pair this deterministic ImpactReport-only fixture class does
    // not have -- see RenameCandidateAnalysisTest in runtimehost-core for that half). Never inferred,
    // always still refuses -- unchanged invariant, only the evidence and the floor changed.

    @Test
    void aHighScoringCandidateIsRenderedWithEveryContributingSignal() {
        ImpactReport report = ImpactReport.ofProbedItems(List.of(
                new ImpactReport.Item(SchemaDiffItem.of("DROP_COLUMN:orders:customer_name:VARCHAR(50)", "orders",
                        "customer_name", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "VARCHAR(50)", null), 3L, ""),
                new ImpactReport.Item(SchemaDiffItem.of("ADD_COLUMN:orders:client_name", "orders", "client_name",
                        SafetyClass.SAFE_ADDITIVE, null, "VARCHAR(50)"), 0L, "")));
        List<RenameCandidateScorer.Candidate> renameCandidates = List.of(
                new RenameCandidateScorer.Candidate("orders", "customer_name", "client_name", 90, List.of(
                        new RenameCandidateScorer.SignalResult("type", 25, 25, "MATCH (VARCHAR(50))"),
                        new RenameCandidateScorer.SignalResult("name similarity", 20, 25, "80% ('customer_name' vs 'client_name')"))));

        String text = ImpactReportText.render(report, "a", "b", "TOKEN", ConstraintSurplusReport.EMPTY, renameCandidates);

        assertTrue(text.contains("possible rename"), text);
        assertTrue(text.contains("'customer_name' -> 'client_name' on orders (score 90/"), text);
        assertTrue(text.contains("MATCH (VARCHAR(50))"), "every contributing signal must be shown: " + text);
        assertTrue(text.contains("\"renamedFrom\": \"customer_name\""), text);
        assertTrue(text.contains("still refuses"), "must not claim to auto-resolve anything: " + text);
    }

    @Test
    void aCandidateBelowTheNoiseFloorIsNotRendered() {
        ImpactReport report = ImpactReport.ofProbedItems(List.of(new ImpactReport.Item(
                SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note", SafetyClass.SAFE_ADDITIVE, null,
                        "VARCHAR(20)"), 0L, "")));
        List<RenameCandidateScorer.Candidate> lowScoring = List.of(
                new RenameCandidateScorer.Candidate("orders", "legacy_flag", "shipping_address_id",
                        RenameCandidateScorer.MAX_SCORE / 2 - 1, List.of()));

        String text = ImpactReportText.render(report, "a", "b", "TOKEN", ConstraintSurplusReport.EMPTY, lowScoring);

        assertFalse(text.contains("possible rename"),
                "a candidate scoring below half of MAX_SCORE is boot-log noise, not evidence: " + text);
    }

    @Test
    void noRenameCandidatesRendersNoSection() {
        ImpactReport report = ImpactReport.ofProbedItems(List.of(new ImpactReport.Item(
                SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note", SafetyClass.SAFE_ADDITIVE, null,
                        "VARCHAR(20)"), 0L, "")));

        String text = ImpactReportText.render(report, "a", "b", "TOKEN");

        assertFalse(text.contains("possible rename"), text);
    }
}
