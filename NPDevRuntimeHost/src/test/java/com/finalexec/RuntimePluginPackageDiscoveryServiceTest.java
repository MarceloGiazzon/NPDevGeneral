package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.RuntimePluginPackageDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginPackageDiscoveryServiceTest {

    @Test
    void discoversProjectedPackageDescriptorsFromLocationIndex() {
        RuntimePluginPackageDiscoveryService service = new RuntimePluginPackageDiscoveryService(
                new ObjectMapper(),
                "npdev/plugin-packages"
        );

        RuntimePluginPackageDiscoveryService.DiscoveryResult result = service.discover();

        assertEquals("projected-resource", result.discoveryMode());
        assertEquals("npdev/plugin-packages", result.discoveryLocation());
        assertTrue(result.indexResourcePath().endsWith("npdev/plugin-packages/index.json"));
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.resourcePath().endsWith("notification-inproc.package.json")));
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.resourcePath().endsWith("notification-warning.package.json")));
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.resourcePath().endsWith("notification-incompatible.package.json")));
    }

    @Test
    void discoversPackageDescriptorsFromFilesystemFolder(@TempDir Path tempDir) throws IOException {
        copyPackageDescriptor(tempDir, "notification-warning.package.json");
        copyPackageDescriptor(tempDir, "notification-inproc.package.json");
        Files.writeString(tempDir.resolve("ignore.txt"), "ignored");

        RuntimePluginPackageDiscoveryService service = new RuntimePluginPackageDiscoveryService(
                new ObjectMapper(),
                "npdev/plugin-packages",
                tempDir.toString()
        );

        RuntimePluginPackageDiscoveryService.DiscoveryResult result = service.discover();

        assertEquals("filesystem-folder", result.discoveryMode());
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), result.discoveryLocation());
        assertNull(result.indexResourcePath());
        assertEquals(2, result.candidates().size());
        assertTrue(result.candidates().get(0).resourcePath().endsWith("notification-inproc.package.json"));
        assertTrue(result.candidates().get(1).resourcePath().endsWith("notification-warning.package.json"));
    }

    @Test
    void supportsExplicitFilesystemDiscoveryModeHint(@TempDir Path tempDir) throws IOException {
        copyPackageDescriptor(tempDir, "notification-warning.package.json");

        RuntimePluginPackageDiscoveryService service = new RuntimePluginPackageDiscoveryService(
                new ObjectMapper(),
                "npdev/plugin-packages",
                tempDir.toString(),
                "filesystem-folder"
        );

        RuntimePluginPackageDiscoveryService.DiscoveryResult result = service.discover();

        assertEquals("filesystem-folder", result.discoveryMode());
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), result.discoveryLocation());
        assertNull(result.indexResourcePath());
        assertEquals(1, result.candidates().size());
    }

    private static void copyPackageDescriptor(Path destinationDir, String fileName) throws IOException {
        try (InputStream inputStream = RuntimePluginPackageDiscoveryServiceTest.class.getClassLoader()
                .getResourceAsStream("npdev/plugin-packages/" + fileName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing test resource: " + fileName);
            }
            Files.copy(inputStream, destinationDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
