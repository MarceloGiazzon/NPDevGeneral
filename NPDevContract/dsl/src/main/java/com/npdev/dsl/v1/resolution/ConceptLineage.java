package com.npdev.dsl.v1.resolution;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ModelAst;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3.3 (NPDEV_PATH_A_REALIGNMENT_PLAN.md Decision D3): "what does this specialization inherit, add,
 * change and remove, and from which parent version" -- computed by comparing a concept's RAW
 * (pre-{@link ModelResolver}) declaration against its resolved parent's final state, rather than
 * re-deriving anything {@code mergeConcept} already decided. {@code mergeConcept} itself only ever
 * returns the flattened result; provenance of which field came from where is not preserved on
 * {@link ConceptAst} -- this class is the one place that reconstructs it, over the SAME two ASTs
 * {@code XrefEmitter}/{@code SemanticGraphEmitter} already keep in scope (the raw parse and the
 * resolved model), so no third traversal or a new field on {@code ConceptAst} is needed.
 *
 * <p>Deliberately scoped to fields and to the override-allowed top-level attributes
 * ({@code docs/architecture/DSL_SPECIALIZATION_POLICY.md}'s table) -- not invariants/events, which
 * are already add-only-with-no-dup and answer a different question ("is this legal") rather than
 * "what would I reuse", the question this report exists to answer.
 *
 * <p>{@code parentVersion}/{@code parentDigest} come from the resolved base's own {@code
 * OriginAst} (PACK-2) rather than a new versioned-reference syntax on {@code specializes} itself --
 * per D3, the parent is already pinned by whichever pack version the model's own lock file resolved,
 * so a second pinning mechanism on the specialization edge would just be redundant. Both are
 * {@code null} when the base is not pack-contributed (an app's own root concept has no version to
 * report).
 *
 * <p>{@code removes} is always empty: {@code docs/architecture/DSL_SPECIALIZATION_POLICY.md}
 * confirms fields/invariants/events are add-only in the single specialization lane, so there is
 * currently no way for a specialization to remove anything it inherits. The field exists so a
 * future removal mechanism has somewhere to report into without a second schema revision.
 */
public record ConceptLineage(
        String parent,
        String parentVersion,
        String parentDigest,
        List<String> inherits,
        List<String> adds,
        List<String> changes,
        List<String> removes
) {

    /**
     * One entry per concept in {@code rawSource} that declares a non-blank {@code specializes}
     * (or its {@code extends} alias -- {@link ConceptAst#getSpecializesName()} already folds both).
     * Concepts with no parent are simply absent from the map, not present with an empty lineage.
     */
    public static Map<String, ConceptLineage> computeAll(ModelAst rawSource, ModelAst resolvedModel) {
        Map<String, ConceptAst> resolvedByName = new LinkedHashMap<>();
        for (ConceptAst concept : resolvedModel.getConcepts()) {
            resolvedByName.put(concept.getName(), concept);
        }

        Map<String, ConceptLineage> lineageByName = new LinkedHashMap<>();
        for (ConceptAst raw : rawSource.getConcepts()) {
            String parent = raw.getSpecializesName();
            if (parent == null || parent.isBlank()) {
                continue;
            }
            ConceptAst resolvedBase = resolvedByName.get(parent);
            if (resolvedBase == null) {
                // Unknown/cyclic base: ModelResolver.resolve itself already refuses this model
                // (BASE_NOT_FOUND/ILLEGAL_OVERRIDE) before a lineage report could ever be asked for.
                continue;
            }

            Set<String> ownFieldNames = new LinkedHashSet<>();
            for (FieldAst field : raw.getFields()) {
                ownFieldNames.add(field.getName());
            }

            List<String> inherits = new ArrayList<>();
            for (FieldAst baseField : resolvedBase.getFields()) {
                if (!ownFieldNames.contains(baseField.getName())) {
                    inherits.add(baseField.getName());
                }
            }

            List<String> adds = new ArrayList<>(ownFieldNames);

            List<String> changes = new ArrayList<>();
            if (raw.getLifecycle() != null) {
                changes.add("lifecycle");
            }
            if (raw.getAccess() != null) {
                changes.add("access");
            }
            if (raw.getModule() != null && !raw.getModule().isBlank()) {
                changes.add("module");
            }
            if (raw.getUi() != null) {
                changes.add("ui");
            }

            String parentVersion = resolvedBase.getOrigin() == null ? null : resolvedBase.getOrigin().packVersion();
            String parentDigest = resolvedBase.getOrigin() == null ? null : resolvedBase.getOrigin().packDigest();

            lineageByName.put(raw.getName(), new ConceptLineage(
                    parent, parentVersion, parentDigest, inherits, adds, changes, List.of()));
        }
        return lineageByName;
    }
}
