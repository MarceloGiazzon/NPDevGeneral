package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginPackageDescriptor;
import com.finalexec.npdev.service.PluginPackageSchemaValidator;
import com.finalexec.npdev.service.RuntimePluginPackageDescriptorLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginPackageDescriptorLoaderTest {

    private final RuntimePluginPackageDescriptorLoader loader = new RuntimePluginPackageDescriptorLoader(
            new ObjectMapper(),
            new PluginPackageSchemaValidator()
    );

    @Test
    void loadsNotificationInProcPackageDescriptor() {
        RuntimePluginPackageDescriptor descriptor =
                loader.load("npdev/plugin-packages/notification-inproc.package.json");

        assertEquals("1.0", descriptor.packageFormatVersion());
        assertEquals("notification-inproc-package", descriptor.packageId());
        assertEquals("internal", descriptor.trust().mode());
        assertEquals("npdev/plugins/default.plugin-manifest.json", descriptor.pluginManifest().path());
        assertEquals(1, descriptor.capabilities().size());
        assertEquals("notification", descriptor.capabilities().get(0).capability());
        assertEquals("send", descriptor.capabilities().get(0).operation());
        assertEquals("notification-inproc", descriptor.capabilities().get(0).adapterId());
        assertTrue(descriptor.artifacts().stream()
                .anyMatch(artifact -> "built-in://notification-inproc".equals(artifact.path())));
    }

    @Test
    void loadsNotificationWarningPackageDescriptor() {
        RuntimePluginPackageDescriptor descriptor =
                loader.load("npdev/plugin-packages/notification-warning.package.json");

        assertEquals("1.0", descriptor.packageFormatVersion());
        assertEquals("notification-warning-package", descriptor.packageId());
        assertEquals("trusted", descriptor.trust().level());
        assertEquals("npdev/plugins/warning.plugin-manifest.json", descriptor.pluginManifest().path());
        assertEquals(1, descriptor.capabilities().size());
        assertEquals("notification-warning-inproc", descriptor.capabilities().get(0).adapterId());
        assertTrue(descriptor.artifacts().stream()
                .anyMatch(artifact -> "built-in://notification-warning".equals(artifact.path())));
    }

    @Test
    void loadsPackageDescriptorFromFilesystem(@TempDir Path tempDir) throws IOException {
        Path descriptorPath = copyPackageDescriptor(tempDir, "notification-inproc.package.json");

        RuntimePluginPackageDescriptor descriptor = loader.load(descriptorPath.toString());

        assertEquals(descriptorPath.toAbsolutePath().normalize().toString(), descriptor.packagePath());
        assertEquals("notification-inproc-package", descriptor.packageId());
    }

    @Test
    void loadsStep1ExternalManifestFromFilesystem(@TempDir Path tempDir) throws IOException {
        Path descriptorPath = tempDir.resolve("notification-valid.package.json");
        Files.writeString(descriptorPath, """
                {
                  "packageId": "notification-valid",
                  "version": "1.0.0",
                  "trustLevel": "trusted",
                  "compatibility": {
                    "npdevMinVersion": "0.1.0",
                    "npdevMaxVersion": "0.9.999"
                  },
                  "capabilities": [
                    {
                      "capability": "notification",
                      "adapterId": "notification-warning-external",
                      "operations": ["send"]
                    }
                  ]
                }
                """);

        RuntimePluginPackageDescriptor descriptor = loader.load(descriptorPath.toString());

        assertEquals("manifest-v1", descriptor.packageFormatVersion());
        assertEquals("notification-valid", descriptor.packageId());
        assertEquals("local-dev", descriptor.trust().mode());
        assertEquals("0.1.0", descriptor.compatibility().npdevMinVersion());
        assertEquals("0.9.999", descriptor.compatibility().npdevMaxVersion());
        assertEquals(1, descriptor.capabilities().size());
        assertEquals("notification-warning-external", descriptor.capabilities().get(0).adapterId());
        assertTrue(descriptor.artifacts().isEmpty());
    }

    private static Path copyPackageDescriptor(Path destinationDir, String fileName) throws IOException {
        Path target = destinationDir.resolve(fileName);
        try (InputStream inputStream = PluginPackageDescriptorLoaderTest.class.getClassLoader()
                .getResourceAsStream("npdev/plugin-packages/" + fileName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing test resource: " + fileName);
            }
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
