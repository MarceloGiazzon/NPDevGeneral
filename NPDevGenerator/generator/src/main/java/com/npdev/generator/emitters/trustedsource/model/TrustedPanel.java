package com.npdev.generator.emitters.trustedsource.model;

public record TrustedPanel(
        String id,
        String route,
        String relativePath,
        String resourceName,
        String cssResourceName,
        String jsResourceName,
        String requiredRole,
        boolean tenantScoped,
        String source,
        String cssSource,
        String jsSource
) {
}
