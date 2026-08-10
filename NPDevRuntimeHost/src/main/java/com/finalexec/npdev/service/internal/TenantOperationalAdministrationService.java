package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.TenantOperationalAdministrationRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TenantOperationalAdministrationService {

    private static final String TENANT_CATALOG_CLASSPATH_LOCATION = "npdev-tenant-admin/tenant-admin-catalog.json";
    private static final String TENANT_MEANING_CLASSPATH_LOCATION = "tenant-admin-meaning/tenant-admin-meaning-catalog.json";

    private static final Path ONBOARDING_ROOT = Paths.get("runtime-data", "spreadsheet-onboarding-requests");
    private static final Path EXECUTION_ROOT = Paths.get("runtime-data", "import-executions");
    private static final Path ANALYSIS_ROOT = Paths.get("runtime-data", "import-conflict-analyses");
    private static final Path CORRECTION_ROOT = Paths.get("runtime-data", "import-corrections");
    private static final Path DRAFT_ROOT = Paths.get("runtime-data", "working-draft-systems");
    private static final Path CHECKLIST_ROOT = Paths.get("runtime-data", "launch-checklists");
    private static final Path BUNDLE_ROOT = Paths.get("runtime-data", "explainability-bundles");
    private static final Path COMPOSITION_ROOT = Paths.get("runtime-data", "capability-compositions");
    private static final Path SNAPSHOT_ROOT = Paths.get("runtime-data", "tenant-admin-snapshots");

    private final ObjectMapper objectMapper;
    private final TenantNativeGovernanceService tenantNativeGovernanceService;

    public TenantOperationalAdministrationService(
            ObjectMapper objectMapper,
            TenantNativeGovernanceService tenantNativeGovernanceService
    ) {
        this.objectMapper = objectMapper;
        this.tenantNativeGovernanceService = tenantNativeGovernanceService;
    }

    public Map<String, Object> summary() {
        Map<String, Object> catalog = loadJsonCatalog(TENANT_CATALOG_CLASSPATH_LOCATION);
        Map<String, Object> meaning = loadJsonCatalog(TENANT_MEANING_CLASSPATH_LOCATION);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("catalogPath", TENANT_CATALOG_CLASSPATH_LOCATION);
        response.put("meaningPath", TENANT_MEANING_CLASSPATH_LOCATION);
        response.put("version", catalog.getOrDefault("version", 1));
        response.put("tenants", catalog.getOrDefault("tenants", List.of()));
        response.put("readinessLevels", meaning.getOrDefault("readinessLevels", List.of()));
        response.put("currentSurfaceMeans", meaning.getOrDefault("currentSurfaceMeans", List.of()));
        response.put("currentSurfaceDoesNotMean", meaning.getOrDefault("currentSurfaceDoesNotMean", List.of()));
        response.put("snapshotStoragePath", SNAPSHOT_ROOT.toString().replace("\\", "/"));
        response.put("mode", "tenant-aware-administration-with-native-governance-guidance");
        response.put("tenantNativeGovernance", tenantNativeGovernanceService.summary());
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = new ArrayList<>();

        try {
            // try-with-resources (QUAL-2) -- see TemplateLibraryManagementService for the full note.
            if (Files.exists(SNAPSHOT_ROOT)) {
                try (var paths = Files.list(SNAPSHOT_ROOT)) {
                    paths
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .forEach(path -> {
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> item = objectMapper.readValue(path.toFile(), LinkedHashMap.class);
                                    items.add(item);
                                } catch (Exception ignored) {
                                }
                            });
                }
            }
        } catch (Exception ignored) {
        }

        items.sort(Comparator.comparing(
                item -> String.valueOf(item.getOrDefault("inspectedAt", "")),
                Comparator.reverseOrder()
        ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> inspect(TenantOperationalAdministrationRequest request) {
        validate(request);

        Map<String, Object> tenant = findTenant(request.getTenantId());
        if (tenant == null) {
            throw new IllegalArgumentException("Referenced tenantId does not exist in tenant admin catalog.");
        }

        int tenantTaggedCount =
                countTenantTaggedRecords(ONBOARDING_ROOT, request.getTenantId()) +
                countTenantTaggedRecords(EXECUTION_ROOT, request.getTenantId()) +
                countTenantTaggedRecords(ANALYSIS_ROOT, request.getTenantId()) +
                countTenantTaggedRecords(CORRECTION_ROOT, request.getTenantId()) +
                countTenantTaggedRecords(DRAFT_ROOT, request.getTenantId()) +
                countTenantTaggedRecords(CHECKLIST_ROOT, request.getTenantId()) +
                countTenantTaggedRecords(BUNDLE_ROOT, request.getTenantId()) +
                countTenantTaggedRecords(COMPOSITION_ROOT, request.getTenantId());

        int untaggedCount =
                countUntaggedRecords(ONBOARDING_ROOT) +
                countUntaggedRecords(EXECUTION_ROOT) +
                countUntaggedRecords(ANALYSIS_ROOT) +
                countUntaggedRecords(CORRECTION_ROOT) +
                countUntaggedRecords(DRAFT_ROOT) +
                countUntaggedRecords(CHECKLIST_ROOT) +
                countUntaggedRecords(BUNDLE_ROOT) +
                countUntaggedRecords(COMPOSITION_ROOT);

        String taggingHealth;
        if (tenantTaggedCount > 0) {
            taggingHealth = "TENANT_TAGGED_ACTIVITY_PRESENT";
        } else if (untaggedCount > 0) {
            taggingHealth = "UNTAGGED_RUNTIME_DATA_PRESENT";
        } else {
            taggingHealth = "NO_RUNTIME_ACTIVITY";
        }

        String inspectionId = UUID.randomUUID().toString();
        String inspectedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> visibility = new LinkedHashMap<>();
        visibility.put("tenantId", tenant.get("tenantId"));
        visibility.put("tenantTitle", tenant.get("title"));
        visibility.put("allowedScopes", tenant.get("allowedScopes"));
        visibility.put("notes", tenant.get("notes"));

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("tenantTaggedRecordCount", tenantTaggedCount);
        counts.put("untaggedRecordCount", untaggedCount);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("inspectionId", inspectionId);
        record.put("tenantId", request.getTenantId());
        record.put("adminViewName", request.getAdminViewName());
        record.put("requestedBy", request.getRequestedBy());
        record.put("inspectedAt", inspectedAt);
        record.put("visibility", visibility);
        record.put("counts", counts);
        record.put("taggingHealth", taggingHealth);
        record.put("readinessLevel", 1);
        record.put("status", "INSPECTED");
        record.put("governanceGuidance", guidanceFor(taggingHealth));

        persistSnapshot(inspectionId, record);

        Map<String, Object> response = new LinkedHashMap<>(record);
        response.put("tenantNativeGovernanceMode", tenantNativeGovernanceService.summary().get("mode"));
        response.put("message", "Tenant operational administration snapshot captured.");
        return response;
    }

    private void validate(TenantOperationalAdministrationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (request.getTenantId() == null || request.getTenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required.");
        }
        if (request.getAdminViewName() == null || request.getAdminViewName().isBlank()) {
            throw new IllegalArgumentException("adminViewName is required.");
        }
        if (request.getRequestedBy() == null || request.getRequestedBy().isBlank()) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
    }

    private Map<String, Object> loadJsonCatalog(String classpathLocation) {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load classpath resource: " + classpathLocation, e);
        }
    }

    private Map<String, Object> findTenant(String tenantId) {
        Map<String, Object> catalog = loadJsonCatalog(TENANT_CATALOG_CLASSPATH_LOCATION);
        Object tenantsObject = catalog.get("tenants");
        if (!(tenantsObject instanceof List<?> list)) {
            return null;
        }

        for (Object item : list) {
            if (item instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tenant = (Map<String, Object>) rawMap;
                if (tenantId.equals(String.valueOf(tenant.get("tenantId")))) {
                    return tenant;
                }
            }
        }

        return null;
    }

    private int countTenantTaggedRecords(Path root, String tenantId) {
        try {
            if (!Files.exists(root)) {
                return 0;
            }

            int count = 0;
            // try-with-resources (QUAL-2): the Stream must be closed even though .toList()
            // materialises it -- terminal operations do not release the directory handle.
            List<Path> files;
            try (var paths = Files.list(root)) {
                files = paths
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .toList();
            }

            for (Path file : files) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> record = objectMapper.readValue(file.toFile(), LinkedHashMap.class);
                    Object value = record.get("tenantId");
                    if (value != null && tenantId.equals(String.valueOf(value))) {
                        count++;
                    }
                } catch (Exception ignored) {
                }
            }

            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int countUntaggedRecords(Path root) {
        try {
            if (!Files.exists(root)) {
                return 0;
            }

            int count = 0;
            // try-with-resources (QUAL-2): the Stream must be closed even though .toList()
            // materialises it -- terminal operations do not release the directory handle.
            List<Path> files;
            try (var paths = Files.list(root)) {
                files = paths
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .toList();
            }

            for (Path file : files) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> record = objectMapper.readValue(file.toFile(), LinkedHashMap.class);
                    if (!record.containsKey("tenantId") || record.get("tenantId") == null || String.valueOf(record.get("tenantId")).isBlank()) {
                        count++;
                    }
                } catch (Exception ignored) {
                }
            }

            return count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private List<String> guidanceFor(String taggingHealth) {
        return switch (taggingHealth) {
            case "TENANT_TAGGED_ACTIVITY_PRESENT" -> List.of(
                    "Tenant-tagged activity is present.",
                    "Use tenant-native governance actions for boundary strengthening where needed."
            );
            case "UNTAGGED_RUNTIME_DATA_PRESENT" -> List.of(
                    "Runtime-data remains mostly untagged.",
                    "Consider declaring tenant evidence boundaries or requesting tenant isolation review."
            );
            default -> List.of(
                    "No runtime activity found for this tenant view.",
                    "Establish tenant scope profile before expecting stronger tenant-native evidence."
            );
        };
    }

    private void persistSnapshot(String inspectionId, Map<String, Object> record) {
        try {
            Files.createDirectories(SNAPSHOT_ROOT);
            Path output = SNAPSHOT_ROOT.resolve(inspectionId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist tenant admin snapshot.", e);
        }
    }
}
