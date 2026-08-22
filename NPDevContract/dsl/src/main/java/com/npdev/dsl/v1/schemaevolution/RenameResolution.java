package com.npdev.dsl.v1.schemaevolution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * LNCH-1 Phase 1, moved to the DSL module in Phase 6 (task 6.1's (A) share decision -- see
 * {@code com.npdev.generator.schemaevolution.MigrationPlanEmitter}'s class javadoc for the full
 * reasoning). Pure computation of which declared {@code renamedFrom} pairs explain a "missing set
 * vs extra set" diff for a single table/concept. Originally extracted from
 * {@code SchemaLifecycleExecutor#classify} so the same logic drives both live-DB classification
 * and in-place rename application; now ALSO used by the generator's model-vs-model migration-plan
 * preview ({@code MigrationPlanEmitter}), since the underlying algorithm is generic over any
 * name-vs-name diff and does not care whether "missing"/"extra" come from JDBC introspection or
 * from a second {@code CompiledModel} -- one derivation, reused everywhere it matters, instead of
 * a second copy that could silently drift from the first.
 *
 * <p>Declared renames are supplied by the caller as a {@code newName -> oldName} map (for
 * RuntimeHost: {@code SchemaManifest#businessTableRenamedColumns()} / {@code businessTableRenames()};
 * for the generator: derived directly from a field's or concept's {@code renamedFrom}). A declared
 * rename "explains" the diff only when the new name is actually missing from the "old" side AND
 * the old name is actually present as "extra" there -- i.e. the old side has not yet caught up to
 * the new declaration. If the old side already has the new name (rename already applied) or never
 * had the old name (fresh install / brand-new declaration), that declared rename contributes
 * nothing to the diff and is left unexplained -- by design (§2.1's "unmatched renamedFrom is a
 * silent no-op" hygiene rule).
 *
 * <p>This class does not itself guard against an ambiguous rename declaration (two different new
 * names both declaring the same old name as their {@code renamedFrom}) -- {@code SemanticValidator}
 * refuses that at the model level, so a valid model/manifest never contains it. If it did,
 * membership-based matching here would explain the diff for whichever pairs match the "extra" set,
 * which is a documented, not silently-wrong, degenerate case.
 */
public final class RenameResolution {

    private RenameResolution() {
    }

    /**
     * @param explainedRenames the subset of declared renames (newName -&gt; oldName) that actually
     *                         explain the observed diff
     * @param remainingMissing "new"-side expected names not present on the "old" side and not
     *                         explained by a rename
     * @param remainingExtra   "old"-side names not expected on the "new" side and not explained by
     *                         a rename
     */
    public record Result(Map<String, String> explainedRenames, Set<String> remainingMissing, Set<String> remainingExtra) {
    }

    public static Result resolve(Set<String> missingInDb, Set<String> extraInDb, Map<String, String> declaredRenames) {
        Map<String, String> explained = new LinkedHashMap<>();
        for (Map.Entry<String, String> rename : declaredRenames.entrySet()) {
            if (missingInDb.contains(rename.getKey()) && extraInDb.contains(rename.getValue())) {
                explained.put(rename.getKey(), rename.getValue());
            }
        }
        Set<String> remainingMissing = new LinkedHashSet<>(missingInDb);
        remainingMissing.removeAll(explained.keySet());
        Set<String> remainingExtra = new LinkedHashSet<>(extraInDb);
        remainingExtra.removeAll(explained.values());
        // REG-175/REG-146: MigrationPlanEmitter iterates explainedRenames().entrySet() unsorted into
        // migration-plan.json's items array -- Map.copyOf's JEP 269 iteration-order randomization
        // was reaching that emitted plan. `explained` is already a LinkedHashMap built in
        // declaredRenames' own order; unmodifiableMap preserves it instead of re-randomizing.
        return new Result(Collections.unmodifiableMap(explained), remainingMissing, remainingExtra);
    }
}
