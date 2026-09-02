package com.npdev.dsl.v1.schemaevolution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit coverage for {@link RenameCandidateScorer} (boundary lift plan 2026-09-02, package 2.2). */
class RenameCandidateScorerTest {

    @Test
    void identicalFactsUnderADifferentNameScoreEverythingButNameSimilarity() {
        RenameCandidateScorer.ColumnFacts dropped = new RenameCandidateScorer.ColumnFacts(
                "old_name", "VARCHAR(255)", false, "'x'", true, null, 3);
        RenameCandidateScorer.ColumnFacts added = new RenameCandidateScorer.ColumnFacts(
                "totally_different", "VARCHAR(255)", false, "'x'", true, null, 3);

        List<RenameCandidateScorer.Candidate> candidates =
                RenameCandidateScorer.score("t", List.of(dropped), List.of(added));

        assertEquals(1, candidates.size());
        RenameCandidateScorer.Candidate candidate = candidates.get(0);
        // Every non-name signal maxes out; only "name similarity" falls short for an unrelated name.
        int nonNameMax = RenameCandidateScorer.MAX_SCORE - 25;
        assertEquals(nonNameMax, candidate.score() - pointsFor(candidate, "name similarity"));
    }

    @Test
    void aOneCharacterTypoScoresNearlyMaximal() {
        RenameCandidateScorer.ColumnFacts dropped = new RenameCandidateScorer.ColumnFacts(
                "email_addres", "VARCHAR(255)", false, null, true, null, 2);
        RenameCandidateScorer.ColumnFacts added = new RenameCandidateScorer.ColumnFacts(
                "email_address", "VARCHAR(255)", false, null, true, null, 2);

        RenameCandidateScorer.Candidate candidate =
                RenameCandidateScorer.score("t", List.of(dropped), List.of(added)).get(0);

        assertTrue(candidate.score() >= RenameCandidateScorer.MAX_SCORE - 3,
                "near-identical names should barely lose any name-similarity points: " + candidate.score());
    }

    @Test
    void completelyUnrelatedColumnsScoreLow() {
        RenameCandidateScorer.ColumnFacts dropped = new RenameCandidateScorer.ColumnFacts(
                "legacy_flag", "BOOLEAN", true, null, false, null, 0);
        RenameCandidateScorer.ColumnFacts added = new RenameCandidateScorer.ColumnFacts(
                "shipping_address_id", "UUID", false, null, false, "address", 9);

        RenameCandidateScorer.Candidate candidate =
                RenameCandidateScorer.score("t", List.of(dropped), List.of(added)).get(0);

        assertTrue(candidate.score() < RenameCandidateScorer.MAX_SCORE / 4,
                "unrelated columns should score low: " + candidate.score());
    }

    @Test
    void typeEqualityNormalizesThroughSqlTypeNormalizationSoEngineSpellingsMatch() {
        // H2 reports CHARACTER VARYING for a model-declared VARCHAR(n) -- same normalizer the
        // executor/generator already share for destructive stable strings (LNCH-1 R1).
        RenameCandidateScorer.ColumnFacts dropped = new RenameCandidateScorer.ColumnFacts(
                "a", "CHARACTER VARYING(40)", false, null, false, null, 0);
        RenameCandidateScorer.ColumnFacts added = new RenameCandidateScorer.ColumnFacts(
                "b", "VARCHAR(40)", false, null, false, null, 0);

        RenameCandidateScorer.Candidate candidate =
                RenameCandidateScorer.score("t", List.of(dropped), List.of(added)).get(0);

        assertEquals(25, pointsFor(candidate, "type"));
    }

    @Test
    void rankingIsHighestScoreFirstThenNameOrderForTies() {
        RenameCandidateScorer.ColumnFacts droppedA = new RenameCandidateScorer.ColumnFacts(
                "customer_name", "VARCHAR(100)", false, null, false, null, 1);
        RenameCandidateScorer.ColumnFacts addedClose = new RenameCandidateScorer.ColumnFacts(
                "customer_full_name", "VARCHAR(100)", false, null, false, null, 1);
        RenameCandidateScorer.ColumnFacts addedFar = new RenameCandidateScorer.ColumnFacts(
                "zzz_unrelated", "BOOLEAN", true, null, true, "other", 8);

        List<RenameCandidateScorer.Candidate> candidates = RenameCandidateScorer.score(
                "t", List.of(droppedA), List.of(addedFar, addedClose));

        assertEquals("customer_full_name", candidates.get(0).addedColumn());
        assertEquals("zzz_unrelated", candidates.get(1).addedColumn());
        assertTrue(candidates.get(0).score() >= candidates.get(1).score());
    }

    @Test
    void everyDroppedByAddedPairIsScoredAndTableIsCarriedThrough() {
        RenameCandidateScorer.ColumnFacts d1 = new RenameCandidateScorer.ColumnFacts(
                "a", "INTEGER", false, null, false, null, 0);
        RenameCandidateScorer.ColumnFacts d2 = new RenameCandidateScorer.ColumnFacts(
                "b", "INTEGER", false, null, false, null, 1);
        RenameCandidateScorer.ColumnFacts a1 = new RenameCandidateScorer.ColumnFacts(
                "c", "INTEGER", false, null, false, null, 0);

        List<RenameCandidateScorer.Candidate> candidates =
                RenameCandidateScorer.score("orders", List.of(d1, d2), List.of(a1));

        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().allMatch(c -> c.table().equals("orders")));
    }

    private static int pointsFor(RenameCandidateScorer.Candidate candidate, String signal) {
        return candidate.signals().stream()
                .filter(s -> s.signal().equals(signal))
                .findFirst()
                .map(RenameCandidateScorer.SignalResult::points)
                .orElseThrow();
    }
}
