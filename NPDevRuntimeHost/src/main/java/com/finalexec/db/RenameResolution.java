package com.finalexec.db;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure computation of which declared {@code renamedFrom} pairs explain a live-DB-vs-manifest
 * column diff for a single business table. Extracted from {@link SchemaLifecycleExecutor#classify}
 * so the same logic drives both classification (does this table look like a clean rename?) and the
 * in-place rename-application step (which pairs are safe to execute as
 * {@code ALTER TABLE ... RENAME COLUMN}?) -- one derivation, reused everywhere it matters.
 *
 * <p>Declared renames come from {@code SchemaManifest#businessTableRenamedColumns()}: for a single
 * table, a map of {@code newColumnName -> oldColumnName}. A declared rename "explains" the diff
 * only when the new name is actually missing from the live DB AND the old name is actually present
 * as an extra column there -- i.e. the live database has not yet been migrated for that field. If
 * the live DB already has the new name (rename already applied) or never had the old name (fresh
 * install), that declared rename contributes nothing to the diff and is left unexplained -- by
 * design (§2.1's "unmatched renamedFrom is a silent no-op" hygiene rule).
 *
 * <p>This class does not itself guard against an ambiguous manifest (two different new names
 * declaring the same old name as their {@code renamedFrom}) -- {@code SemanticValidator} refuses
 * that at the model level (Task 1.1, rule 3), so a valid manifest never contains it. If it did,
 * membership-based matching here would explain the diff for whichever pairs match the live extra
 * column, which is a documented, not silently-wrong, degenerate case.
 */
final class RenameResolution {

    private RenameResolution() {
    }

    /**
     * @param explainedRenames the subset of declared renames (newName -&gt; oldName) that actually
     *                         explain the observed diff
     * @param remainingMissing manifest-expected columns not present live and not explained by a rename
     * @param remainingExtra   live columns not expected by the manifest and not explained by a rename
     */
    record Result(Map<String, String> explainedRenames, Set<String> remainingMissing, Set<String> remainingExtra) {
    }

    static Result resolve(Set<String> missingInDb, Set<String> extraInDb, Map<String, String> declaredRenames) {
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
        return new Result(Map.copyOf(explained), remainingMissing, remainingExtra);
    }
}
