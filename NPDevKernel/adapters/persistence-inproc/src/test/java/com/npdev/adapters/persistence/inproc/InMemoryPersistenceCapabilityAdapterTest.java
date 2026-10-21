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
import com.npdev.kernel.ports.PersistenceCapabilityContract;
import com.npdev.kernel.ports.SequenceAllocator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

            // REG-263: an existing row is merged into, so an update that omits priority keeps the
            // stored value -- it is neither reset nor re-defaulted. A row stored as "high" proves the
            // difference (a re-default would turn it back into "normal").
            Map<?, ?> high = (Map<?, ?>) adapter.save("Order", Map.of("note", "urgent", "priority", "high"));
            Object id = high.get("id");
            Map<?, ?> updated = (Map<?, ?>) adapter.save("Order", Map.of("id", id, "status", "NEW", "note", "x"));
            assertEquals("high", updated.get("priority"), "an update must not re-default priority (store=" + withStore + ")");
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

    @Test
    void tenantBCannotOverwriteTenantARecordInTheFallbackStore() {
        // TEN-1: ids leak, so a tenant-stamped save of another tenant's id must be refused, not replace it.
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();
        adapter.save("Note", Map.of("id", "X", "name", "A-original", "tenantId", "tenantA"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> adapter.save("Note", Map.of("id", "X", "name", "B-overwrite", "tenantId", "tenantB")));

        assertTrue(refused.getMessage().contains("PERSISTENCE_RECORD_NOT_IN_TENANT"));
        assertFalse(refused.getMessage().contains("tenantA"));
        Map<?, ?> kept = (Map<?, ?>) adapter.findById("Note", "X");
        assertEquals("A-original", kept.get("name"));
        assertEquals("tenantA", kept.get("tenantId"));

        adapter.save("Note", Map.of("id", "X", "name", "A-second", "tenantId", "tenantA"));
        assertEquals("A-second", ((Map<?, ?>) adapter.findById("Note", "X")).get("name"));
    }

    private static Map<String, Object> updateMarked(Object... keyValues) {
        Map<String, Object> entity = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            entity.put((String) keyValues[i], keyValues[i + 1]);
        }
        entity.put(PersistenceCapabilityContract.WRITE_MODE_KEY, PersistenceCapabilityContract.WRITE_MODE_UPDATE);
        return entity;
    }

    @Test
    void updateModeSaveMergesIntoTheStoredRowAndMovesItToTheNextVersion() {
        // REG-263: the untouched fields survive and the version advances (isolated map store).
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();
        adapter.save("Line", Map.of("id", "l-1", "sku", "SKU-A", "quantity", 2, "version", 0L));

        Map<?, ?> saved = (Map<?, ?>) adapter.save("Line", updateMarked("id", "l-1", "quantity", 7));

        Map<?, ?> stored = (Map<?, ?>) adapter.findById("Line", "l-1");
        assertEquals("SKU-A", stored.get("sku"));
        assertEquals(7, stored.get("quantity"));
        assertEquals(1L, stored.get("version"));
        assertFalse(saved.containsKey(PersistenceCapabilityContract.WRITE_MODE_KEY));
        assertFalse(stored.containsKey(PersistenceCapabilityContract.WRITE_MODE_KEY));
    }

    @Test
    void updateModeSaveMergesThroughTheSharedConceptStoreToo() {
        // REG-263 with the runtime's wiring: the SAME ConceptStore the generated service reads.
        InMemoryConceptStore store = new InMemoryConceptStore();
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter(null, store);
        adapter.save("Line", Map.of("id", "l-1", "sku", "SKU-A", "quantity", 2));

        adapter.save("Line", updateMarked("id", "l-1", "quantity", 7));

        Map<String, Object> stored = store.findById("default", "line", "l-1").orElseThrow().data();
        assertEquals("SKU-A", stored.get("sku"));
        assertEquals(7, stored.get("quantity"));
        assertEquals(1L, stored.get("version"));
    }

    @Test
    void updateModeSaveOfAnIdWithNoRowIsRefusedAndCreatesNothing() {
        // REG-264: the REST PUT answers 404; a flow updateConcept must not upsert the id into existence.
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> adapter.save("Line", updateMarked("id", "ghost", "quantity", 5)));

        assertTrue(refused.getMessage().contains("PERSISTENCE_RECORD_NOT_FOUND"), refused.getMessage());
        assertEquals(null, adapter.findById("Line", "ghost"));
        // An ordinary save of the same id is still a create -- only the update marker refuses.
        adapter.save("Line", Map.of("id", "ghost", "quantity", 5));
        assertEquals(5, ((Map<?, ?>) adapter.findById("Line", "ghost")).get("quantity"));
    }

    @Test
    void anOrdinarySaveOfAnExistingRowMergesWithoutTouchingItsVersion() {
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();
        adapter.save("Line", Map.of("id", "l-1", "sku", "SKU-A", "quantity", 2, "version", 4L));

        adapter.save("Line", Map.of("id", "l-1", "quantity", 8));

        Map<?, ?> stored = (Map<?, ?>) adapter.findById("Line", "l-1");
        assertEquals("SKU-A", stored.get("sku"));
        assertEquals(8, stored.get("quantity"));
        assertEquals(4L, stored.get("version"));
    }
}
