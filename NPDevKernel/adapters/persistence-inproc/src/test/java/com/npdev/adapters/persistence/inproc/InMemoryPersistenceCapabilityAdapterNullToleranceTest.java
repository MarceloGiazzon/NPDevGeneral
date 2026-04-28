package com.npdev.adapters.persistence.inproc;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InMemoryPersistenceCapabilityAdapterNullToleranceTest {

    @Test
    void saveReturnsMapEvenWhenRecordContainsNullValues() {
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("status", "Scheduled");
        input.put("notes", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) adapter.save("appointment", input);

        assertEquals("Scheduled", saved.get("status"));
        assertNull(saved.get("notes"));
    }
}
