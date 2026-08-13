package com.npdev.dsl.v1.pack;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The full output of {@link PackDiffEngine#diff}: every classified finding between an old and a
 * new {@code pack.json}, plus the pack-level aggregate.
 */
public record PackDiffResult(List<PackDiffFinding> findings) {

    public PackDiffResult {
        findings = List.copyOf(findings);
    }

    /**
     * The worst classification among every finding, or empty when {@link #findings()} is empty
     * (i.e. the two documents are identical in every way this engine looks at -- see the
     * {@code migrations}/{@code version}/{@code dslVersion}/{@code $schema} exclusions documented on
     * {@link PackDiffEngine}). An empty result means no version bump is required at all, which is a
     * distinct, stronger statement than {@link PackChangeClassification#PATCH} (some difference,
     * but a harmless one) -- {@code PackPublishGate} treats them differently for exactly that reason.
     */
    public Optional<PackChangeClassification> worstClassification() {
        return findings.stream()
                .map(PackDiffFinding::classification)
                .max(Comparator.naturalOrder());
    }

    public boolean isEmpty() {
        return findings.isEmpty();
    }
}
