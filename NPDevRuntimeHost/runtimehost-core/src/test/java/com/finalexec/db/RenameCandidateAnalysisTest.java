package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentColumn;
import com.finalexec.db.schemastate.CurrentForeignKey;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentTable;
import com.finalexec.db.schemastate.CurrentUniqueConstraint;
import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.Resolution;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.schemaevolution.RenameCandidateScorer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundary lift plan 2026-09-02, package 2.2 (B1). Unit coverage for {@link RenameCandidateAnalysis}
 * -- the RuntimeHost-side adapter feeding {@link RenameCandidateScorer} from the full
 * {@link CurrentSchema}/{@link DesiredSchema} pair, and the eligibility filter (destructive drop with
 * live rows, unresolved add, same table) that used to live inline in
 * {@code ImpactReportText#appendPossibleRenames} before this package split rendering from scoring.
 */
class RenameCandidateAnalysisTest {

    @Test
    void anEligibleDropAndAddOnTheSameTableProduceAScoredCandidateCarryingRealColumnFacts() {
        CurrentTable currentOrders = new CurrentTable("orders",
                Map.of("customer_name", new CurrentColumn("customer_name", "VARCHAR(50)", 50, null, false, null)),
                List.of(), List.of(new CurrentUniqueConstraint("uq_orders_customer_name", List.of("customer_name"))),
                List.of(), List.of());
        DesiredTable desiredOrders = new DesiredTable("orders",
                Map.of("client_name", new DesiredColumn("client_name", "VARCHAR(50)", false, null, false, true, false, true, null)),
                List.of(), null);

        ImpactReport report = ImpactReport.ofProbedItems(List.of(
                new ImpactReport.Item(SchemaDiffItem.of("DROP_COLUMN:orders:customer_name:VARCHAR(50)", "orders",
                        "customer_name", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "VARCHAR(50)", null), 3L, ""),
                new ImpactReport.Item(SchemaDiffItem.of("ADD_COLUMN:orders:client_name", "orders", "client_name",
                        SafetyClass.SAFE_ADDITIVE, null, "VARCHAR(50)"), 0L, "")));

        List<RenameCandidateScorer.Candidate> candidates = RenameCandidateAnalysis.compute(
                report, new DesiredSchema(Map.of("orders", desiredOrders)), new CurrentSchema(Map.of("orders", currentOrders)));

        assertEquals(1, candidates.size());
        RenameCandidateScorer.Candidate candidate = candidates.get(0);
        assertEquals("orders", candidate.table());
        assertEquals("customer_name", candidate.droppedColumn());
        assertEquals("client_name", candidate.addedColumn());
        // Same type (VARCHAR(50) both sides) and the dropped side is unique-member -- both real facts
        // read off CurrentTable/DesiredTable, not guessed -- must show up in the signal breakdown.
        assertTrue(signalDetail(candidate, "type").contains("MATCH"), candidate.signals().toString());
        assertTrue(signalDetail(candidate, "unique membership").contains("DIFFERS"),
                "dropped side is unique-member, added side (additiveEligible fixture) is not -- must not be silently equal: "
                        + candidate.signals());
    }

    @Test
    void aDropWithZeroLiveRowsIsNeverAnEligibleCandidate() {
        CurrentTable currentOrders = new CurrentTable("orders",
                Map.of("customer_name", new CurrentColumn("customer_name", "VARCHAR(50)", 50, null, false, null)),
                List.of(), List.of(), List.of(), List.of());
        DesiredTable desiredOrders = new DesiredTable("orders",
                Map.of("client_name", new DesiredColumn("client_name", "VARCHAR(50)", false, null, false, true, false, true, null)),
                List.of(), null);

        ImpactReport report = ImpactReport.ofProbedItems(List.of(
                new ImpactReport.Item(SchemaDiffItem.of("DROP_COLUMN:orders:customer_name:VARCHAR(50)", "orders",
                        "customer_name", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "VARCHAR(50)", null), 0L, ""),
                new ImpactReport.Item(SchemaDiffItem.of("ADD_COLUMN:orders:client_name", "orders", "client_name",
                        SafetyClass.SAFE_ADDITIVE, null, "VARCHAR(50)"), 0L, "")));

        List<RenameCandidateScorer.Candidate> candidates = RenameCandidateAnalysis.compute(
                report, new DesiredSchema(Map.of("orders", desiredOrders)), new CurrentSchema(Map.of("orders", currentOrders)));

        assertTrue(candidates.isEmpty(), "nothing at stake with zero live rows: " + candidates);
    }

    @Test
    void aHookClaimedDropIsNeverAnEligibleCandidate() {
        CurrentTable currentOrders = new CurrentTable("orders",
                Map.of("customer_name", new CurrentColumn("customer_name", "VARCHAR(50)", 50, null, false, null)),
                List.of(), List.of(), List.of(), List.of());
        DesiredTable desiredOrders = new DesiredTable("orders",
                Map.of("client_name", new DesiredColumn("client_name", "VARCHAR(50)", false, null, false, true, false, true, null)),
                List.of(), null);

        SchemaDiffItem hookClaimedDrop = SchemaDiffItem.of("DROP_COLUMN:orders:customer_name:VARCHAR(50)", "orders",
                "customer_name", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "VARCHAR(50)", null).withResolution(Resolution.HOOK_CLAIMED);
        ImpactReport report = ImpactReport.ofProbedItems(List.of(
                new ImpactReport.Item(hookClaimedDrop, 3L, "HOOK: some-hook"),
                new ImpactReport.Item(SchemaDiffItem.of("ADD_COLUMN:orders:client_name", "orders", "client_name",
                        SafetyClass.SAFE_ADDITIVE, null, "VARCHAR(50)"), 0L, "")));

        List<RenameCandidateScorer.Candidate> candidates = RenameCandidateAnalysis.compute(
                report, new DesiredSchema(Map.of("orders", desiredOrders)), new CurrentSchema(Map.of("orders", currentOrders)));

        assertTrue(candidates.isEmpty(), "a hook already resolves this drop deliberately: " + candidates);
    }

    @Test
    void aDropOnOneTableAndAnAddOnAnotherAreNeverCrossed() {
        CurrentTable currentOrders = new CurrentTable("orders",
                Map.of("legacy_flag", new CurrentColumn("legacy_flag", "BOOLEAN", null, null, true, null)),
                List.of(), List.of(), List.of(), List.of());
        CurrentTable currentWidgets = new CurrentTable("widgets", Map.of(), List.of(), List.of(), List.of(), List.of());
        DesiredTable desiredOrders = new DesiredTable("orders", Map.of(), List.of(), null);
        DesiredTable desiredWidgets = new DesiredTable("widgets",
                Map.of("legacy_flag", new DesiredColumn("legacy_flag", "BOOLEAN", true, null, false, false, false, true, null)),
                List.of(), null);

        ImpactReport report = ImpactReport.ofProbedItems(List.of(
                new ImpactReport.Item(SchemaDiffItem.of("DROP_COLUMN:orders:legacy_flag:BOOLEAN", "orders",
                        "legacy_flag", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null), 5L, ""),
                new ImpactReport.Item(SchemaDiffItem.of("ADD_COLUMN:widgets:legacy_flag", "widgets", "legacy_flag",
                        SafetyClass.SAFE_ADDITIVE, null, "BOOLEAN"), 0L, "")));

        List<RenameCandidateScorer.Candidate> candidates = RenameCandidateAnalysis.compute(
                report,
                new DesiredSchema(Map.of("orders", desiredOrders, "widgets", desiredWidgets)),
                new CurrentSchema(Map.of("orders", currentOrders, "widgets", currentWidgets)));

        assertTrue(candidates.isEmpty(), "a drop on one table must never be crossed with an add on a different table: " + candidates);
    }

    @Test
    void anFkTargetIsCarriedThroughFromCurrentForeignKeys() {
        CurrentTable currentOrders = new CurrentTable("orders",
                Map.of("buyer_id", new CurrentColumn("buyer_id", "UUID", null, null, false, null)),
                List.of(), List.of(),
                List.of(new CurrentForeignKey("fk_orders_buyer", List.of("buyer_id"), "customers", List.of("id"), null)),
                List.of());
        DesiredTable desiredOrders = new DesiredTable("orders",
                Map.of("purchaser_id", new DesiredColumn("purchaser_id", "UUID", false, null, false, true, true, true, null)),
                List.of(), null);

        ImpactReport report = ImpactReport.ofProbedItems(List.of(
                new ImpactReport.Item(SchemaDiffItem.of("DROP_COLUMN:orders:buyer_id:UUID", "orders",
                        "buyer_id", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "UUID", null), 4L, ""),
                new ImpactReport.Item(SchemaDiffItem.of("ADD_REQUIRED_COLUMN:orders:purchaser_id", "orders", "purchaser_id",
                        SafetyClass.SAFE_ADDITIVE, null, "UUID"), 0L, "")));

        List<RenameCandidateScorer.Candidate> candidates = RenameCandidateAnalysis.compute(
                report, new DesiredSchema(Map.of("orders", desiredOrders)), new CurrentSchema(Map.of("orders", currentOrders)));

        assertEquals(1, candidates.size());
        // No declared FK on the desired side in this fixture -- one side has a target, the other does
        // not, which must read as DIFFERS, not silently ignored.
        assertTrue(signalDetail(candidates.get(0), "FK target").contains("DIFFERS"), candidates.get(0).signals().toString());
    }

    private static String signalDetail(RenameCandidateScorer.Candidate candidate, String signalName) {
        return candidate.signals().stream()
                .filter(s -> s.signal().equals(signalName))
                .findFirst()
                .map(RenameCandidateScorer.SignalResult::detail)
                .orElseThrow(() -> new AssertionError("no such signal: " + signalName + " in " + candidate.signals()));
    }
}
