package com.npdev.cli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.capabilities.CapabilityBindingDescriptor;
import com.npdev.kernel.capabilities.CapabilityBindingManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CapabilityBindingManifestLoader {

    private final ObjectMapper objectMapper;

    public CapabilityBindingManifestLoader() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public CapabilityBindingManifestLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CapabilityBindingManifest load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must be non-null");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Binding manifest not found: " + path);
        }

        CapabilityBindingManifestFile file = objectMapper.readValue(path.toFile(), CapabilityBindingManifestFile.class);
        List<CapabilityBindingDescriptor> bindings = new ArrayList<>();
        if (file != null && file.bindings() != null) {
            for (CapabilityBindingManifestFile.BindingEntry entry : file.bindings()) {
                if (entry == null) {
                    continue;
                }
                bindings.add(new CapabilityBindingDescriptor(
                        entry.capability(),
                        entry.capabilityType(),
                        entry.adapterId(),
                        entry.adapterClass(),
                        entry.environment(),
                        entry.tenantId()
                ));
            }
        }
        return new CapabilityBindingManifest(bindings).normalized();
    }
}
