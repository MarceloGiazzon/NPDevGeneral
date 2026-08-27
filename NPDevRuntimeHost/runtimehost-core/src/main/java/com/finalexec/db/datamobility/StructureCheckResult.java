package com.finalexec.db.datamobility;

import java.util.List;

/**
 * The outcome of one {@link DataMobilityStructureCheck#check} call.
 *
 * <p>The two reason lists are populated independently of each other, not as a matched pair: a
 * {@link #compatible(List)} result carries only {@code compatibleReasons} (what was different but
 * harmless), and an {@link #incompatible(List)} result carries only {@code incompatibleReasons}
 * (what actually blocks the write). This mirrors the verdict itself -- once ANY item is a blocker
 * the overall answer is INCOMPATIBLE, so the caller's first question is "why", not "what else was
 * fine too". A caller that wants the full picture regardless of verdict can call
 * {@link DataMobilityStructureCheck} directly against the underlying diff.
 *
 * @param verdict              the reduced three-way outcome
 * @param incompatibleReasons  human-readable blockers, non-empty only when {@code verdict == INCOMPATIBLE}
 * @param compatibleReasons    human-readable harmless differences, non-empty only when {@code verdict == COMPATIBLE}
 */
public record StructureCheckResult(
        StructureVerdict verdict,
        List<String> incompatibleReasons,
        List<String> compatibleReasons
) {
    public static StructureCheckResult equal() {
        return new StructureCheckResult(StructureVerdict.EQUAL, List.of(), List.of());
    }

    public static StructureCheckResult compatible(List<String> reasons) {
        return new StructureCheckResult(StructureVerdict.COMPATIBLE, List.of(), List.copyOf(reasons));
    }

    public static StructureCheckResult incompatible(List<String> reasons) {
        return new StructureCheckResult(StructureVerdict.INCOMPATIBLE, List.copyOf(reasons), List.of());
    }
}
