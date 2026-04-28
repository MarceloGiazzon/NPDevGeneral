package com.finalexec.npdev.migration;

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
}
