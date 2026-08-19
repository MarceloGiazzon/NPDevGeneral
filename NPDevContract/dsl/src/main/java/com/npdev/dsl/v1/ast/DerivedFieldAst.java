package com.npdev.dsl.v1.ast;

import java.util.Map;

/**
 * Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md): a display-only value recomputed from the
 * current Aggregate Workbench draft. Retires the untyped {@code transaction.metadata.derived}
 * list -- authored as an object keyed by {@code name} (guaranteeing uniqueness by construction,
 * unlike the retired list). {@code tier} "client" (default) evaluates {@code expression} locally
 * (the same narrow expression language the retired {@code derived} key used); "server" invokes
 * {@code procedure} over the draft instead.
 */
public record DerivedFieldAst(
        String name,
        String label,
        String tier,
        String expression,
        String procedure,
        Map<String, String> labelLocales
) {
    public DerivedFieldAst {
        tier = tier == null || tier.isBlank() ? "client" : tier;
        labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }
}
