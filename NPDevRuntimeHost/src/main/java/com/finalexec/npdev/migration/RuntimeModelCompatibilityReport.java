package com.finalexec.npdev.migration;

import java.util.List;

public record RuntimeModelCompatibilityReport(
        boolean compatible,
        String compatibilityStatus,
        String runtimeBuildVersion,
        String runtimeBuiltAt,
        String baselineVersion,
        String modelVersion,
        String previousHash,
        String currentHash,
        String overallRisk,
        List<String> warnings,
        List<String> blockingIssues
) {
}
