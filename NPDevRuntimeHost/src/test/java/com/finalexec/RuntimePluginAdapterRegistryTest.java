package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginManifest;
import com.finalexec.npdev.service.PluginManifestSchemaValidator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.RuntimePluginManifestLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimePluginAdapterRegistryTest {

    private final RuntimePluginManifestLoader loader = new RuntimePluginManifestLoader(
            new ObjectMapper(),
            new PluginManifestSchemaValidator()
    );

    @Test
    void resolvesDeclaredContributionFromActiveManifest() {
        RuntimePluginManifest manifest = loader.load("npdev/plugins/default.plugin-manifest.json");
        RuntimePluginAdapterRegistry registry = new RuntimePluginAdapterRegistry(manifest);

        RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution =
                registry.requireContribution("notification", "send", "notification-inproc");

        assertEquals("notification", contribution.capability());
        assertEquals("send", contribution.operation());
        assertEquals("notification-inproc", contribution.adapterId());
        assertEquals("runtimeref", contribution.implementationKind());
        assertEquals("notificationInProcCapabilityAdapter", contribution.runtimeRef());
    }

    @Test
    void rejectsUnknownContributionOutsideActiveManifest() {
        RuntimePluginManifest manifest = loader.load("npdev/plugins/default.plugin-manifest.json");
        RuntimePluginAdapterRegistry registry = new RuntimePluginAdapterRegistry(manifest);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> registry.requireContribution("notification", "send", "notification-missing-inproc")
        );

        assertEquals(
                "Adapter 'notification-missing-inproc' for capability 'notification' operation 'send' is not declared in active plugin manifest 'npdev/plugins/default.plugin-manifest.json'",
                failure.getMessage()
        );
    }
}
