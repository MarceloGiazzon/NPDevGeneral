package com.npdev.kernel.capabilities;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record CapabilityBindingManifest(List<CapabilityBindingDescriptor> bindings) {
    public CapabilityBindingManifest {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    public CapabilityBindingManifest normalized() {
        List<CapabilityBindingDescriptor> ordered = new ArrayList<>();
        for (CapabilityBindingDescriptor binding : bindings) {
            if (binding != null) {
                ordered.add(binding);
            }
        }
        ordered.sort(Comparator
                .comparing(CapabilityBindingDescriptor::capability)
                .thenComparing(CapabilityBindingDescriptor::capabilityType)
                .thenComparing(CapabilityBindingDescriptor::environment)
                .thenComparing(CapabilityBindingDescriptor::tenantId)
                .thenComparing(CapabilityBindingDescriptor::adapterId));
        return new CapabilityBindingManifest(ordered);
    }
}
