package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.TenantStoragePartitioningRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TenantStoragePartitioningService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-tenant-storage-partitioning/tenant-storage-partitioning-rules.json";

    private static final Path PARTITIONING_ROOT =
            Path.of("runtime-data", "tenant-storage-partitioning");

    private final ObjectMapper objectMapper;
    private final PublicationChainReferenceResolver referenceResolver;
    private final TenantStoragePathResolver tenantStoragePathResolver;

    public TenantStoragePartitioningService(
            ObjectMapper objectMapper,
            PublicationChainReferenceResolver referenceResolver,
            TenantStoragePathResolver tenantStoragePathResolver
    ) {
        this.objectMapper = objectMapper;
        this.referenceResolver = referenceResolver;
        this.tenantStoragePathResolver = tenantStoragePathResolver;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();
        List<String> tenantPartitions = discoverTenantPartitions();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Tenant Storage Partitioning v1"));
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", PARTITIONING_ROOT.toString().replace("\\", "/"));
        response.put("partitionRoot", String.valueOf(rules.getOrDefault("partitionRoot", "runtime-data/tenant-partitions")));
        response.put("mode", rules.getOrDefault("mode", "tenant-storage-partitioning-v1"));
        response.put("partitionReality", rules.getOrDefault(
                "partitionReality",
                "storage-level partitioning of tenant-scoped runtime and governance evidence under tenant-specific roots"
        ));
        response.put("supportedScopes", rules.getOrDefault("supportedScopes", List.of()));
        response.put("partitionTargets", rules.getOrDefault("partitionTargets", List.of()));
        response.put("tenantPartitionCount", tenantPartitions.size());
        response.put("tenantPartitions", tenantPartitions);
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = referenceResolver.readRecords(PARTITIONING_ROOT);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> apply(TenantStoragePartitioningRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String tenantId = request.getTenantId().trim();
        String partitioningId = UUID.randomUUID().toString();
        String appliedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "tenant-storage-partitioning-v1"));

        List<Map<String, Object>> targets = normalizeTargets(rules.get("partitionTargets"));
        Set<String> allowedCategories = categoriesForScope(request.getScope().trim());
        List<Map<String, Object>> appliedTargets = new ArrayList<>();
        int copiedRecordCount = 0;
        int skippedRecordCount = 0;

        for (Map<String, Object> target : targets) {
            String category = stringValue(target.get("category"));
            if (!allowedCategories.contains(category)) {
                continue;
            }

            Path sourceRoot = Path.of(stringValue(target.get("path")));
            Path partitionRoot = tenantStoragePathResolver.targetRoot(
                    tenantId,
                    category,
                    stringValue(target.get("name"))
            );

            Map<String, Object> targetResult = partitionTarget(tenantId, sourceRoot, partitionRoot, target);
            copiedRecordCount += intValue(targetResult.get("copiedRecordCount"));
            skippedRecordCount += intValue(targetResult.get("skippedRecordCount"));
            appliedTargets.add(targetResult);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tenantId", tenantId);
        summary.put("scope", request.getScope().trim());
        summary.put("partitionRoot", tenantStoragePathResolver.tenantRoot(tenantId).toString().replace("\\", "/"));
        summary.put("copiedRecordCount", copiedRecordCount);
        summary.put("skippedRecordCount", skippedRecordCount);
        summary.put("appliedTargetCount", appliedTargets.size());
        summary.put("partitionCategories", new ArrayList<>(allowedCategories));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("tenantStoragePartitioningId", partitioningId);
        record.put("partitionReference", request.getPartitionReference().trim());
        record.put("tenantId", tenantId);
        record.put("scope", request.getScope().trim());
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("partitionMode", request.getPartitionMode().trim());
        record.put("appliedTargets", appliedTargets);
        record.put("partitionSummary", summary);
        record.put("appliedAt", appliedAt);
        record.put("mode", mode);
        record.put("status", "PARTITION_APPLIED");

        persistRecord(partitioningId, record);
        persistTenantSummary(tenantId, record);
        return record;
    }

    private Map<String, Object> partitionTarget(
            String tenantId,
            Path sourceRoot,
            Path partitionRoot,
            Map<String, Object> target
    ) {
        List<String> copiedFiles = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        int copiedCount = 0;
        int skippedCount = 0;

        try {
            Files.createDirectories(partitionRoot);
            if (!Files.exists(sourceRoot) || !Files.isDirectory(sourceRoot)) {
                return targetResult(target, partitionRoot, copiedFiles, skippedFiles, copiedCount, skippedCount, "SOURCE_MISSING");
            }

            try (var stream = Files.list(sourceRoot)) {
                for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    Map<String, Object> record = readJson(path);
                    if (record == null) {
                        skippedCount++;
                        skippedFiles.add(path.getFileName().toString() + ":unreadable");
                        continue;
                    }

                    String recordTenantId = referenceResolver.extractFirstString(record, "tenantId");
                    if (!tenantId.equals(recordTenantId)) {
                        skippedCount++;
                        skippedFiles.add(path.getFileName().toString() + ":tenant-mismatch");
                        continue;
                    }

                    Path targetFile = partitionRoot.resolve(path.getFileName().toString());
                    Files.copy(path, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    copiedCount++;
                    copiedFiles.add(targetFile.getFileName().toString());
                }
            }
        } catch (Exception e) {
            skippedCount++;
            skippedFiles.add("partition-error:" + e.getClass().getSimpleName());
        }

        return targetResult(target, partitionRoot, copiedFiles, skippedFiles, copiedCount, skippedCount, "PARTITIONED");
    }

    private Map<String, Object> targetResult(
            Map<String, Object> target,
            Path partitionRoot,
            List<String> copiedFiles,
            List<String> skippedFiles,
            int copiedCount,
            int skippedCount,
            String status
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetName", stringValue(target.get("name")));
        result.put("category", stringValue(target.get("category")));
        result.put("sourcePath", stringValue(target.get("path")));
        result.put("partitionPath", partitionRoot.toString().replace("\\", "/"));
        result.put("copiedRecordCount", copiedCount);
        result.put("skippedRecordCount", skippedCount);
        result.put("copiedFiles", copiedFiles);
        result.put("skippedFiles", skippedFiles);
        result.put("status", status);
        return result;
    }

    private void persistTenantSummary(String tenantId, Map<String, Object> record) {
        try {
            Path summaryPath = tenantStoragePathResolver.partitionSummaryPath(tenantId);
            Files.createDirectories(summaryPath.getParent());

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("tenantId", tenantId);
            summary.put("latestPartitionReference", record.get("partitionReference"));
            summary.put("latestPartitioningId", record.get("tenantStoragePartitioningId"));
            summary.put("partitionSummary", record.get("partitionSummary"));
            summary.put("appliedTargets", record.get("appliedTargets"));
            summary.put("status", "TENANT_PARTITION_READY");

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(summaryPath.toFile(), summary);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist tenant partition summary.", e);
        }
    }

    private List<Map<String, Object>> normalizeTargets(Object raw) {
        List<Map<String, Object>> targets = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Map<?, ?> rawMap) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    rawMap.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    targets.add(normalized);
                }
            }
        }
        return targets;
    }

    private Set<String> categoriesForScope(String scope) {
        Set<String> categories = new LinkedHashSet<>();
        if ("publication-chain-evidence".equals(scope)) {
            categories.add("publication");
        } else if ("rollback-and-recovery-evidence".equals(scope)) {
            categories.add("rollback-recovery");
        } else if ("preview-evidence".equals(scope)) {
            categories.add("preview");
        } else if ("scenario-and-proof-evidence".equals(scope)) {
            categories.add("proof");
        } else {
            categories.add("publication");
            categories.add("rollback-recovery");
            categories.add("preview");
            categories.add("proof");
        }
        return categories;
    }

    private List<String> discoverTenantPartitions() {
        List<String> tenants = new ArrayList<>();
        try {
            Path root = tenantStoragePathResolver.tenantRoot("sample").getParent();
            if (root == null || !Files.exists(root)) {
                return tenants;
            }
            try (var stream = Files.list(root)) {
                stream.filter(Files::isDirectory)
                        .forEach(path -> tenants.add(path.getFileName().toString()));
            }
        } catch (Exception ignored) {
        }
        tenants.sort(String::compareTo);
        return tenants;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void validate(TenantStoragePartitioningRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getPartitionReference())) {
            throw new IllegalArgumentException("partitionReference is required.");
        }
        if (isBlank(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required.");
        }
        if (isBlank(request.getScope())) {
            throw new IllegalArgumentException("scope is required.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
        if (isBlank(request.getRationale())) {
            throw new IllegalArgumentException("rationale is required.");
        }
        if (isBlank(request.getPartitionMode())) {
            throw new IllegalArgumentException("partitionMode is required.");
        }
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load tenant storage partitioning rules.", e);
        }
    }

    private void persistRecord(String partitioningId, Map<String, Object> record) {
        try {
            Files.createDirectories(PARTITIONING_ROOT);
            Path output = PARTITIONING_ROOT.resolve(partitioningId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist tenant storage partitioning record.", e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
