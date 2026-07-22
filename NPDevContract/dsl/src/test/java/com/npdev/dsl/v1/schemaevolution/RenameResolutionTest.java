package com.npdev.dsl.v1.schemaevolution;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit coverage for {@link RenameResolution}, extracted verbatim from
 * {@code SchemaLifecycleExecutor#classify} in Phase 1, moved to the DSL module in Phase 6 (task
 * 6.1's (A) share decision -- see {@code MigrationPlanEmitter}'s class javadoc).
 */
class RenameResolutionTest {

    @Test
    void renamePresentFullyExplainsTheDiff() {
        Set<String> missing = setOf("new_name");
        Set<String> extra = setOf("old_name");
        RenameResolution.Result result = RenameResolution.resolve(missing, extra, Map.of("new_name", "old_name"));

        assertEquals(Map.of("new_name", "old_name"), result.explainedRenames());
        assertTrue(result.remainingMissing().isEmpty());
        assertTrue(result.remainingExtra().isEmpty());
    }

    @Test
    void declaredRenameUnmatchedByLiveDbIsASilentNoOp() {
        // Fresh install: neither old nor new column touches the live DB's actual diff (there IS
        // no diff for this table at all -- both sets empty). The declared rename simply never
        // fires; §2.1's hygiene rule says this must be silent, not an error.
        Set<String> missing = setOf();
        Set<String> extra = setOf();
        RenameResolution.Result result = RenameResolution.resolve(missing, extra, Map.of("new_name", "old_name"));

        assertTrue(result.explainedRenames().isEmpty());
        assertTrue(result.remainingMissing().isEmpty());
        assertTrue(result.remainingExtra().isEmpty());
    }

    @Test
    void declaredRenameWhoseOldNameIsAbsentLiveLeavesTheMissingColumnUnexplained() {
        // The rename is declared but the live DB never had old_name (e.g. rename already applied
        // on a previous boot, or a brand-new column that happens to carry a renamedFrom pointing
        // nowhere live). new_name stays in remainingMissing -- it falls to the additive-eligible
        // check, not to this class.
        Set<String> missing = setOf("new_name");
        Set<String> extra = setOf();
        RenameResolution.Result result = RenameResolution.resolve(missing, extra, Map.of("new_name", "old_name"));

        assertTrue(result.explainedRenames().isEmpty());
        assertEquals(setOf("new_name"), result.remainingMissing());
        assertTrue(result.remainingExtra().isEmpty());
    }

    @Test
    void renamePlusAdditiveColumnMixLeavesTheAdditiveColumnInRemainingMissing() {
        Set<String> missing = setOf("new_name", "brand_new_column");
        Set<String> extra = setOf("old_name");
        RenameResolution.Result result = RenameResolution.resolve(missing, extra, Map.of("new_name", "old_name"));

        assertEquals(Map.of("new_name", "old_name"), result.explainedRenames());
        assertEquals(setOf("brand_new_column"), result.remainingMissing());
        assertTrue(result.remainingExtra().isEmpty());
    }

    @Test
    void multipleIndependentRenamesOnTheSameTableAreAllExplained() {
        Set<String> missing = setOf("new_a", "new_b");
        Set<String> extra = setOf("old_a", "old_b");
        RenameResolution.Result result = RenameResolution.resolve(
                missing, extra, Map.of("new_a", "old_a", "new_b", "old_b"));

        assertEquals(Map.of("new_a", "old_a", "new_b", "old_b"), result.explainedRenames());
        assertTrue(result.remainingMissing().isEmpty());
        assertTrue(result.remainingExtra().isEmpty());
    }

    @Test
    void ambiguousDeclaredRenamesSharingTheSameOldNameBothMatchByMembership() {
        // Degenerate input SemanticValidator's rule 3 (Task 1.1) refuses at the model level: two
        // different new names both declaring renamedFrom = old_name. RenameResolution does not
        // itself disambiguate -- membership-based matching explains BOTH against the single live
        // extra column. Documented behavior, not a silently-wrong trap: callers rely on the
        // validator to make this input impossible in practice.
        Set<String> missing = setOf("new_a", "new_b");
        Set<String> extra = setOf("old_shared");
        RenameResolution.Result result = RenameResolution.resolve(
                missing, extra, Map.of("new_a", "old_shared", "new_b", "old_shared"));

        assertEquals(Map.of("new_a", "old_shared", "new_b", "old_shared"), result.explainedRenames());
        assertTrue(result.remainingExtra().isEmpty());
    }

    @Test
    void remainingExtraWithoutAMatchingRenameIsNotExplained() {
        // A column present live and not expected by the manifest, with no declared rename
        // claiming it as an old name -- an ordinary removal, not a rename.
        Set<String> missing = setOf();
        Set<String> extra = setOf("dropped_column");
        RenameResolution.Result result = RenameResolution.resolve(missing, extra, Map.of());

        assertTrue(result.explainedRenames().isEmpty());
        assertTrue(result.remainingMissing().isEmpty());
        assertEquals(setOf("dropped_column"), result.remainingExtra());
    }

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Set.of(values));
    }
}
