package com.npdev.generator.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S5 ({@code __OutsideRepo\s5\S5_SPEC.md} I1). Each case below is one of I1's own DoD bullets:
 * two submissions touching different concepts land as disjoint touched-sets; two submissions
 * touching the SAME element intersect and name it; a bare version-only change (nothing else
 * touched) collapses to {@code wholeDocument} rather than reporting "no changes"; and an
 * unattributable array (no usable {@code name}, e.g. {@code bindings[]}) collapses the same way.
 */
class ElementDifferTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static final String BASE = """
        { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
          "concepts": [
            { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
          ] }
        """;

    @Test
    void twoSubmissionsTouchingDifferentConceptsAreDisjoint() throws Exception {
        // NOTE: version deliberately left at "1.0" (unchanged) in both submissions here -- this
        // test is about ELEMENT attribution in isolation. ElementDiffer has no special case for
        // "version" (see versionOnlyChangeIsWholeDocumentNotSilentlyEmpty below); a real 3-way
        // merge's version bumps are stripped by AuthoringMergeGate before it calls this differ.
        JsonNode base = json(BASE);
        JsonNode submission1 = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Shipment", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);
        JsonNode submission2 = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Gadget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);

        ElementDiffer.DiffResult diffA = ElementDiffer.diff(base, submission1);
        ElementDiffer.DiffResult diffB = ElementDiffer.diff(base, submission2);

        assertFalse(diffA.wholeDocument(), diffA.wholeDocumentReasons().toString());
        assertFalse(diffB.wholeDocument(), diffB.wholeDocumentReasons().toString());
        assertEquals(Set.of(new ElementDiffer.ElementKey("concepts", "Shipment")), diffA.touchedKeys());
        assertEquals(Set.of(new ElementDiffer.ElementKey("concepts", "Gadget")), diffB.touchedKeys());

        Set<ElementDiffer.ElementKey> intersection = new java.util.HashSet<>(diffA.touchedKeys());
        intersection.retainAll(diffB.touchedKeys());
        assertTrue(intersection.isEmpty(), "expected disjoint touched-sets, got " + intersection);
    }

    @Test
    void twoSubmissionsTouchingTheSameConceptCollideAndNameIt() throws Exception {
        JsonNode base = json(BASE);
        JsonNode submission1 = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "notes", "type": "string" } ] } ] }
            """);
        JsonNode submission2 = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.1",
              "concepts": [
                { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "total", "type": "int" } ] } ] }
            """);

        ElementDiffer.DiffResult diffA = ElementDiffer.diff(base, submission1);
        ElementDiffer.DiffResult diffB = ElementDiffer.diff(base, submission2);

        Set<ElementDiffer.ElementKey> intersection = new java.util.HashSet<>(diffA.touchedKeys());
        intersection.retainAll(diffB.touchedKeys());

        assertEquals(Set.of(new ElementDiffer.ElementKey("concepts", "Order")), intersection);
    }

    @Test
    void versionOnlyChangeIsWholeDocumentNotSilentlyEmpty() throws Exception {
        JsonNode base = json(BASE);
        JsonNode versionBumpOnly = json(BASE.replace("\"version\": \"1.0\"", "\"version\": \"1.1\""));

        ElementDiffer.DiffResult diff = ElementDiffer.diff(base, versionBumpOnly);

        assertTrue(diff.wholeDocument(), "a bare version change must not silently report zero touched elements");
        assertTrue(diff.changes().isEmpty(), "no element actually changed -- only the unattributable scalar did");
    }

    @Test
    void anAddedConceptIsClassifiedAdded() throws Exception {
        JsonNode base = json(BASE);
        JsonNode withNewConcept = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Invoice", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);

        ElementDiffer.DiffResult diff = ElementDiffer.diff(base, withNewConcept);

        assertFalse(diff.wholeDocument());
        assertEquals(1, diff.changes().size());
        assertEquals(ElementDiffer.ChangeKind.ADDED, diff.changes().get(0).kind());
        assertEquals("Invoice", diff.changes().get(0).key().name());
    }

    @Test
    void aRemovedConceptIsClassifiedRemoved() throws Exception {
        JsonNode base = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Invoice", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ] }
            """);
        JsonNode withoutInvoice = json(BASE);

        ElementDiffer.DiffResult diff = ElementDiffer.diff(base, withoutInvoice);

        assertFalse(diff.wholeDocument());
        assertEquals(1, diff.changes().size());
        assertEquals(ElementDiffer.ChangeKind.REMOVED, diff.changes().get(0).kind());
        assertEquals("Invoice", diff.changes().get(0).key().name());
    }

    @Test
    void unnamedArrayElementsCollapseToWholeDocument_bindings() throws Exception {
        JsonNode base = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [ { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "bindings": [ { "capability": "storage", "adapter": "inproc" } ] }
            """);
        JsonNode changedBinding = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [ { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "bindings": [ { "capability": "storage", "adapter": "postgres" } ] }
            """);

        ElementDiffer.DiffResult diff = ElementDiffer.diff(base, changedBinding);

        assertTrue(diff.wholeDocument(), "bindings[] has no 'name' field -- must be unattributable, not guessed");
    }

    @Test
    void unnamedArrayElementsThatDidNotChangeDoNotTriggerWholeDocument() throws Exception {
        // Regression: an unnamed array (bindings[]) present and IDENTICAL on both sides must not
        // poison an otherwise-clean diff just because it CAN'T be named -- only an actual change
        // to it is unattributable. Real corpus models declare static bindings[] on every submission.
        JsonNode base = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [ { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "bindings": [ { "capability": "storage", "adapter": "inproc" } ] }
            """);
        JsonNode withNewConceptSameBindings = json("""
            { "dslVersion": "1.0.0", "namespace": "s5.test", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "Invoice", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "bindings": [ { "capability": "storage", "adapter": "inproc" } ] }
            """);

        ElementDiffer.DiffResult diff = ElementDiffer.diff(base, withNewConceptSameBindings);

        assertFalse(diff.wholeDocument(), diff.wholeDocumentReasons().toString());
        assertEquals(Set.of(new ElementDiffer.ElementKey("concepts", "Invoice")), diff.touchedKeys());
    }

    @Test
    void noChangesAtAllIsNeitherWholeDocumentNorTouched() throws Exception {
        JsonNode base = json(BASE);
        JsonNode sameAgain = json(BASE);

        ElementDiffer.DiffResult diff = ElementDiffer.diff(base, sameAgain);

        assertFalse(diff.wholeDocument());
        assertTrue(diff.changes().isEmpty());
        assertTrue(diff.touchedKeys().isEmpty());
    }
}
