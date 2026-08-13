package com.npdev.dsl.v1.pack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PK-3: Minimal Version Selection over a single pack id's constraints, collected graph-wide.
 * Deterministic, no backtracking -- with exactly one local file per pack id available today (no
 * registry/candidate pool yet, that's PK-5), "selection" is really "verify the one local copy
 * satisfies every constraint that named it", but the contract (a packId + its requirements in,
 * a resolved version or a named refusal out) is forward-compatible with PK-5 swapping in a real
 * multi-version candidate pool later without changing this class at all.
 *
 * <p>Pure function -- no file I/O, easily unit-tested with synthetic data.
 */
public final class MinimalVersionSelector {

    private MinimalVersionSelector() {
    }

    /** One requirer's own declared constraint on a packId, plus the path that reached it (for
     *  naming every contributor in a refusal message, and for {@code npdev pack why}). */
    public record Requirement(String requirerPackId, List<String> path, PackVersionConstraint constraint) {
    }

    public sealed interface Result permits Selected, Refused {
    }

    public record Selected(PackVersion resolvedVersion) implements Result {
    }

    public record Refused(String message) implements Result {
    }

    /**
     * @param packId       the pack id being resolved (for messages only)
     * @param requirements every constraint any pack/app in the graph placed on {@code packId}
     * @param localVersion the version the one local pack.json for {@code packId} actually declares
     */
    public static Result select(String packId, List<Requirement> requirements, PackVersion localVersion) {
        if (requirements.isEmpty()) {
            return new Selected(localVersion);
        }

        Set<Integer> majors = new LinkedHashSet<>();
        for (Requirement requirement : requirements) {
            majors.add(requirement.constraint().requiredMajor());
        }
        if (majors.size() > 1) {
            return new Refused("Pack '" + packId + "' has version constraints spanning incompatible major "
                    + "versions: " + describe(requirements));
        }

        List<Requirement> exacts = new ArrayList<>();
        for (Requirement requirement : requirements) {
            if (requirement.constraint() instanceof PackVersionConstraint.Exact) {
                exacts.add(requirement);
            }
        }
        if (!exacts.isEmpty()) {
            PackVersion firstExact = exacts.get(0).constraint().minimum();
            for (Requirement exact : exacts) {
                if (!exact.constraint().minimum().equals(firstExact)) {
                    return new Refused("Pack '" + packId + "' has conflicting exact version constraints: "
                            + describe(exacts));
                }
            }
        }

        for (Requirement requirement : requirements) {
            if (!requirement.constraint().satisfies(localVersion)) {
                PackVersion highestMinimum = requirements.stream()
                        .map(r -> r.constraint().minimum())
                        .max(PackVersion::compareTo)
                        .orElseThrow();
                return new Refused("Pack '" + packId + "' requires at least " + highestMinimum + " (highest of: "
                        + describe(requirements) + "); the local copy declares " + localVersion
                        + " -- run 'npdev pack update' after replacing the local pack.json, or relax a constraint");
            }
        }
        return new Selected(localVersion);
    }

    private static String describe(List<Requirement> requirements) {
        List<String> parts = new ArrayList<>();
        for (Requirement requirement : requirements) {
            parts.add(requirement.requirerPackId() + " needs " + requirement.constraint().rawConstraint()
                    + " via " + String.join(" -> ", requirement.path()));
        }
        return String.join("; ", parts);
    }
}
