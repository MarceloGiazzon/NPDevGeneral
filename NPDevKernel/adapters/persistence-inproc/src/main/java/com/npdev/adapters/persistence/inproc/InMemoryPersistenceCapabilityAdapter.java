package com.npdev.adapters.persistence.inproc;

import com.npdev.kernel.ports.PersistenceCapabilityContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of the persistence capability contract for in-proc runtime mode.
 */
public final class InMemoryPersistenceCapabilityAdapter implements PersistenceCapabilityContract {
    private final Map<String, Map<Object, Map<String, Object>>> storeByConcept = new ConcurrentHashMap<>();

    @Override
    public Object save(Object entity) {
        return save("default", entity);
    }

    public Object save(Object concept, Object entity) {
        String conceptKey = normalizeConcept(concept);
        Map<String, Object> record = mutableRecord(entity);
        String conceptIdField = inferredRuntimeIdField(concept);

        Object id = record.get("id");
        if (id == null && conceptIdField != null) {
            id = record.get(conceptIdField);
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        // The in-memory store is keyed on the canonical "id" field. Always expose it
        // so callers, findById, and delete all agree on the same key. Any concept-specific
        // id field the record arrived with (e.g. "userId") is read above but never used to
        // hide the canonical id.
        record.put("id", id);

        storeByConcept
                .computeIfAbsent(conceptKey, k -> new ConcurrentHashMap<>())
                .put(id, new LinkedHashMap<>(record));

        return immutableRecord(record);
    }

    @Override
    public Object findById(Object concept, Object id) {
        if (id == null) {
            return null;
        }
        Map<Object, Map<String, Object>> conceptStore = storeByConcept.get(normalizeConcept(concept));
        if (conceptStore == null) {
            return null;
        }
        Map<String, Object> record = conceptStore.get(id);
        return record == null ? null : immutableRecord(record);
    }

    @Override
    public Object query(Object concept, Object criteria) {
        Map<Object, Map<String, Object>> conceptStore = storeByConcept.get(normalizeConcept(concept));
        if (conceptStore == null) {
            return List.of();
        }

        Map<String, Object> criteriaMap = criteriaMap(criteria);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> record : conceptStore.values()) {
            if (matchesCriteria(record, criteriaMap)) {
                out.add(immutableRecord(record));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Object delete(Object concept, Object id) {
        if (id == null) {
            return false;
        }
        Map<Object, Map<String, Object>> conceptStore = storeByConcept.get(normalizeConcept(concept));
        if (conceptStore == null) {
            return false;
        }
        return conceptStore.remove(id) != null;
    }

    @Override
    public Object exists(Object concept, Object field, Object value) {
        Map<Object, Map<String, Object>> conceptStore = storeByConcept.get(normalizeConcept(concept));
        if (conceptStore == null) {
            return false;
        }
        String fieldName = Objects.toString(field, "");
        for (Map<String, Object> record : conceptStore.values()) {
            Object candidate = record.get(fieldName);
            if (Objects.equals(candidate, value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object unique(Object concept, Object field, Object value) {
        boolean exists = (Boolean) exists(concept, field, value);
        return !exists;
    }

    private static String normalizeConcept(Object concept) {
        String value = Objects.toString(concept, "default").trim().toLowerCase();
        return value.isBlank() ? "default" : value;
    }

    private static String inferredRuntimeIdField(Object concept) {
        String raw = Objects.toString(concept, "default").trim();
        if (raw.isBlank() || "default".equalsIgnoreCase(raw)) {
            return "id";
        }
        return raw.substring(0, 1).toLowerCase() + raw.substring(1) + "Id";
    }

    private static Map<String, Object> mutableRecord(Object entity) {
        if (!(entity instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Persistence save expects a map-like entity");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static Map<String, Object> criteriaMap(Object criteria) {
        if (criteria == null) {
            return Map.of();
        }
        if (!(criteria instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Query criteria must be a map");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }

    private static boolean matchesCriteria(Map<String, Object> record, Map<String, Object> criteria) {
        for (Map.Entry<String, Object> criterion : criteria.entrySet()) {
            if (!Objects.equals(record.get(criterion.getKey()), criterion.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> immutableRecord(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
