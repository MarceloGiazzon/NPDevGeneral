package com.npdev.dsl.v1.paths;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CanonicalModelPaths {
    private static final String MODEL_ENV = "NPDEV_MODEL_PATH";
    private static final String MODEL_PROP = "npdev.model.path";
    private static final Path FALLBACK = Path.of("model.json").toAbsolutePath().normalize();
    private static final String[] SPLIT_WORKSPACE_MARKERS = {
            "NPDevContract",
            "NPDevGenerator",
            "NPDevKernel",
            "NPDevRuntimeHost",
            "NPDevSamples"
    };

    private CanonicalModelPaths() {
    }

    public static Path defaultModelPath() {
        String fromProperty = normalize(System.getProperty(MODEL_PROP));
        if (fromProperty != null) {
            return Path.of(fromProperty).toAbsolutePath().normalize();
        }

        String fromEnv = normalize(System.getenv(MODEL_ENV));
        if (fromEnv != null) {
            return Path.of(fromEnv).toAbsolutePath().normalize();
        }

        Path fromWorkspace = resolveWorkspaceDefault(Path.of("").toAbsolutePath().normalize());
        if (fromWorkspace != null) {
            return fromWorkspace;
        }
        return FALLBACK;
    }

    public static Path defaultCompiledModelPath() {
        return Path.of("compiled-model.json").toAbsolutePath().normalize();
    }


    public static Path defaultRepositoryDir() {
        return Path.of("repository").toAbsolutePath().normalize();
    }

    public static Path defaultRepositoryModelsDir() {
        return defaultRepositoryDir().resolve("models").toAbsolutePath().normalize();
    }

    private static Path resolveWorkspaceDefault(Path start) {
        Path workspaceRoot = resolveWorkspaceRoot(start);
        if (workspaceRoot == null) {
            return null;
        }
        Path sampleModel = workspaceRoot.resolve("NPDevSamples")
                .resolve("canonical-demo")
                .resolve("Input")
                .resolve("model.json")
                .toAbsolutePath()
                .normalize();
        return Files.isRegularFile(sampleModel) ? sampleModel : null;
    }

    private static Path resolveWorkspaceRoot(Path start) {
        Path current = start;
        while (current != null) {
            boolean matches = true;
            for (String marker : SPLIT_WORKSPACE_MARKERS) {
                if (!Files.isDirectory(current.resolve(marker))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return current.toAbsolutePath().normalize();
            }
            current = current.getParent();
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
