package com.finalexec.db.schemastate;

/**
 * One change between the desired and current schema (schema-engine rebuild, Phase 2). The single unit
 * every downstream surface consumes: the reconciliation passes (Phase 4), the Impact Report (Phase 6),
 * and the conversion-hook claims (Phase 7).
 *
 * @param itemKey      stable identity in the P0.3 format ({@code KIND:field:field:…}). Destructive
 *                     items reuse {@code SchemaDeltaItem.stableString()} verbatim so acknowledgment
 *                     tokens stay byte-identical.
 * @param table        lower-cased table name
 * @param column       lower-cased column name, or {@code null} for a table-level item
 * @param constraint   lower-cased constraint name, or {@code null}
 * @param safetyClass  the item's safety classification
 * @param before       the current-side value (type / null-state / name), or {@code null}
 * @param after        the desired-side value, or {@code null}
 * @param resolution   starts {@link Resolution#UNRESOLVED}
 */
public record SchemaDiffItem(
        String itemKey,
        String table,
        String column,
        String constraint,
        SafetyClass safetyClass,
        String before,
        String after,
        Resolution resolution
) {
    /** Convenience: a fresh UNRESOLVED item. */
    public static SchemaDiffItem of(String itemKey, String table, String column, SafetyClass safetyClass,
            String before, String after) {
        return new SchemaDiffItem(itemKey, table, column, null, safetyClass, before, after, Resolution.UNRESOLVED);
    }

    public boolean isDestructive() {
        return safetyClass == SafetyClass.DESTRUCTIVE_DROP_COLUMN
                || safetyClass == SafetyClass.DESTRUCTIVE_DROP_TABLE
                || safetyClass == SafetyClass.DESTRUCTIVE_NARROW_TYPE;
    }

    /** SER-P7.4: a copy with {@link #resolution} replaced -- used by the Impact Report to mark an item
     *  {@link Resolution#HOOK_CLAIMED} once a conversion hook's claim is found to cover it. */
    public SchemaDiffItem withResolution(Resolution newResolution) {
        return new SchemaDiffItem(itemKey, table, column, constraint, safetyClass, before, after, newResolution);
    }
}
