package com.finalexec.db;

/**
 * STOR-33 (boundary B14, POSTURAL_LIFT_PLAN_2026-09-07.md package P5): one destructive schema item
 * that a conversion hook resolves under sanction -- authoring the hook is the acknowledgment
 * (ADR-0008), but the item itself used to vanish invisibly. {@code stableString} is the item's
 * {@link com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem#stableString()} (byte-identical to the
 * diff item key for the destructive kinds); {@code hookId} is the claiming hook's id. Shared by the
 * impact-report preview (what the next boot WILL sanction), the boot-time history rows (what THIS
 * boot sanctioned), and the {@code token-required} refusal.
 */
public record SanctionedDestruction(String stableString, String hookId) {
}