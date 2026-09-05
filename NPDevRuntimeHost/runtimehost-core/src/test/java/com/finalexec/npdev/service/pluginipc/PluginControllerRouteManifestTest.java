package com.finalexec.npdev.service.pluginipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B30/SEC-9: {@link PluginControllerRouteManifestLoader} loads the real fixture at
 * {@code src/test/resources/npdev/plugin-runtime/plugin-controller-routes.json} (shared with
 * {@link ManifestDrivenJavaControllerPluginHandlerTest}); this covers the manifest's own
 * longest-basePath-prefix matching -- the resolution {@link PluginControllerProxyHandler} needs to
 * pick which mount an incoming request path belongs to.
 */
class PluginControllerRouteManifestTest {

    @Test
    void loadsTheFixtureManifestFromTheClasspath() {
        PluginControllerRouteManifest manifest = new PluginControllerRouteManifestLoader(new ObjectMapper()).load();

        assertFalse(manifest.isEmpty());
        PluginControllerRouteManifest.Entry entry = manifest.entryForCapability("sampleController").orElseThrow();
        assertEquals("com.finalexec.npdev.service.pluginipc.fixtures.SampleControllerForHandlerTest", entry.controllerClassName());
        assertEquals(3, entry.routes().size());
    }

    @Test
    void matchesTheBasePathAndAnythingUnderIt() {
        PluginControllerRouteManifest manifest = new PluginControllerRouteManifestLoader(new ObjectMapper()).load();

        assertTrue(manifest.entryForRequestPath("/api/plugins/sample").isPresent());
        assertTrue(manifest.entryForRequestPath("/api/plugins/sample/users/42").isPresent());
        assertTrue(manifest.entryForRequestPath("/api/plugins/sample-other").isEmpty());
        assertTrue(manifest.entryForRequestPath("/api/plugins/unmounted").isEmpty());
    }

    @Test
    void anEmptyManifestIsTheNoMountsSignal() {
        PluginControllerRouteManifest manifest = PluginControllerRouteManifest.empty();

        assertTrue(manifest.isEmpty());
        assertTrue(manifest.entryForRequestPath("/api/plugins/anything").isEmpty());
        assertTrue(manifest.entryForCapability("anything").isEmpty());
    }

    @Test
    void longestBasePathWinsWhenTwoMountsCouldBothMatch() {
        PluginControllerRouteManifest manifest = new PluginControllerRouteManifest(java.util.Map.of(
                "outer", new PluginControllerRouteManifest.Entry("outer", "com.example.Outer", "/api/plugins/sample", List.of()),
                "inner", new PluginControllerRouteManifest.Entry("inner", "com.example.Inner", "/api/plugins/sample/nested", List.of())
        ));

        PluginControllerRouteManifest.Entry match = manifest.entryForRequestPath("/api/plugins/sample/nested/thing").orElseThrow();

        assertEquals("inner", match.capability());
    }
}
