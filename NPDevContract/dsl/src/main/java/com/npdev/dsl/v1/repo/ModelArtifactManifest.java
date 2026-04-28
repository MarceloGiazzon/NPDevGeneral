package com.npdev.dsl.v1.repo;

import java.util.Map;

public record ModelArtifactManifest(
        String name,
        String hash,
        String createdAtUtc,
        Map<String, String> files
) {
}
