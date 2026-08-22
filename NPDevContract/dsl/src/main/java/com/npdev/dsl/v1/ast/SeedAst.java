package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

/**
 * R8.8 (Roadmap Wave 2, 2026-08-19): a model/pack-declared seed row, reusing the EXISTING
 * app-level seed record shape ({@code NPDevContract/schemas/seed.schema.json}'s {@code $defs/record}
 * -- {@code concept}/{@code alias}/{@code id}/{@code data}/{@code repeatOver}/{@code count}) rather
 * than inventing a second one. Unlike that app-level convention (a separate {@code
 * definition/seeds/*.json} file, "Not seen by the model compiler", loaded on demand by {@code
 * SeedDataService} for an operator-triggered run), a model-declared seed IS part of the compiled
 * model and is applied automatically, once, at first boot (see {@code
 * com.finalexec.seed.ModelSeedRunner} in NPDevRuntimeHost).
 *
 * <p><b>Ownership.</b> A PACK- or CONTEXT-declared seed's {@code concept} must name a concept the
 * SAME pack/context declares -- rewritten to its pack-qualified form ({@code packId::Concept}) at
 * pack-composition time ({@code ModelSourceResolver.mergeQualifiedNonConceptArrays}'s own
 * "seeds" branch), and a seed naming a concept the pack does NOT own is a compile error there,
 * thrown eagerly rather than deferred to this AST layer -- unlike every other {@code
 * MODEL_ARRAY_KEYS} kind, a seed's {@code concept} reference is deliberately NEVER resolved
 * cross-pack by the later global unqualified-reference pass ({@code
 * ModelSourceResolver.resolveUnqualifiedReferences}): inserting rows into a concept another pack
 * owns, unattended, at that pack's own first boot, is a materially different hazard than merely
 * reading/joining it, so "concept happens to be globally unique" must never silently stand in for
 * "concept is owned here". A ROOT-declared seed (the app's own model.json, not a pack) has no such
 * restriction, exactly like a root-declared query or panel may reference any of the app's own
 * concepts freely.
 *
 * @param concept        the concept this seed row targets (already pack-qualified by composition
 *                        time when pack/context-declared).
 * @param alias           optional local name a LATER seed record (anywhere in the fully resolved
 *                        model, not just the same pack) can reference via {@code "$ref:<alias>"}.
 *                        Only single (non-bulk) records may carry one -- schema-enforced
 *                        (mutually exclusive with {@code repeatOver}/{@code count}).
 * @param id              optional explicit id; a generated UUID is used at seed-run time when
 *                        absent.
 * @param data            field values, same shape as a POST body to the target concept. May use
 *                        {@code "$ref:<alias>"} / {@code "$gen:<generator>[:<args>]"} placeholders,
 *                        resolved at seed-run time exactly like the app-level convention.
 * @param repeatOverVars  bulk-generation ranges ({@code var -> [min, max]} inclusive), mutually
 *                        exclusive with {@code count}/{@code alias}. Empty when not declared.
 * @param count           shorthand bulk-generation count (N copies of {@code data}, no index
 *                        variables), mutually exclusive with {@code repeatOverVars}/{@code alias}.
 *                        Null when not declared.
 */
public record SeedAst(
        String concept,
        String alias,
        String id,
        Map<String, Object> data,
        Map<String, List<Integer>> repeatOverVars,
        Integer count
) {
    public SeedAst {
        data = data == null ? Map.of() : Map.copyOf(data);
        repeatOverVars = repeatOverVars == null ? Map.of() : Map.copyOf(repeatOverVars);
    }
}
