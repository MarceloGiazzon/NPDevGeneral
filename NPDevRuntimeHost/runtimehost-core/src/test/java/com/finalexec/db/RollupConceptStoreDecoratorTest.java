package com.finalexec.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P8 prelude: concept {@code rollups[]} stay equal to their aggregate across every child write. */
class RollupConceptStoreDecoratorTest {

    private static final String MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "demo.rollup", "version": "1.0",
          "concepts": [
            { "name": "Mosaic", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "title", "type": "string" },
              { "name": "likeCount", "type": "integer" },
              { "name": "totalWeight", "type": "decimal" },
              { "name": "maxWeight", "type": "decimal" } ],
              "rollups": [
                { "field": "likeCount", "from": "Like", "via": "mosaicId" },
                { "field": "totalWeight", "from": "Like", "via": "mosaicId", "fn": "sum", "of": "weight" },
                { "field": "maxWeight", "from": "Like", "via": "mosaicId", "fn": "max", "of": "weight" } ] },
            { "name": "Like", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "mosaicId", "type": "reference", "reference": { "target": "Mosaic" } },
              { "name": "weight", "type": "decimal" } ] },
            { "name": "Note", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "text", "type": "string" } ] }
          ]
        }
        """;

    private RollupConceptStoreDecorator store;

    @BeforeEach
    void setUp() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        store = new RollupConceptStoreDecorator(new InMemoryConceptStore(model), () -> model);
        save("Mosaic", "m1", Map.of("title", "Sunset"));
        save("Mosaic", "m2", Map.of("title", "Flag"));
    }

    private ConceptRecord save(String concept, String id, Map<String, Object> data) {
        Map<String, Object> withId = new LinkedHashMap<>(data);
        withId.put("id", id);
        return store.save(new ConceptRecord(concept, id, "default", withId));
    }

    private Object field(String id, String field) {
        return store.findById("default", "Mosaic", id).orElseThrow().data().get(field);
    }

    private long likes(String id) {
        return ((Number) field(id, "likeCount")).longValue();
    }

    @Test
    void aNewParentStartsAtZeroAndEmptyMinMaxStayNull() {
        assertEquals(0L, likes("m1"));
        assertEquals(0, new java.math.BigDecimal(String.valueOf(field("m1", "totalWeight"))).signum());
        assertNull(field("m1", "maxWeight"));
    }

    @Test
    void childCreateAndDeleteKeepCountSumAndMaxCurrent() {
        save("Like", "l1", Map.of("mosaicId", "m1", "weight", 2));
        save("Like", "l2", Map.of("mosaicId", "m1", "weight", 5));
        save("Like", "l3", Map.of("mosaicId", "m2", "weight", 1));
        assertEquals(2L, likes("m1"));
        assertEquals(1L, likes("m2"));
        assertEquals(0, new java.math.BigDecimal("7").compareTo(new java.math.BigDecimal(String.valueOf(field("m1", "totalWeight")))));
        assertEquals(0, new java.math.BigDecimal("5").compareTo(new java.math.BigDecimal(String.valueOf(field("m1", "maxWeight")))));

        store.deleteById("default", "Like", "l2");
        assertEquals(1L, likes("m1"));
        assertEquals(0, new java.math.BigDecimal("2").compareTo(new java.math.BigDecimal(String.valueOf(field("m1", "maxWeight")))));
        store.deleteById("default", "Like", "l1", null);
        assertEquals(0L, likes("m1"));
        assertNull(field("m1", "maxWeight"));
    }

    @Test
    void movingAChildRecountsBothTheOldAndTheNewParent() {
        save("Like", "l1", Map.of("mosaicId", "m1", "weight", 1));
        save("Like", "l1", Map.of("mosaicId", "m2", "weight", 1));
        assertEquals(0L, likes("m1"));
        assertEquals(1L, likes("m2"));
    }

    @Test
    void aClientWriteOfTheRollupFieldIsOverwrittenWithTheRealValue() {
        save("Like", "l1", Map.of("mosaicId", "m1", "weight", 1));
        ConceptRecord saved = save("Mosaic", "m1", Map.of("title", "Renamed", "likeCount", 999));
        assertEquals(1L, ((Number) saved.data().get("likeCount")).longValue());
        assertEquals(1L, likes("m1"));
        assertEquals("Renamed", field("m1", "title"));
    }

    @Test
    void aChildPointingAtNoParentOrAMissingOneIsHarmless() {
        save("Like", "l1", Map.of("weight", 1));
        save("Like", "l2", Map.of("mosaicId", "ghost", "weight", 1));
        assertEquals(0L, likes("m1"));
        assertTrue(store.findById("default", "Mosaic", "ghost").isEmpty());
    }

    @Test
    void conceptsWithoutRollupsPassStraightThrough() {
        save("Note", "n1", Map.of("text", "hi"));
        assertEquals("hi", store.findById("default", "Note", "n1").orElseThrow().data().get("text"));
        store.deleteById("default", "Note", "n1");
        assertTrue(store.findById("default", "Note", "n1").isEmpty());
    }
}
