package com.finalexec.npdev.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class PublicationChainReferenceResolver {

    private final ObjectMapper objectMapper;

    public PublicationChainReferenceResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> readRecords(Path root) {
        List<Map<String, Object>> items = new ArrayList<>();

        try {
            if (!Files.exists(root)) {
                return items;
            }

            try (Stream<Path> stream = Files.list(root)) {
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
        } catch (Exception ignored) {
        }

        items.sort(Comparator.comparing(this::extractTimestamp, Comparator.reverseOrder()));
        return items;
    }

    public Map<String, Object> resolveSingle(Path root, String tenantId, String reference, String... fields) {
        if (reference == null || reference.isBlank()) {
            return null;
        }

        List<Map<String, Object>> resolved = resolveRecords(root, tenantId, List.of(reference), fields);
        return resolved.isEmpty() ? null : resolved.get(0);
    }

    public List<Map<String, Object>> resolveRecords(Path root, String tenantId, List<String> references, String... fields) {
        List<String> normalizedReferences = normalizeList(references);
        if (normalizedReferences.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> items = readRecords(root);
        List<Map<String, Object>> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String reference : normalizedReferences) {
            for (Map<String, Object> item : items) {
                if (!tenantCompatible(item, tenantId)) {
                    continue;
                }
                if (matchesAnyField(item, reference, fields)) {
                    String canonical = extractFirstString(item, fields);
                    String dedupeKey = canonical.isBlank() ? reference + "::" + resolved.size() : canonical;
                    if (seen.add(dedupeKey)) {
                        resolved.add(item);
                    }
                }
            }
        }

        return resolved;
    }

    public List<Map<String, Object>> findAllByReference(Path root, String tenantId, String reference, String... fields) {
        if (reference == null || reference.isBlank()) {
            return List.of();
        }
        return resolveRecords(root, tenantId, List.of(reference), fields);
    }

    public List<Map<String, Object>> findAllByReferenceAnyTenant(Path root, String reference, String... fields) {
        if (reference == null || reference.isBlank()) {
            return List.of();
        }

        List<Map<String, Object>> items = readRecords(root);
        List<Map<String, Object>> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Map<String, Object> item : items) {
            if (matchesAnyField(item, reference.trim(), fields)) {
                String canonical = extractFirstString(item, fields);
                String tenantId = extractFirstString(item, "tenantId");
                String dedupeKey = canonical + "::" + tenantId;
                if (seen.add(dedupeKey)) {
                    resolved.add(item);
                }
            }
        }

        return resolved;
    }

    public Map<String, Object> assessTenantIsolation(
            Path root,
            String tenantId,
            List<String> references,
            String label,
            String... fields
    ) {
        List<String> normalizedReferences = normalizeList(references);
        List<String> rejectedReferenceReasons = new ArrayList<>();
        int crossTenantViolationCount = 0;

        for (String reference : normalizedReferences) {
            List<Map<String, Object>> tenantScoped = resolveRecords(root, tenantId, List.of(reference), fields);
            if (!tenantScoped.isEmpty()) {
                continue;
            }

            List<Map<String, Object>> anyTenant = findAllByReferenceAnyTenant(root, reference, fields);
            for (Map<String, Object> record : anyTenant) {
                String recordTenantId = extractFirstString(record, "tenantId");
                if (!recordTenantId.isBlank() && !recordTenantId.equals(tenantId)) {
                    crossTenantViolationCount++;
                    rejectedReferenceReasons.add(
                            label + " '" + reference + "' belongs to tenant '" + recordTenantId + "'"
                    );
                }
            }
        }

        Map<String, Object> assessment = new LinkedHashMap<>();
        assessment.put("tenantCompatibilityStatus", crossTenantViolationCount == 0 ? "COMPATIBLE" : "VIOLATION");
        assessment.put("crossTenantViolationCount", crossTenantViolationCount);
        assessment.put("tenantIsolationStatus", crossTenantViolationCount == 0 ? "TENANT_SCOPED" : "CROSS_TENANT_REJECTED");
        assessment.put("rejectedReferenceReasons", rejectedReferenceReasons);
        return assessment;
    }

    @SafeVarargs
    public final Map<String, Object> mergeTenantAssessments(Map<String, Object>... assessments) {
        List<String> rejectedReferenceReasons = new ArrayList<>();
        int crossTenantViolationCount = 0;

        for (Map<String, Object> assessment : assessments) {
            if (assessment == null) {
                continue;
            }
            Object rawCount = assessment.get("crossTenantViolationCount");
            if (rawCount instanceof Number number) {
                crossTenantViolationCount += number.intValue();
            } else if (rawCount != null) {
                try {
                    crossTenantViolationCount += Integer.parseInt(String.valueOf(rawCount));
                } catch (NumberFormatException ignored) {
                }
            }
            rejectedReferenceReasons.addAll(extractStringList(assessment, "rejectedReferenceReasons"));
        }

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("tenantCompatibilityStatus", crossTenantViolationCount == 0 ? "COMPATIBLE" : "VIOLATION");
        merged.put("crossTenantViolationCount", crossTenantViolationCount);
        merged.put("tenantIsolationStatus", crossTenantViolationCount == 0 ? "TENANT_SCOPED" : "CROSS_TENANT_REJECTED");
        merged.put("rejectedReferenceReasons", rejectedReferenceReasons);
        return merged;
    }

    public List<String> extractCanonicalReferences(List<Map<String, Object>> records, String... keys) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, Object> record : records) {
            String value = extractFirstString(record, keys);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    public List<String> unresolvedReferences(List<String> claimedReferences, List<Map<String, Object>> resolvedRecords, String... fields) {
        List<String> normalized = normalizeList(claimedReferences);
        List<String> unresolved = new ArrayList<>();

        for (String reference : normalized) {
            boolean matched = false;
            for (Map<String, Object> record : resolvedRecords) {
                if (matchesAnyField(record, reference, fields)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                unresolved.add(reference);
            }
        }

        return unresolved;
    }

    public List<String> extractStringList(Map<String, Object> record, String... fields) {
        Set<String> values = new LinkedHashSet<>();
        for (String field : fields) {
            Object raw = record.get(field);
            if (raw instanceof Collection<?> collection) {
                for (Object value : collection) {
                    String normalized = normalizeString(value);
                    if (!normalized.isBlank()) {
                        values.add(normalized);
                    }
                }
            } else {
                String normalized = normalizeString(raw);
                if (!normalized.isBlank()) {
                    values.add(normalized);
                }
            }
        }
        return new ArrayList<>(values);
    }

    public String extractFirstString(Map<String, Object> record, String... fields) {
        for (String field : fields) {
            if (!record.containsKey(field)) {
                continue;
            }
            Object raw = record.get(field);
            if (raw instanceof Collection<?> collection) {
                for (Object value : collection) {
                    String normalized = normalizeString(value);
                    if (!normalized.isBlank()) {
                        return normalized;
                    }
                }
            } else {
                String normalized = normalizeString(raw);
                if (!normalized.isBlank()) {
                    return normalized;
                }
            }
        }
        return "";
    }

    public String determineIntegrityStatus(List<String> claimedReferences, List<String> unresolvedReferences) {
        if (claimedReferences.isEmpty()) {
            return "NOT_APPLICABLE";
        }
        if (unresolvedReferences.isEmpty()) {
            return "RESOLVED";
        }
        if (unresolvedReferences.size() == claimedReferences.size()) {
            return "UNRESOLVED";
        }
        return "PARTIALLY_RESOLVED";
    }

    private boolean tenantCompatible(Map<String, Object> record, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return true;
        }
        String recordTenantId = extractFirstString(record, "tenantId");
        return recordTenantId.isBlank() || tenantId.equals(recordTenantId);
    }

    private boolean matchesAnyField(Map<String, Object> record, String reference, String... fields) {
        for (String field : fields) {
            if (matchesField(record, reference, field)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesField(Map<String, Object> record, String reference, String field) {
        if (!record.containsKey(field)) {
            return false;
        }

        Object raw = record.get(field);
        if (raw instanceof Collection<?> collection) {
            for (Object value : collection) {
                if (reference.equals(normalizeString(value))) {
                    return true;
                }
            }
            return false;
        }

        return reference.equals(normalizeString(raw));
    }

    private String extractTimestamp(Map<String, Object> record) {
        return extractFirstString(record, "recordedAt", "executedAt", "generatedAt");
    }

    private List<String> normalizeList(List<String> values) {
        List<String> normalized = new ArrayList<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String normalizedValue = normalizeString(value);
            if (!normalizedValue.isBlank()) {
                normalized.add(normalizedValue);
            }
        }
        return normalized;
    }

    private String normalizeString(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }
}
