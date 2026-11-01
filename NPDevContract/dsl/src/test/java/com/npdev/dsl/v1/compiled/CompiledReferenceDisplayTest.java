package com.npdev.dsl.v1.compiled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** REG-318(6): the reference's display field a grouped query labels with is the one the grid uses. */
class CompiledReferenceDisplayTest {

    private static CompiledModel model() {
        CompiledConcept site = new CompiledConcept("Site", "Site", "sites", List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField("code", "string", "String", false, false, false),
                new CompiledField("name", "string", "String", false, false, false)));
        CompiledConcept visit = new CompiledConcept("Visit", "Visit", "visits", List.of(
                new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                new CompiledField("site", "reference", "java.util.UUID", false, false, false,
                        List.of(), "Site"),
                new CompiledField("notes", "string", "String", false, false, false)));
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("Site", site);
        concepts.put("Visit", visit);
        return new CompiledModel("t", "1.0.0", "1.0", concepts);
    }

    @Test
    void aReferenceFieldResolvesToItsTargetAndTheInferredDisplayField() {
        CompiledReferenceDisplay.Target target = CompiledReferenceDisplay.of(model(), "Visit", "site").orElseThrow();
        assertEquals("Site", target.conceptName());
        assertEquals("name", target.displayField());
    }

    @Test
    void aPlainFieldAnUnknownFieldOrAnUnknownConceptResolvesToNothing() {
        assertTrue(CompiledReferenceDisplay.of(model(), "Visit", "notes").isEmpty());
        assertTrue(CompiledReferenceDisplay.of(model(), "Visit", "nope").isEmpty());
        assertTrue(CompiledReferenceDisplay.of(model(), "Nope", "site").isEmpty());
        assertTrue(CompiledReferenceDisplay.of(null, "Visit", "site").isEmpty());
    }
}
