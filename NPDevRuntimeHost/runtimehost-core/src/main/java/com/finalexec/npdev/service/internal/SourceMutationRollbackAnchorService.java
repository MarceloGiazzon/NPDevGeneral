package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.SourceMutationRollbackAnchorCreateRequest;
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
public class SourceMutationRollbackAnchorService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-source-mutation-rollback-anchor/source-mutation-rollback-anchor-rules.json";

    private static final Path ANCHOR_ROOT =
            Paths.get("runtime-data", "source-mutation-rollback-anchors");

    private final ObjectMapper objectMapper;

    public SourceMutationRollbackAnchorService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("storagePath", ANCHOR_ROOT.toString().replace("\\", "/"));
        response.put("surfaceName", rules.getOrDefault("surfaceName", "Source Mutation Rollback Anchor"));
        response.put("scopePolicies", rules.getOrDefault("scopePolicies", List.of()));
        response.put("laterRollbackUsageNotes", rules.getOrDefault("laterRollbackUsageNotes", List.of()));
        response.put("mode", rules.getOrDefault("mode", "source-mutation-rollback-anchor-v1"));
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = new ArrayList<>();

        try {
            if (Files.exists(ANCHOR_ROOT)) {
                try (Stream<Path> stream = Files.list(ANCHOR_ROOT)) {
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

    public Map<String, Object> createAnchor(SourceMutationRollbackAnchorCreateRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        String rollbackAnchorId = UUID.randomUUID().toString();
        String recordedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String tenantId = isBlank(request.getTenantId()) ? "global" : request.getTenantId().trim();
        String mode = String.valueOf(rules.getOrDefault("mode", "source-mutation-rollback-anchor-v1"));
        Map<String, Object> scopePolicy = findScopePolicy(rules, request.getMutationScope().trim());

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("rollbackAnchorId", rollbackAnchorId);
        record.put("mutationScope", request.getMutationScope().trim());
        record.put("mutationReference", request.getMutationReference().trim());
        record.put("beforeStateReference", request.getBeforeStateReference().trim());
        record.put("requestedBy", request.getRequestedBy().trim());
        record.put("rationale", request.getRationale().trim());
        record.put("tenantId", tenantId);
        record.put("scopePolicy", scopePolicy == null ? Map.of() : scopePolicy);
        record.put("recordedAt", recordedAt);
        record.put("mode", mode);
        record.put("message", "Source mutation rollback anchor recorded.");
        record.put("status", "RECORDED");

        persistRecord(rollbackAnchorId, record);
        return record;
    }

    private void validate(SourceMutationRollbackAnchorCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getMutationScope())) {
            throw new IllegalArgumentException("mutationScope is required.");
        }
        if (isBlank(request.getMutationReference())) {
            throw new IllegalArgumentException("mutationReference is required.");
        }
        if (isBlank(request.getBeforeStateReference())) {
            throw new IllegalArgumentException("beforeStateReference is required.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
        if (isBlank(request.getRationale())) {
            throw new IllegalArgumentException("rationale is required.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load source mutation rollback anchor rules.", e);
        }
    }

    private Map<String, Object> findScopePolicy(Map<String, Object> rules, String mutationScope) {
        Object raw = rules.get("scopePolicies");
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

    private void persistRecord(String rollbackAnchorId, Map<String, Object> record) {
        try {
            Files.createDirectories(ANCHOR_ROOT);
            Path output = ANCHOR_ROOT.resolve(rollbackAnchorId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist source mutation rollback anchor record.", e);
        }
    }
}
