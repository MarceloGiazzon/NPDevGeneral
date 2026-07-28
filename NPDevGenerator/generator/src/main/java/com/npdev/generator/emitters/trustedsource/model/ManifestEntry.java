package com.npdev.generator.emitters.trustedsource.model;

public record ManifestEntry(
        String entryId,
        String kind,
        String relativePath,
        String language,
        String sha256,
        String runtimeBinding,
        String className,
        String method,
        String requiredRole,
        boolean tenantScoped
) {
}
