package com.finalexec.npdev.service;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class TenantStoragePathResolver {

    private static final Path TENANT_PARTITION_ROOT = Paths.get("runtime-data", "tenant-partitions");

    public Path tenantRoot(String tenantId) {
        return TENANT_PARTITION_ROOT.resolve(sanitizeTenantId(tenantId));
    }

    public Path categoryRoot(String tenantId, String category) {
        return tenantRoot(tenantId).resolve(sanitizeSegment(category));
    }

    public Path targetRoot(String tenantId, String category, String targetName) {
        return categoryRoot(tenantId, category).resolve(sanitizeSegment(targetName));
    }

    public Path partitionSummaryPath(String tenantId) {
        return tenantRoot(tenantId).resolve("partition-summary.json");
    }

    private String sanitizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "global";
        }
        return sanitizeSegment(tenantId);
    }

    private String sanitizeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
