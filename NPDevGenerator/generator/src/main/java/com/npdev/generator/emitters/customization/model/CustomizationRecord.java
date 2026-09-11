package com.npdev.generator.emitters.customization.model;

/**
 * Path A P5.2: one author-declared provenance entry from the optional sibling
 * {@code customization-provenance.json}, keyed by {@code owner} -- the same id
 * {@code ExtensionInventoryEmitter} already stamps as a customization's owner (P0.3).
 */
public record CustomizationRecord(
        String owner,
        String changeSummary,
        String reason,
        String author,
        String authorType,
        String regenerationIntent,
        boolean requiresRetest,
        String releaseImpact
) {
}
