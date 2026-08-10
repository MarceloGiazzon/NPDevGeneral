package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.TenantNativeGovernanceRequest;
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
public class TenantNativeGovernanceService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-tenant-native-governance/tenant-native-governance-rules.json";

    private static final Path TENANT_GOVERNANCE_ROOT =
            Paths.get("runtime-data", "tenant-native-governance-records");

    private final ObjectMapper objectMapper;

    public TenantNativeGovernanceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("version", rules.getOrDefault("version", 1));
        response.put("supportedTenantActions", rules.getOrDefault("supportedTenantActions", List.of()));
        response.put("tenantGovernanceStoragePath", TENANT_GOVERNANCE_ROOT.toString().replace("\\", "/"));
        response.put("mode", "tenant-native-administration-deepening-foundation");
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = new ArrayList<>();

        try {
            // try-with-resources (QUAL-2) -- see TemplateLibraryManagementService for the full note.
            if (Files.exists(TENANT_GOVERNANCE_ROOT)) {
                try (var paths = Files.list(TENANT_GOVERNANCE_ROOT)) {
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
                item -> String.valueOf(item.getOrDefault("recordedAt", "")),
                Comparator.reverseOrder()
        ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> recordTenantGovernance(TenantNativeGovernanceRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        Map<String, Object> matchedRule = findRule(rules, request.getTenantAction());

        String outcome;
        String authorityMeaning;
        String note;

        if (matchedRule == null) {
            outcome = String.valueOf(rules.getOrDefault("unsupportedTenantActionsDefaultOutcome", "UNSUPPORTED"));
            authorityMeaning = "unmapped";
            note = "Tenant action is not currently supported for tenant-native administration deepening.";
        } else {
            outcome = String.valueOf(matchedRule.getOrDefault("defaultOutcome", "REVIEW_REQUIRED"));
            authorityMeaning = String.valueOf(matchedRule.getOrDefault("authorityMeaning", "unmapped"));
            note = String.valueOf(matchedRule.getOrDefault("notes", ""));
        }

        String tenantGovernanceRecordId = UUID.randomUUID().toString();
        String recordedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        List<Map<String, Object>> actionItems = buildActionItems(request, outcome, authorityMeaning);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenantGovernanceRecordId", tenantGovernanceRecordId);
        response.put("tenantId", request.getTenantId());
        response.put("tenantAction", request.getTenantAction());
        response.put("targetScope", request.getTargetScope());
        response.put("requestedBy", request.getRequestedBy());
        response.put("rationale", request.getRationale());
        response.put("authorityMeaning", authorityMeaning);
        response.put("outcome", outcome);
        response.put("note", note);
        response.put("actionItems", actionItems);
        response.put("recordedAt", recordedAt);
        response.put("status", "RECORDED");

        persistTenantGovernance(tenantGovernanceRecordId, response);
        return response;
    }

    private void validate(TenantNativeGovernanceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required.");
        }
        if (isBlank(request.getTenantAction())) {
            throw new IllegalArgumentException("tenantAction is required.");
        }
        if (isBlank(request.getTargetScope())) {
            throw new IllegalArgumentException("targetScope is required.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load tenant-native governance rules.", e);
        }
    }

    private Map<String, Object> findRule(Map<String, Object> rules, String tenantAction) {
        Object raw = rules.get("supportedTenantActions");
        if (!(raw instanceof List<?> list)) {
            return null;
        }

        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                if (tenantAction.equals(String.valueOf(typed.get("tenantAction")))) {
                    return typed;
                }
            }
        }
        return null;
    }

    private List<Map<String, Object>> buildActionItems(
            TenantNativeGovernanceRequest request,
            String outcome,
            String authorityMeaning
    ) {
        List<Map<String, Object>> items = new ArrayList<>();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("step", 1);
        item.put("tenantAction", request.getTenantAction());
        item.put("tenantId", request.getTenantId());
        item.put("targetScope", request.getTargetScope());
        item.put("authorityMeaning", authorityMeaning);
        item.put("description", "Interpret tenant-native governance action '" + request.getTenantAction() + "' for tenant '" + request.getTenantId() + "' in scope '" + request.getTargetScope() + "'.");
        item.put("outcome", outcome);
        items.add(item);

        return items;
    }

    private void persistTenantGovernance(String tenantGovernanceRecordId, Map<String, Object> record) {
        try {
            Files.createDirectories(TENANT_GOVERNANCE_ROOT);
            Path output = TENANT_GOVERNANCE_ROOT.resolve(tenantGovernanceRecordId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist tenant-native governance record.", e);
        }
    }
}