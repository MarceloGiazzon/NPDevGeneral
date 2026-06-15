package com.npdev.dsl.v1.ast;

import java.util.Locale;

/**
 * NPDev truth classification ladder (T0–T6).
 *
 * <p>Truth never blocks creation; it constrains release claims. The {@link #rank()}
 * gives the monotonic ordering used by bond integrity ("a bond may not point at a
 * concept whose truth level is below the bond's own" — no upward edges).
 */
public enum TruthLevel {
    T0_IDEA(0, "T0", "Idea"),
    T1_DECLARED(1, "T1", "Declared"),
    T2_GENERATED(2, "T2", "Generated"),
    T3_RUNS_LOCALLY(3, "T3", "RunsLocally"),
    T4_TESTED(4, "T4", "Tested"),
    T5_EVIDENCE_BACKED(5, "T5", "EvidenceBacked"),
    T6_RELEASE_APPROVED(6, "T6", "ReleaseApproved");

    /** A concept written in the model is at least Declared. */
    public static final TruthLevel DEFAULT = T1_DECLARED;

    private final int rank;
    private final String code;
    private final String label;

    TruthLevel(int rank, String code, String label) {
        this.rank = rank;
        this.code = code;
        this.label = label;
    }

    public int rank() {
        return rank;
    }

    /** Canonical code form, e.g. {@code "T3"}. */
    public String code() {
        return code;
    }

    /** Human label form, e.g. {@code "RunsLocally"}. */
    public String label() {
        return label;
    }

    /**
     * Parse a truth level from its code ({@code T0}..{@code T6}) or label
     * ({@code Idea}..{@code ReleaseApproved}), case-insensitively.
     *
     * @return the matching level, or {@code null} when {@code value} is unrecognized.
     */
    public static TruthLevel fromString(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        for (TruthLevel level : values()) {
            if (level.code.toLowerCase(Locale.ROOT).equals(normalized)
                    || level.label.toLowerCase(Locale.ROOT).equals(normalized)) {
                return level;
            }
        }
        return null;
    }

    /** Same as {@link #fromString(String)} but falls back to {@link #DEFAULT} when absent/unknown. */
    public static TruthLevel fromStringOrDefault(String value) {
        TruthLevel parsed = fromString(value);
        return parsed == null ? DEFAULT : parsed;
    }
}
