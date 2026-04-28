package com.npdev.kernel.inproc;

import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class InMemoryConceptStore implements ConceptStore {
    private final Map<String, ConceptRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
        return Optional.ofNullable(records.get(key(tenantId, conceptName, id)));
    }

    @Override
    public synchronized List<ConceptRecord> findAll(String tenantId, String conceptName) {
        String prefix = keyPrefix(tenantId, conceptName);
        List<ConceptRecord> out = new ArrayList<>();
        for (Map.Entry<String, ConceptRecord> entry : records.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                out.add(entry.getValue());
            }
        }
        out.sort(Comparator.comparing(ConceptRecord::id, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    @Override
    public synchronized ConceptRecord save(ConceptRecord record) {
        records.put(key(record.tenantId(), record.conceptName(), record.id()), record);
        return record;
    }

    @Override
    public synchronized void deleteById(String tenantId, String conceptName, String id) {
        records.remove(key(tenantId, conceptName, id));
    }

    private static String key(String tenantId, String conceptName, String id) {
        return keyPrefix(tenantId, conceptName) + normalize(id);
    }

    private static String keyPrefix(String tenantId, String conceptName) {
        return normalize(tenantId) + "|" + normalize(conceptName) + "|";
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
