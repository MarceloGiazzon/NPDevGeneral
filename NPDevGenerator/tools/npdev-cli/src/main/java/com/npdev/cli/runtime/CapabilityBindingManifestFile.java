package com.npdev.cli.runtime;

import java.util.List;

public record CapabilityBindingManifestFile(List<BindingEntry> bindings) {

    public record BindingEntry(
            String capability,
            String capabilityType,
            String adapterId,
            String adapterClass,
            String environment,
            String tenantId
    ) {
    }
}
