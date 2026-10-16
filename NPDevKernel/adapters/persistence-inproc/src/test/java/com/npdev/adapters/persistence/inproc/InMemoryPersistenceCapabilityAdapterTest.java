package com.npdev.adapters.persistence.inproc;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.concepts.LiveConceptGatewaySemanticPolicy;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.SequenceAllocator;
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
    void saveDelegatesToTheInjectedConceptStore() {
        // REG-187: with a ConceptStore injected (as the runtime does), save() writes to THAT store, so
        // a flow-driven create is visible to the generated service's post-flow findById.
        InMemoryConceptStore conceptStore = new InMemoryConceptStore();
        InMemoryPersistenceCapabilityAdapter adapter =
                new InMemoryPersistenceCapabilityAdapter(null, conceptStore);

        Map<?, ?> saved = (Map<?, ?>) adapter.save("User",
                Map.of("email", "a@b.com", "name", "Ana", "tenantId", "dev"));
        Object id = saved.get("id");
        assertNotNull(id);

        // The row must be readable from the SAME store the service reads (tenant "dev", concept "user").
        assertTrue(conceptStore.findById("dev", "user", String.valueOf(id)).isPresent());
        assertEquals("a@b.com",
                conceptStore.findById("dev", "user", String.valueOf(id)).orElseThrow().data().get("email"));
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

    private static final String FLOW_DEFAULTS_MODEL = "{"
            + "\"dslVersion\":\"1.0.0\",\"namespace\":\"flowdefaults.test\",\"version\":\"1.0\","
            + "\"sequences\":[{\"name\":\"ticketCode\",\"format\":\"TK-{seq:4}\",\"scope\":\"tenant\"}],"
            + "\"concepts\":["
            + "{\"name\":\"Order\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"string\",\"id\":true,\"required\":true},"
            + "{\"name\":\"status\",\"type\":\"string\"},"
            + "{\"name\":\"priority\",\"type\":\"string\",\"default\":\"normal\"},"
            + "{\"name\":\"note\",\"type\":\"string\"}],"
            + "\"lifecycle\":{\"statusField\":\"status\","
            + "\"states\":[{\"value\":\"NEW\",\"label\":\"New\",\"initial\":true},"
            + "{\"value\":\"DONE\",\"label\":\"Done\",\"terminal\":true}],"
            + "\"transitions\":[{\"from\":\"NEW\",\"to\":\"DONE\",\"event\":\"finish\",\"actionLabel\":\"Finish\"}]}},"
            + "{\"name\":\"Ticket\",\"fields\":["
            + "{\"name\":\"id\",\"type\":\"string\",\"id\":true,\"required\":true},"
            + "{\"name\":\"code\",\"type\":\"string\",\"defaultExpression\":\"nextNumber('ticketCode')\"},"
            + "{\"name\":\"title\",\"type\":\"string\"}]}"
            + "]}";

    private static InMemoryPersistenceCapabilityAdapter flowDefaultsAdapter(InMemoryConceptStore store) throws Exception {
        CompiledModel model = new ModelCompiler().compile(
                new JsonModelParser().parse(new ObjectMapper().readTree(FLOW_DEFAULTS_MODEL)));
        InMemoryPersistenceCapabilityAdapter adapter = store != null
                ? new InMemoryPersistenceCapabilityAdapter(model, store)
                : new InMemoryPersistenceCapabilityAdapter(model);
        adapter.setSemanticPolicy(new LiveConceptGatewaySemanticPolicy(() -> model, SequenceAllocator.inMemory()));
        return adapter;
    }

    @Test
    void createGetsLifecycleInitialStateLiteralDefaultAndSequenceCodeButUpdateDoesNotReDefault() throws Exception {
        // ENG-6: a flow create gets the same defaulting pass as the CRUD API; a partial update never
        // re-defaults an untouched field. Run against both the isolated map and an injected store.
        for (boolean withStore : new boolean[] {false, true}) {
            InMemoryConceptStore store = withStore ? new InMemoryConceptStore() : null;
            InMemoryPersistenceCapabilityAdapter adapter = flowDefaultsAdapter(store);

            Map<?, ?> created = (Map<?, ?>) adapter.save("Order", Map.of("note", "first"));
            assertEquals("NEW", created.get("status"), "lifecycle initialState (store=" + withStore + ")");
            assertEquals("normal", created.get("priority"));

            // The in-memory save replaces the whole record, so an update that omits priority leaves it
            // absent -- what matters is that it is NOT re-defaulted to "normal" on the update.
            Object id = created.get("id");
            Map<?, ?> updated = (Map<?, ?>) adapter.save("Order", Map.of("id", id, "status", "NEW", "note", "x"));
            assertEquals(null, updated.get("priority"), "an update must not re-default priority (store=" + withStore + ")");
            assertEquals("x", updated.get("note"));

            Map<?, ?> first = (Map<?, ?>) adapter.save("Ticket", Map.of("title", "a"));
            Map<?, ?> second = (Map<?, ?>) adapter.save("Ticket", Map.of("title", "b"));
            assertEquals("TK-0001", first.get("code"));
            assertEquals("TK-0002", second.get("code"));
        }
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
