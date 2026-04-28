package com.npdev.cli.runtime;

import java.nio.file.Path;

public record CliRuntimeOptions(
        Path modelPath,
        Path simulationPath,
        Path storeDir,
        Path permissionManifestPath
) {
}
