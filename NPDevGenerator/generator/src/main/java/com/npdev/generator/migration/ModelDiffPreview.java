package com.npdev.generator.migration;

import java.util.List;

public record ModelDiffPreview(
        boolean deterministicDiff,
        String previousVersion,
        String currentVersion,
        String previousHash,
        String currentHash,
        int operationCount,
        List<MigrationOperation> operations,
        List<String> additiveChanges,
        List<String> riskyChanges,
        List<String> breakingChanges
) {
    public ModelDiffPreview {
        previousVersion = previousVersion == null || previousVersion.isBlank() ? "none" : previousVersion.trim();
        currentVersion = currentVersion == null || currentVersion.isBlank() ? "unknown" : currentVersion.trim();
        previousHash = previousHash == null || previousHash.isBlank() ? "none" : previousHash.trim();
        currentHash = currentHash == null || currentHash.isBlank() ? "unknown" : currentHash.trim();
        operations = operations == null ? List.of() : List.copyOf(operations);
        additiveChanges = additiveChanges == null ? List.of() : List.copyOf(additiveChanges);
        riskyChanges = riskyChanges == null ? List.of() : List.copyOf(riskyChanges);
        breakingChanges = breakingChanges == null ? List.of() : List.copyOf(breakingChanges);
        operationCount = operations.size();
    }
}
