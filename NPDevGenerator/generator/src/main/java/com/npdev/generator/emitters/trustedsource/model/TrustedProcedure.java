package com.npdev.generator.emitters.trustedsource.model;

import com.npdev.dsl.v1.compiled.CompiledGeneratedActionDescriptorSpec;

import java.util.Map;

public record TrustedProcedure(
        String id,
        String relativePath,
        String className,
        String method,
        String requiredRole,
        boolean tenantScoped,
        CompiledGeneratedActionDescriptorSpec actionDescriptor,
        Map<String, Object> metadata,
        String source
) {
}
