package com.npdev.dsl.v1.repo;

import java.nio.file.Path;

public record ModelArtifact(
        String name,
        String hash,
        Path rootDir,
        Path modelJsonPath,
        Path compiledModelJsonPath,
        Path compiledMetadataJsonPath,
        Path manifestJsonPath
) {
}
