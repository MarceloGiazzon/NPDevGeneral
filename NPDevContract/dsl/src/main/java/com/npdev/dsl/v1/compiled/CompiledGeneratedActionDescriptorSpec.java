package com.npdev.dsl.v1.compiled;

import java.util.List;

public record CompiledGeneratedActionDescriptorSpec(
        String actionName,
        List<String> affectedConcepts,
        String sideEffectConcept,
        String eventNameOnSuccess,
        String auditResourceType,
        String idempotencyPolicy,
        String tracePolicy,
        String correlationPolicy,
        boolean explicit
) {
    public CompiledGeneratedActionDescriptorSpec {
        affectedConcepts = affectedConcepts == null ? List.of() : List.copyOf(affectedConcepts);
    }
}
