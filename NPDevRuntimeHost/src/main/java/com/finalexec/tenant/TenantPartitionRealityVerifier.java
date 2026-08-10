package com.finalexec.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TenantPartitionRealityVerifier {

    private static final Path TENANT_PARTITION_ROOT = Path.of("runtime-data", "tenant-partitions");

    private final ObjectMapper objectMapper;

    public TenantPartitionRealityVerifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> inspect() {
        List<Map<String, Object>> tenants = new ArrayList<>();
        int tenantCount = 0;
        int evidenceCount = 0;
        int ownershipMismatchCount = 0;

        try {
            if (Files.exists(TENANT_PARTITION_ROOT)) {
                try (var stream = Files.list(TENANT_PARTITION_ROOT)) {
                    for (Path tenantRoot : stream.filter(Files::isDirectory).toList()) {
                        String tenantId = tenantRoot.getFileName().toString();
                        Map<String, Object> tenantReport = inspectTenant(tenantId, tenantRoot);
                        tenants.add(tenantReport);
                        tenantCount++;
                        evidenceCount += intValue(tenantReport.get("evidenceCount"));
                        ownershipMismatchCount += intValue(tenantReport.get("ownershipMismatchCount"));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tenantCount", tenantCount);
        report.put("partitionRoot", TENANT_PARTITION_ROOT.toString().replace("\\", "/"));
        report.put("evidenceCount", evidenceCount);
        report.put("ownershipMismatchCount", ownershipMismatchCount);
        report.put("isolationStatus", ownershipMismatchCount == 0 ? "OWNERSHIP_CONSISTENT" : "OWNERSHIP_MISMATCH_DETECTED");
        report.put("tenants", tenants);
        return report;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inspectTenant(String tenantId, Path tenantRoot) {
        int evidenceCount = 0;
        int ownershipMismatchCount = 0;
        List<String> mismatches = new ArrayList<>();

        try {
            // try-with-resources (QUAL-2): Files.walk holds an open handle for EVERY directory it
            // descends into, so a leak here costs more than one -- and .toList() does not release
            // them. See TemplateLibraryManagementService for why this matters on Windows.
            List<Path> evidenceFiles;
            try (var walk = Files.walk(tenantRoot)) {
                evidenceFiles = walk.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".json"))
                        .toList();
            }
            for (Path file : evidenceFiles) {
                evidenceCount++;
                if ("partition-summary.json".equalsIgnoreCase(file.getFileName().toString())) {
                    continue;
                }

                try {
                    Map<String, Object> record = objectMapper.readValue(file.toFile(), LinkedHashMap.class);
                    Object rawTenantId = record.get("tenantId");
                    if (rawTenantId != null && !tenantId.equals(String.valueOf(rawTenantId).trim())) {
                        ownershipMismatchCount++;
                        mismatches.add(file.getFileName().toString());
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("tenantId", tenantId);
        report.put("tenantRoot", tenantRoot.toString().replace("\\", "/"));
        report.put("evidenceCount", evidenceCount);
        report.put("ownershipMismatchCount", ownershipMismatchCount);
        report.put("mismatches", mismatches);
        return report;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }
}
