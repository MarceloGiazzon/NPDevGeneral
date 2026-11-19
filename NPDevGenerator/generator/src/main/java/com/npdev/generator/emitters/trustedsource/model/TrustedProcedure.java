package com.npdev.generator.emitters.trustedsource.model;

import java.util.Map;

public record TrustedProcedure(
        String id,
        String relativePath,
        String className,
        String method,
        String requiredRole,
        boolean tenantScoped,
        Map<String, Object> metadata,
        String source
) {
}
