package com.npdev.generator.emitters.trustedsource.model;

import java.util.List;

/**
 * Path A P5.1: {@code inputs}/{@code outputs}/{@code dependencies}/{@code tests}/{@code truthStatus}
 * are the object-manifest attributes the untrusted-extension zone declares for a code-bearing
 * object, alongside the identity/source/permissions/tenancy fields above. Additive on the existing
 * {@code npdev-untrusted-extension-manifest.v1} schema (not a version bump): an author who omits
 * them gets safe defaults from {@link com.npdev.generator.emitters.TrustedSourceManifest#readManifest},
 * matching the platform's truth-classification rule that declaring nothing should never block
 * generation -- only a false claim (an unproven high {@code truthStatus}) does.
 */
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
        boolean tenantScoped,
        List<String> inputs,
        List<String> outputs,
        List<String> dependencies,
        List<String> tests,
        String truthStatus
) {
}
