package com.npdev.adapters.persistence.inproc;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
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
    void saveAppliesDeclaredFieldDefaultWhenOmitted() {
        // ARCH-8b: flow-compiled createConcept/updateConcept steps dispatch straight to save(),
        // bypassing the ConceptGatewaySemanticPolicy defaults pass generic CRUD create goes through.
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter(widgetModel());

        Map<?, ?> saved = (Map<?, ?>) adapter.save("Widget", Map.of("name", "Gadget"));

        assertEquals("Draft", saved.get("status"), "Omitted field with a declared default should be defaulted");
    }

    @Test
    void saveDoesNotOverrideAnExplicitlySuppliedValue() {
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter(widgetModel());

        Map<?, ?> saved = (Map<?, ?>) adapter.save("Widget", Map.of("name", "Gadget", "status", "Published"));

        assertEquals("Published", saved.get("status"), "Caller-supplied value must win over the declared default");
    }

    private static CompiledModel widgetModel() {
        CompiledSchema statusSchema = new CompiledSchema(
                "string", Map.of(), null, List.of(), List.of(), "Draft", null, null, null, null, null, null);
        CompiledField statusField = new CompiledField(
                "status", "string", "String", false, false, false, List.of(), null, statusSchema);
        CompiledField nameField = new CompiledField("name", "string", "String", false, true, false);
        CompiledConcept widget = new CompiledConcept("Widget", "Widget", "widgets", List.of(nameField, statusField));
        return new CompiledModel(
                "arch8b.test", "1.0.0", "1.0.0",
                Map.of("Widget", widget),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
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
