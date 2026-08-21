package com.finalexec.trustedsource;

import java.nio.file.Path;
import java.util.List;

/**
 * Wave 4B / trusted-source-path-traversal scenario: resolves an entrypoint path against allowed
 * roots. Rejects absolute paths, drive-qualified paths, UNC paths, URI-like paths, and traversal
 * segments. Normalized path must remain under one of the allowed roots.
 */
public class SourceDiscovery {

    /**
     * Resolves {@code entrypoint} against the first matching allowed root.
     *
     * @throws SecurityException if the path contains traversal segments or escapes all roots
     */
    public Path resolve(String entrypoint, List<Path> allowedRoots) {
        if (entrypoint == null || entrypoint.isBlank()) {
            throw new SecurityException("Trusted source entrypoint must not be blank");
        }

        // Reject obvious bad patterns
        if (entrypoint.contains("..") || entrypoint.contains("~")
                || entrypoint.startsWith("/") || entrypoint.startsWith("\\")
                || entrypoint.matches("^[A-Za-z]:.*")
                || entrypoint.startsWith("\\\\")
                || entrypoint.contains("://")) {
            throw new SecurityException(
                    "Trusted source path traversal rejected: " + entrypoint);
        }

        // Normalize and check containment
        for (Path root : allowedRoots) {
            Path resolved = root.resolve(entrypoint).normalize();
            if (resolved.startsWith(root.normalize())) {
                return resolved;
            }
        }
        throw new SecurityException(
                "Trusted source entrypoint escapes allowed roots: " + entrypoint);
    }
}
