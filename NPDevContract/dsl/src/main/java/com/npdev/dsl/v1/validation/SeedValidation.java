package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.SeedAst;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * R8.8 (Roadmap Wave 2, 2026-08-19): structural checks for the optional top-level {@code seeds}
 * declaration -- {@code concept} required and referencing a concept that actually exists, plus
 * {@code alias} uniqueness across EVERY declared seed (not just within one pack), the same
 * discipline {@link WebhookValidation}/{@link SequenceValidation} apply to their own identity
 * field.
 *
 * <p>Pack/context OWNERSHIP of a seed's target concept is deliberately NOT re-checked here -- it
 * is enforced eagerly, and unconditionally, at pack-composition time
 * ({@code ModelSourceResolver.rewriteSeedConceptOwnership}, called from
 * {@code mergeQualifiedNonConceptArrays}'s own "seeds" branch) as a compile error thrown before
 * this AST layer is ever reached, so an unowned pack-declared seed can never survive to be checked
 * here -- re-validating it would be dead code. This class instead covers what composition-time
 * check cannot: a ROOT-declared seed (no pack/context, so no ownership concept applies) naming a
 * concept that does not exist at all, and a duplicate alias -- possibly declared by two DIFFERENT
 * packs, or a pack and the root -- which would otherwise silently collide in {@code
 * SeedDataService}/{@code ModelSeedRunner}'s single flat {@code aliasToId} map for one seed run
 * (the second declaration's alias silently overwrites the first's entry, so a {@code "$ref:<alias>"}
 * elsewhere resolves to the WRONG row instead of failing loudly).
 */
final class SeedValidation {

    private SeedValidation() {
    }

    static void validateSeeds(ModelAst modelAst, List<String> errors) {
        List<SeedAst> seeds = modelAst.getSeeds();
        if (seeds.isEmpty()) {
            return;
        }
        Set<String> conceptNames = new HashSet<>();
        for (ConceptAst concept : modelAst.getConcepts()) {
            conceptNames.add(normalize(concept.getName()));
        }

        Set<String> aliasesSeen = new HashSet<>();
        for (int index = 0; index < seeds.size(); index++) {
            SeedAst seed = seeds.get(index);
            String here = "Seed[" + index + "]" + (hasText(seed.concept()) ? " (concept " + seed.concept() + ")" : "");
            if (!hasText(seed.concept())) {
                errors.add(here + ": concept is required -- suggestedFix: add 'concept' naming an "
                        + "existing concept to this seed record");
            } else if (!conceptNames.contains(normalize(seed.concept()))) {
                errors.add(here + ": references unknown concept " + seed.concept()
                        + " -- suggestedFix: declare " + seed.concept() + " in concepts[], or fix the "
                        + "typo in this seed's 'concept'");
            }
            if (hasText(seed.alias()) && !aliasesSeen.add(normalize(seed.alias()))) {
                errors.add(here + ": duplicate seed alias '" + seed.alias() + "' -- suggestedFix: every "
                        + "declared seed's alias must be unique across the whole model (including across "
                        + "different packs), since $ref:" + seed.alias() + " would otherwise resolve "
                        + "ambiguously at seed-run time");
            }
        }
    }
}
