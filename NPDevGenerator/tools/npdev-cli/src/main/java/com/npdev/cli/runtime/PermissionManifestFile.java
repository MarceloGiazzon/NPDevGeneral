package com.npdev.cli.runtime;

import java.util.List;

public record PermissionManifestFile(List<PermissionGrantEntry> grants) {

    public record PermissionGrantEntry(
            String permission,
            String tenantId,
            String actorId,
            String role
    ) {
    }
}
