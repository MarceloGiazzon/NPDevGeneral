package com.npdev.generator.emitters.trustedsource.model;

public record TrustedReference(
        String kind,
        String id,
        String route,
        String relativePath,
        String requiredRole,
        Object source
) {
}
