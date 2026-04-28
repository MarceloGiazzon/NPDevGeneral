package com.npdev.adapters.persistence.inproc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPersistenceCapabilityAdapterTest {

    @Test
    void saveFindExistsUniqueAndDeleteWork() {
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();

        Map<?, ?> saved = (Map<?, ?>) adapter.save("User", Map.of("email", "a@b.com", "name", "Ana"));
        Object id = saved.get("id");
        assertNotNull(id);

        Object fetched = adapter.findById("User", id);
        assertEquals("a@b.com", ((Map<?, ?>) fetched).get("email"));

        assertTrue((Boolean) adapter.exists("User", "email", "a@b.com"));
        assertFalse((Boolean) adapter.unique("User", "email", "a@b.com"));
        assertTrue((Boolean) adapter.unique("User", "email", "other@b.com"));

        assertTrue((Boolean) adapter.delete("User", id));
        assertFalse((Boolean) adapter.exists("User", "email", "a@b.com"));
    }

    @Test
    void queryMatchesCriteria() {
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();
        adapter.save("User", Map.of("email", "a@b.com", "name", "Ana"));
        adapter.save("User", Map.of("email", "x@y.com", "name", "Xavier"));

        List<?> query = (List<?>) adapter.query("User", Map.of("email", "x@y.com"));
        assertEquals(1, query.size());
        assertEquals("Xavier", ((Map<?, ?>) query.get(0)).get("name"));
    }
}
