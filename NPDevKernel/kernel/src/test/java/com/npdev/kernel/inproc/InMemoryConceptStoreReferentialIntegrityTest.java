package com.npdev.kernel.inproc;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ReferentialIntegrityException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the gap documented in npdev_platform_gaps.md finding #4 is closed: onDelete restrict/
 * cascade/nullify must now be enforced by InMemoryConceptStore itself (application-level), not
 * just by a physical database's real foreign-key constraint.
 */
class InMemoryConceptStoreReferentialIntegrityTest {

    private static CompiledField referenceField(String name, String target, String onDelete) {
        CompiledReferenceSemantics semantics = new CompiledReferenceSemantics(
                target, false, null, List.of(), List.of(), null, null, List.of(), null, null, null, onDelete);
        return new CompiledField(name, "reference", "String", false, true, false,
                List.of(), target, semantics, null, null, List.of(), null);
    }

    private static CompiledModel modelWith(String childConceptName, String onDelete) {
        CompiledConcept parent = new CompiledConcept("Parent", "Parent", "parents", List.of(
                new CompiledField("id", "uuid", "String", true, true, false)
        ));
        CompiledConcept child = new CompiledConcept(childConceptName, childConceptName, "children", List.of(
                new CompiledField("id", "uuid", "String", true, true, false),
                referenceField("parentId", "Parent", onDelete)
        ));
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("Parent", parent);
        concepts.put(childConceptName, child);
        return new CompiledModel("test", "1.0.0", "1.0.0", concepts);
    }

    @Test
    void restrictBlocksDeleteWhenChildRowExists() {
        InMemoryConceptStore store = new InMemoryConceptStore(modelWith("ChildRestrict", "restrict"));
        String parentId = UUID.randomUUID().toString();
        store.save(new ConceptRecord("Parent", parentId, "dev", Map.of()));
        store.save(new ConceptRecord("ChildRestrict", UUID.randomUUID().toString(), "dev",
                Map.of("parentId", parentId)));

        ReferentialIntegrityException ex = assertThrows(ReferentialIntegrityException.class,
                () -> store.deleteById("dev", "Parent", parentId));
        assertEquals("Parent", ex.getConceptName());
        assertEquals("parentId", ex.getFieldName());
        assertTrue(store.findById("dev", "Parent", parentId).isPresent(), "parent must not be deleted");
    }

    @Test
    void restrictIsTheDefaultWhenOnDeleteIsUnspecified() {
        InMemoryConceptStore store = new InMemoryConceptStore(modelWith("ChildDefault", null));
        String parentId = UUID.randomUUID().toString();
        store.save(new ConceptRecord("Parent", parentId, "dev", Map.of()));
        store.save(new ConceptRecord("ChildDefault", UUID.randomUUID().toString(), "dev",
                Map.of("parentId", parentId)));

        assertThrows(ReferentialIntegrityException.class, () -> store.deleteById("dev", "Parent", parentId));
    }

    @Test
    void deleteSucceedsWhenNoChildRowReferencesIt() {
        InMemoryConceptStore store = new InMemoryConceptStore(modelWith("ChildRestrict", "restrict"));
        String parentId = UUID.randomUUID().toString();
        store.save(new ConceptRecord("Parent", parentId, "dev", Map.of()));

        store.deleteById("dev", "Parent", parentId);
        assertFalse(store.findById("dev", "Parent", parentId).isPresent());
    }

    @Test
    void cascadeDeletesReferencingChildRows() {
        InMemoryConceptStore store = new InMemoryConceptStore(modelWith("ChildCascade", "cascade"));
        String parentId = UUID.randomUUID().toString();
        String childId = UUID.randomUUID().toString();
        store.save(new ConceptRecord("Parent", parentId, "dev", Map.of()));
        store.save(new ConceptRecord("ChildCascade", childId, "dev", Map.of("parentId", parentId)));

        store.deleteById("dev", "Parent", parentId);

        assertFalse(store.findById("dev", "Parent", parentId).isPresent());
        assertFalse(store.findById("dev", "ChildCascade", childId).isPresent(), "child must be cascade-deleted too");
    }

    @Test
    void nullifyClearsTheReferencingFieldInsteadOfDeletingTheChild() {
        InMemoryConceptStore store = new InMemoryConceptStore(modelWith("ChildNullify", "nullify"));
        String parentId = UUID.randomUUID().toString();
        String childId = UUID.randomUUID().toString();
        store.save(new ConceptRecord("Parent", parentId, "dev", Map.of()));
        store.save(new ConceptRecord("ChildNullify", childId, "dev", Map.of("parentId", parentId)));

        store.deleteById("dev", "Parent", parentId);

        assertFalse(store.findById("dev", "Parent", parentId).isPresent());
        ConceptRecord child = store.findById("dev", "ChildNullify", childId).orElseThrow();
        assertEquals(null, child.data().get("parentId"));
    }

    @Test
    void noModelMeansNoEnforcementAtAllPreservingOriginalBehavior() {
        InMemoryConceptStore store = new InMemoryConceptStore();
        String parentId = UUID.randomUUID().toString();
        store.save(new ConceptRecord("Parent", parentId, "dev", Map.of()));
        store.save(new ConceptRecord("ChildRestrict", UUID.randomUUID().toString(), "dev",
                Map.of("parentId", parentId)));

        store.deleteById("dev", "Parent", parentId);
        assertFalse(store.findById("dev", "Parent", parentId).isPresent());
    }
}
