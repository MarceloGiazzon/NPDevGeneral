package com.finalexec.npdev.service;

import com.finalexec.npdev.model.RuntimePluginManifest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * REG-359 (capability.specializes): a capability of a built-in TYPE under another name finds the built-in's adapter
 * contribution instead of failing at boot, and a type with no built-in still fails with the model's own name.
 */
class RuntimePluginAdapterRegistryBuiltInTypeTest {

    private static RuntimePluginAdapterRegistry registry() {
        return new RuntimePluginAdapterRegistry(new RuntimePluginManifest(
                "test-manifest.json", "1",
                List.of(new RuntimePluginManifest.PluginContribution(
                        "notification-inproc-plugin", "In-process notification plugin", "1.0.0", true,
                        List.of(new RuntimePluginManifest.AdapterContribution(
                                "notification", "send", "notification-inproc", "notification.send",
                                new RuntimePluginManifest.ImplementationRef("runtimeRef", "notificationInProcCapabilityAdapter")))))));
    }

    @Test
    void aCapabilityOfTheBuiltInTypeUnderAnotherNameUsesTheBuiltInsContribution() {
        RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution =
                registry().requireContribution("notificationAudit", "NotificationCapability", "", "notification-inproc");

        assertEquals("notification", contribution.capability());
        assertEquals("notificationInProcCapabilityAdapter", contribution.runtimeRef());
    }

    @Test
    void theBuiltInItselfStillResolvesByItsOwnName() {
        assertEquals("notification",
                registry().requireContribution("notification", "NotificationCapability", "send", "notification-inproc").capability());
    }

    @Test
    void aTypeWithNoBuiltInStillFailsNamingTheCapabilityTheModelDeclares() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> registry().requireContribution("vault", "VaultCapability", "", "vault-inproc"));

        assertEquals(true, refused.getMessage().contains("capability 'vault'"), refused.getMessage());
    }

    @Test
    void anAdapterTheBuiltInDoesNotOfferIsStillRefused() {
        assertThrows(IllegalStateException.class,
                () -> registry().requireContribution("notificationAudit", "NotificationCapability", "", "some-other-adapter"));
    }

    @Test
    void theBuiltInNameIsDerivedFromTheTypeOnly() {
        assertEquals("notification", RuntimePluginAdapterRegistry.builtInCapabilityOfType("NotificationCapability"));
        assertEquals("externalai", RuntimePluginAdapterRegistry.builtInCapabilityOfType("ExternalAiCapability"));
        assertEquals("", RuntimePluginAdapterRegistry.builtInCapabilityOfType("HttpConnector"));
        assertEquals("", RuntimePluginAdapterRegistry.builtInCapabilityOfType(null));
        assertEquals("", RuntimePluginAdapterRegistry.builtInCapabilityOfType("Capability"));
    }
}
