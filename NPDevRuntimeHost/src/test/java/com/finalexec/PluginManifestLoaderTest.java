package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginManifestSchemaValidator;
import com.finalexec.npdev.service.RuntimePluginManifestLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManifestLoaderTest {

    private final RuntimePluginManifestLoader loader = new RuntimePluginManifestLoader(
            new ObjectMapper(),
            new PluginManifestSchemaValidator()
    );

    @Test
    void loadsDefaultPluginManifest() {
        RuntimePluginManifest manifest = loader.load("npdev/plugins/default.plugin-manifest.json");

        assertEquals("1.0", manifest.manifestVersion());
        assertTrue(manifest.toSummary().activeAdapterIds().contains("notification-inproc"));
        assertTrue(manifest.toSummary().plugins().stream()
                .anyMatch(plugin -> "notification-inproc-plugin".equals(plugin.pluginId())));
    }

    @Test
    void loadsAlternatePluginManifest() {
        RuntimePluginManifest manifest = loader.load("npdev/plugins/warning.plugin-manifest.json");

        assertEquals("1.0", manifest.manifestVersion());
        assertTrue(manifest.toSummary().activeAdapterIds().contains("notification-warning-inproc"));
        assertTrue(manifest.toSummary().plugins().stream()
                .anyMatch(plugin -> "notification-warning-plugin".equals(plugin.pluginId())));
    }

    @Test
    void loadsPluginManifestFromFilesystem(@TempDir Path tempDir) throws IOException {
        Path manifestPath = copyManifest(tempDir, "default.plugin-manifest.json");

        RuntimePluginManifest manifest = loader.load(manifestPath.toString());

        assertEquals("1.0", manifest.manifestVersion());
        assertTrue(manifest.toSummary().activeAdapterIds().contains("notification-inproc"));
        assertTrue(manifest.toSummary().plugins().stream()
                .anyMatch(plugin -> "notification-inproc-plugin".equals(plugin.pluginId())));
    }

    private static Path copyManifest(Path destinationDir, String fileName) throws IOException {
        try (InputStream inputStream = PluginManifestLoaderTest.class.getClassLoader()
                .getResourceAsStream("npdev/plugins/" + fileName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing test resource: " + fileName);
            }
            Path target = destinationDir.resolve(fileName);
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }
}
