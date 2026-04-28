package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.SourceMutationAuditRecordRequest;
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
import java.util.stream.Stream;

@Service
public class SourceMutationAuditRecordService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-source-mutation-audit/source-mutation-audit-rules.json";

    private static final Path AUDIT_ROOT =
            Paths.get("runtime-data", "source-mutation-audit-records");

    private final ObjectMapper objectMapper;

    public SourceMutationAuditRecordService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", AUDIT_ROOT.toString().replace("\\", "/"));
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Source Mutation Audit Record"));
        response.put("supportedAuditEventTypes", rules.getOrDefault("supportedAuditEventTypes", List.of()));
        response.put("supportedDecisions", rules.getOrDefault("supportedDecisions", List.of()));
        response.put("supportedScopeCategories", rules.getOrDefault("supportedScopeCategories", List.of()));
        response.put("mode", rules.getOrDefault("mode", "source-mutation-audit-record-v1"));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = new ArrayList<>();

        try {
            if (Files.exists(AUDIT_ROOT)) {
                try (Stream<Path> stream = Files.list(AUDIT_ROOT)) {
                    stream.filter(path -> path.getFileName().toString().endsWith(".json"))
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

    public Map<String, Object> recordAudit(SourceMutationAuditRecordRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String auditId = UUID.randomUUID().toString();
        String recordedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String mode = String.valueOf(rules.getOrDefault("mode", "source-mutation-audit-record-v1"));
        Map<String, Object> scopeCategory = findScopeCategory(rules, request.getMutationScope().trim());

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("auditId", auditId);
        record.put("mutationScope", request.getMutationScope().trim());
        record.put("mutationReference", request.getMutationReference().trim());
        record.put("auditEventType", request.getAuditEventType().trim());
        record.put("decision", request.getDecision().trim());
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", request.getTenantId().trim());
        record.put("scopeCategory", scopeCategory == null ? Map.of() : scopeCategory);
        record.put("recordedAt", recordedAt);
        record.put("mode", mode);
        record.put("status", "RECORDED");

        persistRecord(auditId, record);
        return record;
    }

    private void validate(SourceMutationAuditRecordRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getMutationScope())) {
            throw new IllegalArgumentException("mutationScope is required.");
        }
        if (isBlank(request.getMutationReference())) {
            throw new IllegalArgumentException("mutationReference is required.");
        }
        if (isBlank(request.getAuditEventType())) {
            throw new IllegalArgumentException("auditEventType is required.");
        }
        if (isBlank(request.getDecision())) {
            throw new IllegalArgumentException("decision is required.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
        if (isBlank(request.getRationale())) {
            throw new IllegalArgumentException("rationale is required.");
        }
        if (isBlank(request.getTenantId())) {
            throw new IllegalArgumentException("tenantId is required.");
        }

        Map<String, Object> rules = loadRules();
        List<String> supportedAuditEventTypes = extractStringList(rules.get("supportedAuditEventTypes"));
        List<String> supportedDecisions = extractStringList(rules.get("supportedDecisions"));

        if (!supportedAuditEventTypes.contains(request.getAuditEventType().trim())) {
            throw new IllegalArgumentException("Unsupported auditEventType: " + request.getAuditEventType());
        }
        if (!supportedDecisions.contains(request.getDecision().trim())) {
            throw new IllegalArgumentException("Unsupported decision: " + request.getDecision());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load source mutation audit rules.", e);
        }
    }

    private Map<String, Object> findScopeCategory(Map<String, Object> rules, String mutationScope) {
        Object raw = rules.get("supportedScopeCategories");
        if (!(raw instanceof List<?> list)) {
            return null;
        }

        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                if (mutationScope.equals(String.valueOf(typed.get("mutationScope")))) {
                    return typed;
                }
            }
        }
        return null;
    }

    private List<String> extractStringList(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    private void persistRecord(String auditId, Map<String, Object> record) {
        try {
            Files.createDirectories(AUDIT_ROOT);
            Path output = AUDIT_ROOT.resolve(auditId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist source mutation audit record.", e);
        }
    }
}
