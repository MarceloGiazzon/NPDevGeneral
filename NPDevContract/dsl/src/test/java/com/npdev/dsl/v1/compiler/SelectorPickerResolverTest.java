package com.npdev.dsl.v1.compiler;

import com.npdev.dsl.v1.ast.SelectorAst;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REAL_LIFT_PLAN_2026-09-03 package C2 (boundary B16 Step 2, EDIT-18): the shared merge/adoption
 * logic behind {@code field.picker.selectorRef} / {@code transaction.bandPickers.<name>.selectorRef},
 * tested directly rather than only through the two compile-time call sites that use it.
 */
class SelectorPickerResolverTest {

    private static SelectorAst selector(String name, List<String> columns, List<String> filters,
                                         List<String> orderBy, String filter) {
        return new SelectorAst(name, "SomeConcept", false, filters, columns, Map.of(), Map.of(), orderBy, filter);
    }

    @Test
    void noSelectorRefReturnsOnlyTheLocalFilterUnchanged() {
        SelectorPickerResolver.Resolved resolved =
                SelectorPickerResolver.resolve(null, "name == 'x'", Map.of());

        assertTrue(resolved.displayFields().isEmpty());
        assertTrue(resolved.searchFields().isEmpty());
        assertTrue(resolved.orderBy().isEmpty());
        assertEquals("name == 'x'", resolved.filter());
    }

    @Test
    void aResolvedSelectorRefAdoptsDisplaySearchOrderAndFilterWholesale() {
        Map<String, SelectorAst> byName = Map.of(
                "Picker", selector("Picker", List.of("name", "sku"), List.of("name"), List.of("sku"), "active == true"));

        SelectorPickerResolver.Resolved resolved = SelectorPickerResolver.resolve("Picker", null, byName);

        assertEquals(List.of("name", "sku"), resolved.displayFields());
        assertEquals(List.of("name"), resolved.searchFields());
        assertEquals(List.of("sku"), resolved.orderBy());
        assertEquals("active == true", resolved.filter());
    }

    @Test
    void aLocalFilterAndComposesOnTopOfTheSelectorsOwnFilter() {
        Map<String, SelectorAst> byName = Map.of(
                "Picker", selector("Picker", List.of(), List.of(), List.of(), "active == true"));

        SelectorPickerResolver.Resolved resolved =
                SelectorPickerResolver.resolve("Picker", "name != 'x'", byName);

        assertEquals("active == true && name != 'x'", resolved.filter());
    }

    @Test
    void aSelectorWithNoFilterOfItsOwnUsesOnlyTheLocalFilter() {
        Map<String, SelectorAst> byName = Map.of(
                "Picker", selector("Picker", List.of("name"), List.of(), List.of(), null));

        SelectorPickerResolver.Resolved resolved =
                SelectorPickerResolver.resolve("Picker", "name != 'x'", byName);

        assertEquals(List.of("name"), resolved.displayFields());
        assertEquals("name != 'x'", resolved.filter());
    }

    @Test
    void anUnresolvableSelectorRefFallsBackToTheLocalFilterAloneWithNoAdoptedFields() {
        SelectorPickerResolver.Resolved resolved =
                SelectorPickerResolver.resolve("DoesNotExist", "name != 'x'", Map.of());

        assertTrue(resolved.displayFields().isEmpty());
        assertTrue(resolved.searchFields().isEmpty());
        assertTrue(resolved.orderBy().isEmpty());
        assertEquals("name != 'x'", resolved.filter());
    }

    @Test
    void noSelectorRefAndNoLocalFilterResolvesToANullFilter() {
        SelectorPickerResolver.Resolved resolved = SelectorPickerResolver.resolve(null, null, Map.of());

        assertNull(resolved.filter());
    }

    @Test
    void blankLocalFilterIsTreatedAsAbsent() {
        SelectorPickerResolver.Resolved resolved = SelectorPickerResolver.resolve(null, "   ", Map.of());

        assertNull(resolved.filter());
    }
}
