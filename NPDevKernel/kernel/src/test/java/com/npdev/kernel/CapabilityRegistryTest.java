package com.npdev.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRegistryTest {

    @Test
    void registerResolveAndHasWork() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("eventBus", "adapter");

        assertTrue(registry.has("eventBus"));
        assertEquals("adapter", registry.resolve("eventBus", String.class));
        assertFalse(registry.has("missing"));
    }

    @Test
    void resolveBoundContractFromAliasAndType() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", "PersistenceCapability", new Object());

        CapabilityContract contract = registry.findContract("persistence", null).orElseThrow();
        assertEquals("PersistenceCapability", contract.getName());
        assertTrue(contract.supportsOperation("save"));

        CapabilityContract explicit = registry.findContract("anything", "PersistenceCapability").orElseThrow();
        assertNotNull(explicit);
    }

    @Test
    void resolveFailsForMissingOrWrongType() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", 123);

        assertThrows(IllegalStateException.class, () -> registry.resolve("missing", Integer.class));
        assertThrows(IllegalStateException.class, () -> registry.resolve("persistence", String.class));
    }
}
