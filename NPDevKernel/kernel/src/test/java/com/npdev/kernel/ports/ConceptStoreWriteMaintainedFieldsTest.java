package com.npdev.kernel.ports;

import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P8 prelude: the port's default {@link ConceptStore#writeMaintainedFields} merges onto the stored row. */
class ConceptStoreWriteMaintainedFieldsTest {

    @Test
    void mergesTheGivenFieldsAndKeepsEverythingElse() {
        ConceptStore store = new InMemoryConceptStore();
        store.save(new ConceptRecord("Mosaic", "m1", "default", Map.of("title", "Sunset", "likeCount", 0)));

        store.writeMaintainedFields("default", "Mosaic", "m1", Map.of("likeCount", 3L));

        Map<String, Object> data = store.findById("default", "Mosaic", "m1").orElseThrow().data();
        assertEquals("Sunset", data.get("title"));
        assertEquals(3L, data.get("likeCount"));
    }

    @Test
    void aMissingRowOrNoValuesIsANoOp() {
        ConceptStore store = new InMemoryConceptStore();
        store.writeMaintainedFields("default", "Mosaic", "ghost", Map.of("likeCount", 1L));
        assertTrue(store.findById("default", "Mosaic", "ghost").isEmpty());

        store.save(new ConceptRecord("Mosaic", "m1", "default", Map.of("likeCount", 2)));
        store.writeMaintainedFields("default", "Mosaic", "m1", new HashMap<>());
        store.writeMaintainedFields("default", "Mosaic", "m1", null);
        assertEquals(2, store.findById("default", "Mosaic", "m1").orElseThrow().data().get("likeCount"));
    }
}
