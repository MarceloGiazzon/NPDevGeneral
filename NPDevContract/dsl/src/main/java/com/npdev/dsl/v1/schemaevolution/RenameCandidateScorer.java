package com.npdev.dsl.v1.schemaevolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Boundary lift plan 2026-09-02, package 2.2 (docs/ACCEPTED_BOUNDARIES.md B1). A dropped column and
 * an added column on the same table can never be told apart from a rename by shape alone -- that is
 * the invariant B1 exists to protect, and this class does not weaken it: nothing here ever applies a
 * rename. What it replaces is the old all-or-nothing heuristic ({@code ImpactReportText}'s previous
 * "same table + same normalized type" gate), with a ranked, explained score so a human can judge
 * intent instead of trusting a single number.
 *
 * <p>Lives beside {@link RenameResolution} in {@code com.npdev.dsl.v1.schemaevolution} for the exact
 * reason that class does: the DSL jar is on both the generator's and RuntimeHost's classpaths with
 * zero dependency either way, so {@code com.finalexec.db.ImpactReportText} (probing a LIVE database)
 * and {@code com.npdev.generator.schemaevolution.MigrationPlanEmitter} (previewing a MODEL-vs-MODEL
 * diff, no database) can share one derivation instead of drifting into two. Each side supplies its
 * own {@link ColumnFacts} adapter over whatever column representation it already has
 * ({@code CurrentColumn}/{@code DesiredColumn} for RuntimeHost, {@code BusinessTableMetadata} for the
 * generator) -- this class only ever sees the generic facts.
 *
 * <p>Pure and deterministic: no DB, no clock, no randomness. Given the same two column-fact lists it
 * always returns the same ranked {@link Candidate} list.
 */
public final class RenameCandidateScorer {

    private RenameCandidateScorer() {
    }

    /**
     * Facts about one column, supplied by the caller's own adapter over its native representation.
     *
     * @param name              lower-cased column name
     * @param normalizedSqlType the column's SQL type in whatever spelling the caller has; run through
     *                          {@link SqlTypeNormalization#normalize} again here so a caller that
     *                          passes an already-normalized string or a raw declared one both compare
     *                          correctly
     * @param nullable          whether the column permits NULL
     * @param defaultLiteral    the literal default (any caller-consistent textual form), or
     *                          {@code null} for none
     * @param uniqueMember      whether the column participates in any unique constraint on its table
     * @param fkTarget          the referenced table name when this column is a foreign key, else
     *                          {@code null}
     * @param ordinalPosition   the column's 0-based position within its table, in the caller's own
     *                          declared/read order
     */
    public record ColumnFacts(
            String name,
            String normalizedSqlType,
            boolean nullable,
            String defaultLiteral,
            boolean uniqueMember,
            String fkTarget,
            int ordinalPosition
    ) {
    }

    /** One scored signal's contribution -- shown verbatim so a human judges the evidence, not just the total. */
    public record SignalResult(String signal, int points, int maxPoints, String detail) {
    }

    /** One ranked candidate: dropped column {@code droppedColumn} might be a rename of added column
     *  {@code addedColumn} on {@code table}. Never a claim of certainty -- {@code score} is a heuristic,
     *  same as before this class existed; only the transparency changed. */
    public record Candidate(String table, String droppedColumn, String addedColumn, int score,
            List<SignalResult> signals) {
    }

    private static final int NAME_SIMILARITY_MAX = 25;
    private static final int TYPE_MAX = 25;
    private static final int NULLABILITY_MAX = 10;
    private static final int DEFAULT_MAX = 10;
    private static final int UNIQUE_MAX = 10;
    private static final int FK_MAX = 15;
    private static final int ORDINAL_MAX = 5;

    /** Sum of every signal's {@code maxPoints} -- a {@link Candidate#score()} is always in {@code [0, MAX_SCORE]}. */
    public static final int MAX_SCORE =
            NAME_SIMILARITY_MAX + TYPE_MAX + NULLABILITY_MAX + DEFAULT_MAX + UNIQUE_MAX + FK_MAX + ORDINAL_MAX;

