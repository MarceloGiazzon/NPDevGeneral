package com.finalexec;

import com.finalexec.npdev.service.RuntimePluginProfileResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginProfileResolverTest {

    @Test
    void resolvesDefaultProfileResources() {
        RuntimePluginProfileResolver.ResolvedRuntimePluginProfile resolved = new RuntimePluginProfileResolver(
                "default",
                "",
                "",
                ""
        ).resolve();

        assertEquals("default", resolved.activeProfile());
        assertEquals("dev", resolved.executionEnvironment());
        assertEquals("profile-fallback", resolved.selectionMode());
        assertEquals("npdev/bindings/dev.bindings.json", resolved.bindingsManifestPath());
        assertEquals("npdev/plugins/default.plugin-manifest.json", resolved.pluginManifestPath());
    }

    @Test
    void resolvesWarningProfileResources() {
        RuntimePluginProfileResolver.ResolvedRuntimePluginProfile resolved = new RuntimePluginProfileResolver(
                "warning",
                "",
                "",
                ""
        ).resolve();

        assertEquals("warning", resolved.activeProfile());
        assertEquals("alt", resolved.executionEnvironment());
        assertEquals("profile-fallback", resolved.selectionMode());
        assertEquals("npdev/bindings/alt.bindings.json", resolved.bindingsManifestPath());
        assertEquals("npdev/plugins/warning.plugin-manifest.json", resolved.pluginManifestPath());
    }

    @Test
    void explicitManifestPathsOverrideProfileFallback() {
        RuntimePluginProfileResolver.ResolvedRuntimePluginProfile resolved = new RuntimePluginProfileResolver(
                "custom",
                "npdev/bindings/dev.bindings.json",
                "npdev/plugins/default.plugin-manifest.json",
                "dev"
        ).resolve();

        assertEquals("custom", resolved.activeProfile());
        assertEquals("dev", resolved.executionEnvironment());
        assertEquals("explicit", resolved.selectionMode());
        assertEquals("npdev/bindings/dev.bindings.json", resolved.bindingsManifestPath());
        assertEquals("npdev/plugins/default.plugin-manifest.json", resolved.pluginManifestPath());
    }

    @Test
    void rejectsUnknownProfileWithoutExplicitOverrides() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new RuntimePluginProfileResolver("mismatch", "", "", "").resolve()
        );

        assertTrue(failure.getMessage().contains("Unknown deployment plugin profile"));
    }
}
