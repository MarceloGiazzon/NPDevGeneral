package com.npdev.kernel.capabilities;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeOverrideCapabilityBindingResolverTest {

    @Test
    void runtimeOverrideWinsWhenItConflictsWithStaticBinding() {
        CapabilityBindingDescriptor staticBinding = new CapabilityBindingDescriptor(
                "notification", "messaging", "static-adapter", null, null, null);
        CapabilityBindingDescriptor overrideTarget = new CapabilityBindingDescriptor(
                "notification", "messaging", "override-adapter", null, null, null);
        CapabilityBindingManifest manifest = new CapabilityBindingManifest(List.of(staticBinding, overrideTarget));

        RuntimeOverridesManifest.CapabilityOverride override =
                new RuntimeOverridesManifest.CapabilityOverride("notification", "messaging", null, null, "override-adapter");
        RuntimeOverrideCapabilityBindingResolver resolver =
                new RuntimeOverrideCapabilityBindingResolver(manifest, new RuntimeOverridesManifest(List.of(override)));

        Optional<CapabilityBindingDescriptor> result = resolver.resolve("notification", "messaging", null, null);

        assertTrue(result.isPresent());
        assertEquals("override-adapter", result.get().adapterId());

        // Without any override the static binding is selected.
        CapabilityBindingManifest staticOnly = new CapabilityBindingManifest(List.of(staticBinding));
        RuntimeOverrideCapabilityBindingResolver noOverrideResolver =
                new RuntimeOverrideCapabilityBindingResolver(staticOnly, new RuntimeOverridesManifest(List.of()));
        Optional<CapabilityBindingDescriptor> staticResult = noOverrideResolver.resolve("notification", "messaging", null, null);

        assertTrue(staticResult.isPresent());
        assertEquals("static-adapter", staticResult.get().adapterId());
    }

    @Test
    void capabilityBindingFailureModesAreExplicit() {
        CapabilityBindingDescriptor staticBinding = new CapabilityBindingDescriptor(
                "notification", "messaging", "static-adapter", null, null, null);

        // non-existent capability with an empty manifest -> empty
        RuntimeOverrideCapabilityBindingResolver emptyResolver =
                new RuntimeOverrideCapabilityBindingResolver(new CapabilityBindingManifest(List.of()), new RuntimeOverridesManifest(List.of()));
        assertTrue(emptyResolver.resolve("does-not-exist", "messaging", null, null).isEmpty());

        // capability present in the manifest but for a different capability -> empty
        RuntimeOverrideCapabilityBindingResolver otherResolver =
                new RuntimeOverrideCapabilityBindingResolver(
                        new CapabilityBindingManifest(List.of(staticBinding)),
                        new RuntimeOverridesManifest(List.of()));
        assertTrue(otherResolver.resolve("other", "messaging", null, null).isEmpty());

        // an override pointing at an unbound adapter falls back to the static binding
        RuntimeOverridesManifest.CapabilityOverride ghostOverride =
                new RuntimeOverridesManifest.CapabilityOverride("notification", "messaging", null, null, "ghost-adapter");
        RuntimeOverrideCapabilityBindingResolver ghostResolver =
                new RuntimeOverrideCapabilityBindingResolver(
                        new CapabilityBindingManifest(List.of(staticBinding)),
                        new RuntimeOverridesManifest(List.of(ghostOverride)));
        Optional<CapabilityBindingDescriptor> ghostResult = ghostResolver.resolve("notification", "messaging", null, null);
        assertTrue(ghostResult.isPresent());
        assertEquals("static-adapter", ghostResult.get().adapterId());

        // blank capability name is a hard contract violation
        assertThrows(IllegalArgumentException.class, () -> emptyResolver.resolve("  ", "messaging", null, null));
        assertThrows(IllegalArgumentException.class, () -> emptyResolver.resolve(null, "messaging", null, null));
    }
}