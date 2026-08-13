package com.finalexec.npdev.model;

import java.util.List;

public record RuntimePluginRepositoryDescriptor(
        String repositoryId,
        String displayName,
        String repositoryType,
        String endpoint,
        String trustMode,
        boolean signatureRequired,
        List<String> packageIds
) {
    public RuntimePluginRepositoryDescriptor {
        repositoryId = normalize(repositoryId, "default-repository");
        displayName = normalize(displayName, repositoryId);
        repositoryType = normalize(repositoryType, "http-json");
        endpoint = normalize(endpoint, "");
        trustMode = normalize(trustMode, "declared");
        packageIds = packageIds == null ? List.of() : List.copyOf(packageIds);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
