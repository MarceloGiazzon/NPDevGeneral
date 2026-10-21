package com.npdev.kernel.inproc;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledIndex;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptStoreUniqueViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-257 (declared unique concept index enforced in-process, single and compound), REG-258 (soft
 * delete + restore in-process) and REG-267 (undo journal behind {@code runAtomically}).
 */
class InMemoryConceptStoreIndexSoftDeleteAtomicTest {

    private static final String TENANT = "dev";

    private static CompiledConcept concept(String name, boolean softDelete, CompiledIndex index, String... fieldNames) {
        List<CompiledField> fields = new ArrayList<>();
        fields.add(new CompiledField("id", "uuid", "String", true, true, false));
        for (String fieldName : fieldNames) {
            fields.add(new CompiledField(fieldName, "string", "String", false, false, false));
        }
        return new CompiledConcept(name, name, name.toLowerCase() + "s", fields, List.of(), List.of(), null, null,
                null, null, List.of(index), null, null, null, null, softDelete);
    }

    private InMemoryConceptStore store;

    @BeforeEach
    void setUp() {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("Loc", concept("Loc", false,
                new CompiledIndex("ux_loc_local_id", List.of("localId"), true), "localId"));
        concepts.put("Line", concept("Line", false,
                new CompiledIndex("ux_line_sku", List.of("orderId", "sku"), true), "orderId", "sku"));
        concepts.put("Supplier", concept("Supplier", true,
                new CompiledIndex("ux_supplier_code", List.of("code"), true), "code"));
        store = new InMemoryConceptStore(new CompiledModel("test", "1.0.0", "1.0.0", concepts));
    }

    private ConceptRecord rec(String concept, String id, Map<String, Object> data) {
        return new ConceptRecord(concept, id, TENANT, data);
    }

    @Test
    void uniqueSingleFieldIndexRejectsSecondLiveRowButAllowsRewriteOfSameRow() {
        store.save(rec("Loc", "a", Map.of("localId", "L1")));
        ConceptStoreUniqueViolationException ex = assertThrows(ConceptStoreUniqueViolationException.class,
                () -> store.save(rec("Loc", "b", Map.of("localId", "l1 "))));
        assertEquals("ux_loc_local_id", ex.getIndexName());
        assertEquals(List.of("localId"), ex.getFieldNames());
        assertTrue(store.findById(TENANT, "Loc", "b").isEmpty(), "the refused row must not be stored");
        // the same row may be rewritten, and a different value is fine
        store.save(rec("Loc", "a", Map.of("localId", "L1")));
        store.save(rec("Loc", "b", Map.of("localId", "L2")));
        assertEquals(2, store.findAll(TENANT, "Loc").size());
    }

    @Test
    void nullInUniqueIndexNeverCollides() {
        Map<String, Object> noValue = new java.util.LinkedHashMap<>();
        noValue.put("localId", null);
        store.save(rec("Loc", "a", noValue));
        store.save(rec("Loc", "b", noValue));
        assertEquals(2, store.findAll(TENANT, "Loc").size());
    }

    @Test
    void compoundUniqueIndexCollidesOnlyOnTheWholePair() {
        store.save(rec("Line", "1", Map.of("orderId", "O1", "sku", "S")));
        assertThrows(ConceptStoreUniqueViolationException.class,
                () -> store.save(rec("Line", "2", Map.of("orderId", "O1", "sku", "S"))));
        store.save(rec("Line", "3", Map.of("orderId", "O2", "sku", "S")));
        store.save(rec("Line", "4", Map.of("orderId", "O1", "sku", "T")));
        assertEquals(3, store.findAll(TENANT, "Line").size());
    }

    @Test
    void softDeleteHidesRowFreesItsUniqueValueAndRestoreBringsItBack() {
        store.save(rec("Supplier", "s1", Map.of("code", "C")));
        store.deleteById(TENANT, "Supplier", "s1");
        assertTrue(store.findById(TENANT, "Supplier", "s1").isEmpty());
        assertTrue(store.findAll(TENANT, "Supplier").isEmpty());

        // value freed: a new live row may take it, and then the old one cannot come back
        store.save(rec("Supplier", "s2", Map.of("code", "C")));
        assertThrows(ConceptStoreUniqueViolationException.class, () -> store.restore(TENANT, "Supplier", "s1"));
        assertTrue(store.findById(TENANT, "Supplier", "s1").isEmpty());
        store.deleteById(TENANT, "Supplier", "s2");

        assertTrue(store.restore(TENANT, "Supplier", "s1"));
        assertTrue(store.findById(TENANT, "Supplier", "s1").isPresent());
        assertFalse(store.restore(TENANT, "Supplier", "s1"), "already live");
        assertFalse(store.restore(TENANT, "Supplier", "never-existed"));
    }

    @Test
    void restoreIsFalseForAConceptWithoutSoftDelete() {
        store.save(rec("Loc", "a", Map.of("localId", "L1")));
        store.deleteById(TENANT, "Loc", "a");
        assertTrue(store.findById(TENANT, "Loc", "a").isEmpty(), "physical delete");
        assertFalse(store.restore(TENANT, "Loc", "a"));
    }

    @Test
    void runAtomicallyUndoesCreatesUpdatesAndDeletesWhenTheWorkFails() {
        store.save(rec("Loc", "keep", Map.of("localId", "K")));
        store.save(rec("Loc", "gone", Map.of("localId", "G")));
        store.save(rec("Supplier", "s", Map.of("code", "S")));

        assertThrows(IllegalStateException.class, () -> store.runAtomically(() -> {
            store.save(rec("Loc", "new", Map.of("localId", "N")));
            store.save(rec("Loc", "keep", Map.of("localId", "K-changed")));
            store.deleteById(TENANT, "Loc", "gone");
            store.deleteById(TENANT, "Supplier", "s");
            throw new IllegalStateException("boom");
        }));

        assertTrue(store.findById(TENANT, "Loc", "new").isEmpty());
        assertEquals("K", store.findById(TENANT, "Loc", "keep").orElseThrow().data().get("localId"));
        assertTrue(store.findById(TENANT, "Loc", "gone").isPresent());
        assertTrue(store.findById(TENANT, "Supplier", "s").isPresent(), "soft delete undone too");
    }

    @Test
    void runAtomicallyKeepsEverythingOnSuccessAndNestedUnitsJoinTheOuterOne() {
        String result = store.runAtomically(() -> {
            store.save(rec("Loc", "a", Map.of("localId", "A")));
            return store.runAtomically(() -> {
                store.save(rec("Loc", "b", Map.of("localId", "B")));
                return "done";
            });
        });
        assertEquals("done", result);
        assertEquals(2, store.findAll(TENANT, "Loc").size());

        assertThrows(IllegalStateException.class, () -> store.runAtomically(() -> {
            store.save(rec("Loc", "c", Map.of("localId", "C")));
            store.runAtomically(() -> {
                store.save(rec("Loc", "d", Map.of("localId", "D")));
                return null;
            });
            throw new IllegalStateException("outer fails after the inner unit finished");
        }));
        assertEquals(2, store.findAll(TENANT, "Loc").size(), "inner unit rolled back with the outer one");
    }
}
