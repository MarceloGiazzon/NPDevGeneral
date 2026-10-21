package com.npdev.runtime.support;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.EventBus;
import com.npdev.kernel.ports.InvariantEngine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LIVE-1: the runtime-support half of "add a field to a running app". A field added to an existing
 * concept by a model reload must (1) appear in {@code liveFieldNames} (the generated service's
 * request whitelist), (2) be WRITTEN onto an entity that was generated without it -- through the
 * {@code ExtraFieldAccess} bag, instead of being silently dropped -- and (3) survive the Jackson
 * round trip the generated service uses between entity and store record.
 */
class GeneratedCrudRuntimeSupportLiveFieldTest {

    /** Shaped exactly like the generated entity: a baked field plus the extras bag. */
    public static class WidgetEntity {
        private UUID id;
        private String name;
        private final Map<String, Object> extras = new LinkedHashMap<>();

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        @JsonAnySetter
        public void putExtraField(String fieldName, Object value) { extras.put(fieldName, value); }

        @JsonAnyGetter
        public Map<String, Object> extraFields() { return extras; }
    }

    private static CompiledModel model(boolean withColour) {
        CompiledField id = new CompiledField("id", "uuid", "java.util.UUID", true, true, false);
        CompiledField name = new CompiledField("name", "string", "String", false, false, false);
        CompiledField colour = new CompiledField("colour", "string", "String", false, false, false);
        CompiledField qty = new CompiledField("qty", "int", "Integer", false, false, false);
        CompiledConcept widget = new CompiledConcept("Widget", "Widget", "widgets",
                withColour ? List.of(id, name, colour, qty) : List.of(id, name));
        return new CompiledModel("demo", "1.0.1", "v1", Map.of("Widget", widget));
    }

    private static GeneratedCrudRuntimeSupport support(AtomicReference<CompiledModel> model) {
        return new GeneratedCrudRuntimeSupport(model::get, new KernelRunner(
                (EventBus) event -> { },
                new InvariantEngine() {
                    @Override
                    public List<String> evaluate(String entityName, Object payload) {
                        return List.of();
                    }
                }));
    }

    @Test
    void liveFieldNamesFollowTheSwappedModel() {
        AtomicReference<CompiledModel> current = new AtomicReference<>(model(false));
        GeneratedCrudRuntimeSupport support = support(current);

        assertEquals(Set.of("id", "name"), support.liveFieldNames("Widget"));
        current.set(model(true));
        assertEquals(Set.of("id", "name", "colour", "qty"), support.liveFieldNames("Widget"),
                "the SAME instance must see the field added by the reload");
        assertTrue(support.liveFieldNames("Nope").isEmpty());
        assertTrue(support.liveFieldNames(null).isEmpty());
    }

    @Test
    void aFieldTheEntityWasNotGeneratedWithIsWrittenToTheExtrasBagNotDropped() {
        AtomicReference<CompiledModel> current = new AtomicReference<>(model(true));
        GeneratedCrudRuntimeSupport support = support(current);
        WidgetEntity entity = new WidgetEntity();

        support.applyCreateFields("Widget", Map.of("name", "bolt", "colour", "red", "qty", "7"), entity);

        assertEquals("bolt", entity.getName());
        assertEquals("red", entity.extraFields().get("colour"));
        assertEquals(7, ((Number) entity.extraFields().get("qty")).intValue(),
                "the value is normalised by the field's DSL type before it reaches the bag");
    }

    @Test
    void aPatchKeepsTheExistingExtraValueAndAnExplicitUpdateReplacesIt() {
        AtomicReference<CompiledModel> current = new AtomicReference<>(model(true));
        GeneratedCrudRuntimeSupport support = support(current);
        WidgetEntity entity = new WidgetEntity();
        support.applyCreateFields("Widget", Map.of("name", "bolt", "colour", "red"), entity);

        support.applyUpdateFields("Widget", Map.of("name", "nut"), entity);
        assertEquals("nut", entity.getName());
        assertEquals("red", entity.extraFields().get("colour"), "a patch that omits the field must not clear it");

        support.applyUpdateFields("Widget", Map.of("colour", "blue"), entity);
        assertEquals("blue", entity.extraFields().get("colour"));
    }

    @Test
    void theExtrasSurviveTheEntityToMapToEntityRoundTripTheGeneratedServiceUses() {
        ObjectMapper mapper = new ObjectMapper();
        WidgetEntity entity = new WidgetEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("bolt");
        entity.putExtraField("colour", "red");

        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = mapper.convertValue(entity, Map.class);
        assertEquals("red", asMap.get("colour"), "extras must be flattened into the store map (any-getter)");
        assertFalse(asMap.containsKey("extras") || asMap.containsKey("extraFields"),
                "the bag itself must not leak as a property");

        asMap.put("shade", "dark"); // a column the entity has no field for -- used to throw
        WidgetEntity back = mapper.convertValue(asMap, WidgetEntity.class);
        assertEquals("bolt", back.getName());
        assertEquals("red", back.extraFields().get("colour"));
        assertEquals("dark", back.extraFields().get("shade"));
    }

    @Test
    void aTargetWithoutTheBagStillDropsAnUnknownFieldAsBefore() {
        AtomicReference<CompiledModel> current = new AtomicReference<>(model(true));
        GeneratedCrudRuntimeSupport support = support(current);
        class Plain {
            public String name;
        }
        Plain plain = new Plain();

        support.applyCreateFields("Widget", Map.of("name", "bolt", "colour", "red"), plain);

        assertEquals("bolt", plain.name);
    }
}
