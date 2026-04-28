package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.model.RuntimePluginPackageDescriptor;
import com.finalexec.npdev.service.PluginManifestSchemaValidator;
import com.finalexec.npdev.service.PluginPackageSchemaValidator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.RuntimePluginManifestLoader;
import com.finalexec.npdev.service.RuntimePluginPackageAdmissionEvaluator;
import com.finalexec.npdev.service.RuntimePluginPackageCatalog;
import com.finalexec.npdev.service.RuntimePluginPackageDiscoveryService;
import com.finalexec.npdev.service.RuntimePluginPackageDescriptorLoader;
import com.finalexec.npdev.service.RuntimePluginPackageRealizationService;
import com.finalexec.npdev.service.RuntimePluginRealizationProvider;
import com.finalexec.npdev.service.RuntimePluginArtifactRealizationProvider;
import com.finalexec.npdev.service.RuntimeRefArtifactRealizationProvider;
import com.finalexec.npdev.service.ClasspathArtifactRealizationProvider;
import com.finalexec.npdev.service.FilesystemArtifactRealizationProvider;
import com.finalexec.npdev.service.RuntimePluginPackagedArtifactHandlerResolver;
import com.finalexec.npdev.service.RuntimePluginPackageCompatibilityEvaluator;
import com.finalexec.npdev.service.RuntimePluginPackageTrustEvaluator;
import com.finalexec.npdev.service.RuntimePluginRuntimeRefResolver;
import com.npdev.kernel.ports.CapabilityAdapter;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginPackageRealizationServiceTest {

    private final RuntimePluginManifestLoader manifestLoader = new RuntimePluginManifestLoader(
            new ObjectMapper(),
            new PluginManifestSchemaValidator()
    );

    @Test
    void realizesNotificationContributionThroughPackageAwareStrategy() {
        RuntimePluginManifest manifest = manifestLoader.load("npdev/plugins/default.plugin-manifest.json");
        RuntimePluginAdapterRegistry registry = new RuntimePluginAdapterRegistry(manifest);
        RuntimePluginPackageRealizationService service = realizationService("npdev/plugins/default.plugin-manifest.json");

        RuntimePluginPackageRealizationService.RealizedAdapter realizedAdapter =
                service.realize(registry.requireContribution("notification", "send", "notification-inproc"));

        assertEquals(StubNotificationAdapter.class, realizedAdapter.handler().getClass());
        assertTrue(realizedAdapter.summary().packageBacked());
        assertEquals("runtimeRefBundle", realizedAdapter.summary().realizationStrategy());
        assertEquals("classpath-artifact", realizedAdapter.summary().artifactRealizationStrategy());
        assertEquals("classpath-artifact-provider", realizedAdapter.summary().artifactRealizationProvider());
        assertEquals("notification-inproc-package", realizedAdapter.summary().selectedPackageId());
        assertEquals("1.0.0", realizedAdapter.summary().selectedPackageVersion());
        assertEquals("npdev/plugin-packages/notification-inproc.package.json", realizedAdapter.summary().selectedPackagePath());
    }

    @Test
    void fallsBackToDirectRuntimeRefRealizationForUnpackagedContribution() {
        RuntimePluginManifest manifest = manifestLoader.load("npdev/plugins/default.plugin-manifest.json");
        RuntimePluginAdapterRegistry registry = new RuntimePluginAdapterRegistry(manifest);
        RuntimePluginPackageRealizationService service = realizationService("npdev/plugins/default.plugin-manifest.json");

        RuntimePluginPackageRealizationService.RealizedAdapter realizedAdapter =
                service.realize(registry.requireContribution("persistence", "save", "memory"));

        assertEquals(StubPersistenceAdapter.class, realizedAdapter.handler().getClass());
        assertFalse(realizedAdapter.summary().packageBacked());
        assertEquals("runtimeRefDirect", realizedAdapter.summary().realizationStrategy());
        assertEquals("runtime-ref-direct", realizedAdapter.summary().artifactRealizationStrategy());
        assertEquals("runtime-ref-artifact-provider", realizedAdapter.summary().artifactRealizationProvider());
    }

    @Test
    void realizesFilesystemDiscoveredPackageThroughFilesystemArtifactProvider(@TempDir Path tempDir) throws IOException {
        copyDescriptor(tempDir, "notification-incompatible.package.json");
        copyDescriptor(tempDir, "notification-inproc.package.json");
        copyDescriptor(tempDir, "notification-untrusted.package.json");
        copyDescriptor(tempDir, "notification-warning.package.json");

        RuntimePluginManifest manifest = manifestLoader.load("npdev/plugins/default.plugin-manifest.json");
        RuntimePluginAdapterRegistry registry = new RuntimePluginAdapterRegistry(manifest);
        RuntimePluginPackageRealizationService service = realizationService(
                filesystemPackageCatalog(tempDir),
                "npdev/plugins/default.plugin-manifest.json"
        );

        RuntimePluginPackageRealizationService.RealizedAdapter realizedAdapter =
                service.realize(registry.requireContribution("notification", "send", "notification-inproc"));

        assertEquals(StubNotificationAdapter.class, realizedAdapter.handler().getClass());
        assertTrue(realizedAdapter.summary().packageBacked());
        assertEquals("runtimeRefBundle", realizedAdapter.summary().realizationStrategy());
        assertEquals("filesystem-artifact", realizedAdapter.summary().artifactRealizationStrategy());
        assertEquals("filesystem-artifact-provider", realizedAdapter.summary().artifactRealizationProvider());
        assertEquals("notification-inproc-package", realizedAdapter.summary().selectedPackageId());
        assertTrue(String.valueOf(realizedAdapter.summary().selectedPackagePath()).contains(tempDir.toString()));
    }

    @Test
    void realizesWarningContributionThroughSelectedWarningPackage() {
        RuntimePluginManifest manifest = manifestLoader.load("npdev/plugins/warning.plugin-manifest.json");
        RuntimePluginAdapterRegistry registry = new RuntimePluginAdapterRegistry(manifest);
        RuntimePluginPackageRealizationService service = realizationService("npdev/plugins/warning.plugin-manifest.json");

        RuntimePluginPackageRealizationService.RealizedAdapter realizedAdapter =
                service.realize(registry.requireContribution("notification", "send", "notification-warning-inproc"));

        assertEquals(StubWarningNotificationAdapter.class, realizedAdapter.handler().getClass());
        assertTrue(realizedAdapter.summary().packageBacked());
        assertEquals("notification-warning-package", realizedAdapter.summary().selectedPackageId());
        assertEquals("1.0.0", realizedAdapter.summary().selectedPackageVersion());
        assertEquals("npdev/plugin-packages/notification-warning.package.json", realizedAdapter.summary().selectedPackagePath());
        assertEquals("runtimeRefBundle", realizedAdapter.summary().realizationStrategy());
        assertEquals("classpath-artifact", realizedAdapter.summary().artifactRealizationStrategy());
        assertEquals("classpath-artifact-provider", realizedAdapter.summary().artifactRealizationProvider());
    }

    @Test
    void carriesSelectedFilesystemManifestPackageIdentityIntoDirectRuntimeRefExecution(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(
                tempDir.resolve("notification-valid.package.json"),
                """
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
                              "operations": [
                                "send"
                              ]
                            }
                          ]
                        }
                        """,
                StandardCharsets.UTF_8
        );

        RuntimePluginManifest manifest = manifestLoader.load("npdev/plugins/dev.plugin-manifest.json");
        RuntimePluginAdapterRegistry registry = new RuntimePluginAdapterRegistry(manifest);
        RuntimePluginPackageRealizationService service = realizationService(
                filesystemPackageCatalog(tempDir),
                "npdev/plugins/dev.plugin-manifest.json"
        );

        RuntimePluginPackageRealizationService.RealizedAdapter realizedAdapter =
                service.realize(registry.requireContribution("notification", "send", "notification-warning-inproc"));

        assertEquals(StubWarningNotificationAdapter.class, realizedAdapter.handler().getClass());
        assertFalse(realizedAdapter.summary().packageBacked());
        assertEquals("notification-valid", realizedAdapter.summary().selectedPackageId());
        assertEquals("1.0.0", realizedAdapter.summary().selectedPackageVersion());
        assertTrue(String.valueOf(realizedAdapter.summary().selectedPackagePath()).contains(tempDir.toString()));
        assertEquals("runtimeRefDirect", realizedAdapter.summary().realizationStrategy());
    }

    private static RuntimePluginPackageRealizationService realizationService(String activePluginManifestPath) {
        return realizationService(packageCatalog(), activePluginManifestPath);
    }

    private static RuntimePluginPackageRealizationService realizationService(
            RuntimePluginPackageCatalog packageCatalog,
            String activePluginManifestPath
    ) {
        RuntimePluginRuntimeRefResolver runtimeRefResolver = new RuntimePluginRuntimeRefResolver(List.of(
                provider("notificationInProcCapabilityAdapter", new StubNotificationAdapter()),
                provider("notificationWarningCapabilityAdapter", new StubWarningNotificationAdapter()),
                provider("persistenceInMemoryCapabilityAdapter", new StubPersistenceAdapter())
        ));
        RuntimePluginPackagedArtifactHandlerResolver packagedArtifactHandlerResolver =
                new RuntimePluginPackagedArtifactHandlerResolver(runtimeRefResolver);
        List<RuntimePluginArtifactRealizationProvider> artifactRealizationProviders = List.of(
                new ClasspathArtifactRealizationProvider(packagedArtifactHandlerResolver),
                new FilesystemArtifactRealizationProvider(packagedArtifactHandlerResolver),
                new RuntimeRefArtifactRealizationProvider(runtimeRefResolver)
        );
        return new RuntimePluginPackageRealizationService(
                packageCatalog,
                activePluginManifestPath,
                artifactRealizationProviders
        );
    }

    private static RuntimePluginPackageCatalog packageCatalog() {
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimePluginPackageDiscoveryService discoveryService =
                new RuntimePluginPackageDiscoveryService(objectMapper, "npdev/plugin-packages");
        RuntimePluginPackageDescriptorLoader descriptorLoader =
                new RuntimePluginPackageDescriptorLoader(objectMapper, new PluginPackageSchemaValidator());
        RuntimePluginPackageAdmissionEvaluator admissionEvaluator =
                new RuntimePluginPackageAdmissionEvaluator(
                        new RuntimePluginPackageCompatibilityEvaluator(
                                "1.0",
                                "1.0.0",
                                "0.1.0",
                                "1.0",
                                "npdev/plugins/default.plugin-manifest.json"
                        ),
                        new RuntimePluginPackageTrustEvaluator(java.util.List.of("internal", "local-dev"), true)
                );

        RuntimePluginPackageDiscoveryService.DiscoveryResult discoveryResult = discoveryService.discover();
        List<RuntimePluginPackageCatalog.PackageCatalogEntry> entries = discoveryResult.candidates().stream()
                .map(candidate -> toCatalogEntry(candidate, descriptorLoader, admissionEvaluator))
                .toList();
        return new RuntimePluginPackageCatalog(
                discoveryResult,
                admissionEvaluator.toSummary(),
                admissionEvaluator.trustPolicySummary(),
                entries
        );
    }

    private static RuntimePluginPackageCatalog filesystemPackageCatalog(Path pluginPackageDirectory) {
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimePluginPackageDiscoveryService discoveryService =
                new RuntimePluginPackageDiscoveryService(objectMapper, "npdev/plugin-packages", pluginPackageDirectory.toString());
        RuntimePluginPackageDescriptorLoader descriptorLoader =
                new RuntimePluginPackageDescriptorLoader(objectMapper, new PluginPackageSchemaValidator());
        RuntimePluginPackageAdmissionEvaluator admissionEvaluator =
                new RuntimePluginPackageAdmissionEvaluator(
                        new RuntimePluginPackageCompatibilityEvaluator(
                                "1.0",
                                "1.0.0",
                                "0.1.0",
                                "1.0",
                                "npdev/plugins/default.plugin-manifest.json"
                        ),
                        new RuntimePluginPackageTrustEvaluator(java.util.List.of("internal", "local-dev"), true)
                );

        RuntimePluginPackageDiscoveryService.DiscoveryResult discoveryResult = discoveryService.discover();
        List<RuntimePluginPackageCatalog.PackageCatalogEntry> entries = discoveryResult.candidates().stream()
                .map(candidate -> toCatalogEntry(candidate, descriptorLoader, admissionEvaluator))
                .toList();
        return new RuntimePluginPackageCatalog(
                discoveryResult,
                admissionEvaluator.toSummary(),
                admissionEvaluator.trustPolicySummary(),
                entries
        );
    }

    private static RuntimePluginPackageCatalog.PackageCatalogEntry toCatalogEntry(
            RuntimePluginPackageDiscoveryService.DiscoveredPackageCandidate candidate,
            RuntimePluginPackageDescriptorLoader descriptorLoader,
            RuntimePluginPackageAdmissionEvaluator admissionEvaluator
    ) {
        try {
            RuntimePluginPackageDescriptor descriptor = descriptorLoader.load(candidate.resourcePath());
            return new RuntimePluginPackageCatalog.PackageCatalogEntry(
                    candidate,
                    descriptor,
                    admissionEvaluator.evaluate(descriptor)
            );
        } catch (RuntimeException exception) {
            return new RuntimePluginPackageCatalog.PackageCatalogEntry(
                    candidate,
                    null,
                    RuntimePluginPackageAdmissionEvaluator.AdmissionDecision.reject(
                            "DESCRIPTOR_LOAD_FAILED",
                            exception.getMessage()
                    )
            );
        }
    }

    private static RuntimePluginRealizationProvider provider(String runtimeRef, Object handler) {
        return new RuntimePluginRealizationProvider() {
            @Override
            public String runtimeRef() {
                return runtimeRef;
            }

            @Override
            public Object realize() {
                return handler;
            }
        };
    }

    static final class StubNotificationAdapter implements CapabilityAdapter {
        @Override
        public String capability() {
            return "notification";
        }

        @Override
        public String adapterId() {
            return "notification-inproc";
        }

        @Override
        public com.npdev.kernel.CapabilityResult invoke(com.npdev.kernel.CapabilityCall call, Map<String, Object> contextState) {
            return com.npdev.kernel.CapabilityResult.success("ok");
        }
    }

    static final class StubWarningNotificationAdapter implements CapabilityAdapter {
        @Override
        public String capability() {
            return "notification";
        }

        @Override
        public String adapterId() {
            return "notification-warning-inproc";
        }

        @Override
        public com.npdev.kernel.CapabilityResult invoke(com.npdev.kernel.CapabilityCall call, Map<String, Object> contextState) {
            return com.npdev.kernel.CapabilityResult.success("warn");
        }
    }

    static final class StubPersistenceAdapter implements CapabilityAdapter {
        @Override
        public String capability() {
            return "persistence";
        }

        @Override
        public String adapterId() {
            return "memory";
        }

        @Override
        public com.npdev.kernel.CapabilityResult invoke(com.npdev.kernel.CapabilityCall call, Map<String, Object> contextState) {
            return com.npdev.kernel.CapabilityResult.success("saved");
        }
    }

    private static void copyDescriptor(Path destinationDir, String fileName) throws IOException {
        try (InputStream inputStream = RuntimePluginPackageRealizationServiceTest.class.getClassLoader()
                .getResourceAsStream("npdev/plugin-packages/" + fileName)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing test resource: " + fileName);
            }
            Files.copy(inputStream, destinationDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
