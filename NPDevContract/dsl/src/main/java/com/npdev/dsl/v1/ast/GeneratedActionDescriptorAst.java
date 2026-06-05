package com.npdev.dsl.v1.ast;

import java.util.List;

public record GeneratedActionDescriptorAst(
        String actionName,
        List<String> affectedConcepts,
        String sideEffectConcept,
        String eventNameOnSuccess,
        String auditResourceType,
        String idempotencyPolicy,
        String tracePolicy,
        String correlationPolicy
) {
    public GeneratedActionDescriptorAst {
        affectedConcepts = affectedConcepts == null ? List.of() : List.copyOf(affectedConcepts);
    }
}
