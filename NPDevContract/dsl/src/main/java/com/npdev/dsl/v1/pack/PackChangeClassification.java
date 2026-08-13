package com.npdev.dsl.v1.pack;

/**
 * PK-4 Stage A: the three buckets every difference between two {@code pack.json} documents is
 * classified into (see {@link PackDiffEngine}).
 *
 * <p>Declared in ascending severity order on purpose -- {@link #compareTo(Object)} (inherited from
 * {@code Enum}) and {@code Comparator.naturalOrder()} both then answer "which change is worse"
 * correctly, and {@link PackDiffResult#worstClassification()} relies on exactly that ordering to
 * compute the pack-level aggregate as the max of every finding.
 *
 * <ul>
 *   <li>{@link #PATCH} -- description/metadata-only. Nothing a consumer could observe changed.</li>
 *   <li>{@link #ADDITIVE} -- a new nullable/optional field, a wholly new concept/panel/query/
 *       procedure/etc., or any other change that could not break an existing consumer.</li>
 *   <li>{@link #BREAKING} -- a field/concept/panel/query removed, a type narrowed or retyped, a
 *       field made required that wasn't before, or any rename. Renames are classified BREAKING
 *       unconditionally: there is no migration-chain mechanism yet (that is Stage C, explicitly out
 *       of scope for this engine) to prove a rename preserves data, so this engine does not attempt
 *       to detect "this removal + this addition is really one rename" at all -- it sees a field
 *       removed (BREAKING) and a field added (ADDITIVE) as two independent findings, and the
 *       BREAKING one dominates the aggregate. That is deliberate, not an accident of a missing
 *       heuristic: see {@code PackDiffEngineTest} for the explicit rename case.</li>
 * </ul>
 */
public enum PackChangeClassification {
    PATCH,
    ADDITIVE,
    BREAKING
}