    /**
     * Every dropped-column x added-column pair on one table, scored and ranked highest-first
     * (ties broken by dropped then added column name, for determinism). Returns every pair --
     * filtering by a score floor, if a caller wants one, is the caller's decision to make and show.
     */
    public static List<Candidate> score(String table, List<ColumnFacts> dropped, List<ColumnFacts> added) {
        List<Candidate> candidates = new ArrayList<>();
        for (ColumnFacts droppedColumn : dropped) {
            for (ColumnFacts addedColumn : added) {
                candidates.add(scorePair(table, droppedColumn, addedColumn));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(Candidate::droppedColumn)
                .thenComparing(Candidate::addedColumn));
        return List.copyOf(candidates);
    }

    private static Candidate scorePair(String table, ColumnFacts dropped, ColumnFacts added) {
        List<SignalResult> signals = new ArrayList<>();
        int score = 0;
        score += addNameSimilarity(signals, dropped.name(), added.name());
        score += addEquality(signals, "type", TYPE_MAX,
                SqlTypeNormalization.normalize(dropped.normalizedSqlType()),
                SqlTypeNormalization.normalize(added.normalizedSqlType()));
        score += addBooleanEquality(signals, "nullable", NULLABILITY_MAX, dropped.nullable(), added.nullable());
        score += addEquality(signals, "default", DEFAULT_MAX,
                trimOrNull(dropped.defaultLiteral()), trimOrNull(added.defaultLiteral()));
        score += addBooleanEquality(signals, "unique membership", UNIQUE_MAX,
                dropped.uniqueMember(), added.uniqueMember());
        score += addEquality(signals, "FK target", FK_MAX,
                trimOrNull(dropped.fkTarget()), trimOrNull(added.fkTarget()));
        score += addOrdinalProximity(signals, dropped.ordinalPosition(), added.ordinalPosition());
        return new Candidate(table, dropped.name(), added.name(), score, List.copyOf(signals));
    }

    private static int addNameSimilarity(List<SignalResult> signals, String droppedName, String addedName) {
        double ratio = nameSimilarity(droppedName, addedName);
        int points = (int) Math.round(ratio * NAME_SIMILARITY_MAX);
        signals.add(new SignalResult("name similarity", points, NAME_SIMILARITY_MAX,
                String.format(Locale.ROOT, "%.0f%% ('%s' vs '%s')", ratio * 100, nullSafe(droppedName), nullSafe(addedName))));
        return points;
    }

    private static int addEquality(List<SignalResult> signals, String label, int max, String a, String b) {
        boolean equal = Objects.equals(a, b);
        int points = equal ? max : 0;
        String detail = equal ? "MATCH (" + nullSafe(a) + ")" : "DIFFERS (" + nullSafe(a) + " vs " + nullSafe(b) + ")";
        signals.add(new SignalResult(label, points, max, detail));
        return points;
    }

    private static int addBooleanEquality(List<SignalResult> signals, String label, int max, boolean a, boolean b) {
        boolean equal = a == b;
        int points = equal ? max : 0;
        signals.add(new SignalResult(label, points, max,
                equal ? "MATCH (" + a + ")" : "DIFFERS (" + a + " vs " + b + ")"));
        return points;
    }

    private static int addOrdinalProximity(List<SignalResult> signals, int droppedPosition, int addedPosition) {
        int distance = Math.abs(droppedPosition - addedPosition);
        int points = distance == 0 ? ORDINAL_MAX : (distance == 1 ? ORDINAL_MAX / 2 : 0);
        String detail = distance == 0
                ? "MATCH (position " + droppedPosition + ")"
                : "position " + droppedPosition + " vs " + addedPosition + " (distance " + distance + ")";
        signals.add(new SignalResult("ordinal position", points, ORDINAL_MAX, detail));
        return points;
    }

    private static String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullSafe(String value) {
        return value == null ? "none" : value;
    }

    /** Case-insensitive normalized Levenshtein similarity in {@code [0.0, 1.0]}; {@code 1.0} = identical
     *  spelling, {@code 0.0} = maximally different for the pair's own length. */
    private static double nameSimilarity(String a, String b) {
        String x = a == null ? "" : a.toLowerCase(Locale.ROOT);
        String y = b == null ? "" : b.toLowerCase(Locale.ROOT);
        int maxLen = Math.max(x.length(), y.length());
        if (maxLen == 0) {
            return 1.0;
        }
        return 1.0 - ((double) levenshtein(x, y) / maxLen);
    }

    /** Classic two-row edit distance -- O(len(a)*len(b)) time, O(len(b)) space. Column names are short. */
    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitutionCost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + substitutionCost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
