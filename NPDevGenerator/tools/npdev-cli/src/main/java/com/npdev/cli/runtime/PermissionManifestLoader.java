package com.npdev.cli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.security.PermissionGrant;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PermissionManifestLoader {

    private final ObjectMapper objectMapper;

    public PermissionManifestLoader() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public PermissionManifestLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<PermissionGrant> load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must be non-null");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Permission manifest not found: " + path);
        }

        PermissionManifestFile file = objectMapper.readValue(path.toFile(), PermissionManifestFile.class);
        List<PermissionGrant> grants = new ArrayList<>();
        if (file != null && file.grants() != null) {
            for (PermissionManifestFile.PermissionGrantEntry entry : file.grants()) {
                if (entry == null) {
                    continue;
                }
                grants.add(new PermissionGrant(
                        entry.permission(),
                        entry.tenantId(),
                        entry.actorId(),
                        entry.role()
                ));
            }
        }
        return List.copyOf(grants);
    }
}
