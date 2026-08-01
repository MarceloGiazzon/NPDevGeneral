package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B16/B19 (Move 9 A3, {@code docs/ACCEPTED_BOUNDARIES.md}): {@code CompiledField.picker} and a
 * {@code CompiledWorkbenchBandPicker}'s {@code filter}/{@code multiSelect} must survive the
 * compiled-model canonical JSON round trip -- the writer/reader pair R0.3 warns about. This exact
 * reader file has a documented history (HARDEN-OBJSTORE, {@code file}-field metadata) of a
 * writer-only field silently vanishing on read, defeating the feature in every generated app while
 * every unit test that only calls the writer stayed green.
 */
class CompiledFieldPickerCanonicalJsonTest {

    @Test
    void fieldPickerSurvivesCanonicalRoundTrip() throws Exception {
        CompiledField widgetRef = new CompiledField(
                "widgetRef", "reference", "String", false, true, false,
                List.of(), "Widget", null, null, null, List.of(), null,
                null, null, null, false,
                new CompiledFieldPicker("ativo == true", true)
        );
        CompiledConcept order = new CompiledConcept("Order", "Order", "orders", List.of(widgetRef));
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0", Map.of(order.getName(), order));

        String json = CompiledModelCanonicalJson.toJson(model);
        CompiledModel back = CompiledModelCanonicalJsonReader.fromJson(json);

        CompiledField backField = back.getConcepts().iterator().next().getFields().get(0);
        assertEquals("widgetRef", backField.getName());
        CompiledFieldPicker picker = backField.getPicker();
        assertNotNull(picker, "picker must survive the canonical JSON round trip, not silently vanish on read");
        assertEquals("ativo == true", picker.filter());
        assertTrue(picker.multiSelect());
    }

    @Test
    void fieldWithNoPickerRoundTripsToANullPicker() throws Exception {
        CompiledField plain = new CompiledField("name", "string", "String", false, true, false);
        CompiledConcept order = new CompiledConcept("Order", "Order", "orders", List.of(plain));
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0", Map.of(order.getName(), order));

        String json = CompiledModelCanonicalJson.toJson(model);
        CompiledModel back = CompiledModelCanonicalJsonReader.fromJson(json);

        assertEquals(null, back.getConcepts().iterator().next().getFields().get(0).getPicker());
    }

    @Test
    void bandPickerFilterAndMultiSelectSurviveCanonicalRoundTrip() throws Exception {
        CompiledAutoPanel rich = new CompiledAutoPanel(
                "ExpedicaoWorkWith", null, "Expedicao", "/expedicao",
                List.of("transaction"),
                null,
                null,
                new CompiledAutoPanelSurface(List.of(), List.of(), List.of(), List.of(), null, Map.of(), null,
                        List.of(), Map.of(), List.of(), Map.of(),
                        Map.of("itens", new CompiledWorkbenchBandPicker(
                                "ItemSelectionPanel", "Choose item", List.of("sku"), "ativo == true", true)),
                        null, Map.of()),
                null,
                Map.of());
        CompiledModel model = new CompiledModel(
                "wms.agg", "1.0.0", "1.0", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(rich));

        String json = CompiledModelCanonicalJson.toJson(model);
        CompiledModel back = CompiledModelCanonicalJsonReader.fromJson(json);

        CompiledWorkbenchBandPicker picker = back.getAutoPanels().get(0).transaction().bandPickers().get("itens");
        assertNotNull(picker, "bandPicker must survive the canonical JSON round trip");
        assertEquals("ItemSelectionPanel", picker.panel());
        assertEquals("ativo == true", picker.filter());
        assertTrue(picker.multiSelect());
    }
}
