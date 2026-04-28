package com.finalexec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "npdev.runtime.surface-profile=non-default")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuntimePluginFilesystemDiscoveryIntegrationTest {

    private static final String API_KEY = "dev-key";
    private static final Path FILESYSTEM_PLUGIN_DIR = createFilesystemPluginDirectory();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerFilesystemDiscoveryProperties(DynamicPropertyRegistry registry) {
        registry.add("npdev.runtime.plugin-package-discovery-mode", () -> "filesystem-folder");
        registry.add("npdev.runtime.plugin-package-directory", () -> FILESYSTEM_PLUGIN_DIR.toString());
    }

    @Test
    void exposesFilesystemDiscoveryThroughRuntimeStatus() throws Exception {
        mockMvc.perform(get("/api/admin/runtime/plugin-status")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoveryMode").value("filesystem-folder"))
                .andExpect(jsonPath("$.discoveryLocation").value(FILESYSTEM_PLUGIN_DIR.toString()))
                .andExpect(jsonPath("$.discoveredPackages").isArray())
                .andExpect(jsonPath("$.discoveredPackages[0].packageId").value("notification-incompatible-package"))
                .andExpect(jsonPath("$.admittedPackages").isArray())
                .andExpect(jsonPath("$.discoveryOperationalMode.modeActive").value(true))
                .andExpect(jsonPath("$.governance.rejectedPackageCount").value(2))
                .andExpect(jsonPath("$.statusAudit.selection.selectedRealizations").isArray())
                .andExpect(jsonPath("$.externalMediumDemo.rejectedUntrustedPackage.rejectionCode").value("UNSUPPORTED_TRUST_MODE"))
                .andExpect(jsonPath("$.trust.policy.allowedModes").isArray())
                .andExpect(jsonPath("$.admittedPackageIds[0]").value("notification-inproc-package"))
                .andExpect(jsonPath("$.resourceOwnership.externalPluginResources").isArray())
                .andExpect(jsonPath("$.resourceOwnership.filesystemPackageDescriptorResources").isArray());

        mockMvc.perform(get("/api/admin/runtime/plugin-packages")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discoveryMode").value("filesystem-folder"))
                .andExpect(jsonPath("$.packages[0].packageId").value("notification-incompatible-package"))
                .andExpect(jsonPath("$.rejectedPackages[1].rejectionCode").value("UNSUPPORTED_TRUST_MODE"));
    }

    private static Path createFilesystemPluginDirectory() {
        try {
            Path tempDir = Files.createTempDirectory("npdev-plugin-dir-");
            copyDescriptor(tempDir, "notification-incompatible.package.json");
            copyDescriptor(tempDir, "notification-inproc.package.json");
            copyDescriptor(tempDir, "notification-untrusted.package.json");
            copyDescriptor(tempDir, "notification-warning.package.json");
            return tempDir.toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void copyDescriptor(Path destinationDir, String fileName) throws IOException {
        try (InputStream inputStream = RuntimePluginFilesystemDiscoveryIntegrationTest.class.getClassLoader()
                .getResourceAsStream("npdev/plugin-packages/" + fileName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing test resource: " + fileName);
            }
            Files.copy(inputStream, destinationDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
