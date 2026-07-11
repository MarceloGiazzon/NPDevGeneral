package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies AutoPanels survive the compiled-model canonical JSON round trip (P1 slice 1). */
class CompiledAutoPanelCanonicalJsonTest {

    private static CompiledModel modelWithAutoPanels() {
        CompiledAutoPanel slim = new CompiledAutoPanel(
                null, "Cliente", null, null,
                List.of(), null, null, null, null, Map.of());

        CompiledAutoPanel rich = new CompiledAutoPanel(
                "ExpedicaoWorkWith", null, "Expedicao", "/expedicao",
                List.of("selection", "detail", "transaction"),
                new CompiledAutoPanelSurface(List.of("cliente", "situacao"), List.of("id", "cliente"), List.of(), null, Map.of()),
                null,
                new CompiledAutoPanelSurface(List.of(), List.of(), List.of("cliente", "veiculo", "observacao"), null, Map.of()),
                new CompiledAutoPanelSurface(List.of(), List.of(), List.of(), "cliente", Map.of()),
                Map.of());

        return new CompiledModel(
                "wms.agg", "1.0.0", "1.0", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(slim, rich));
    }

    @Test
    void autoPanelsSurviveCanonicalRoundTrip() throws Exception {
        String json = CompiledModelCanonicalJson.toJson(modelWithAutoPanels());
        CompiledModel back = CompiledModelCanonicalJsonReader.fromJson(json);

        assertEquals(2, back.getAutoPanels().size());
        // Name-sorted: "Cliente" (slim, keyed by concept) < "ExpedicaoWorkWith".
        CompiledAutoPanel slim = back.getAutoPanels().get(0);
        assertEquals("Cliente", slim.concept());
        assertNull(slim.selection(), "unset surface blocks stay null (derive-from-concept)");
        assertTrue(slim.surfaces().isEmpty());

        CompiledAutoPanel rich = back.getAutoPanels().get(1);
        assertEquals("ExpedicaoWorkWith", rich.name());
        assertEquals("Expedicao", rich.aggregate());
        assertEquals("/expedicao", rich.route());
        assertEquals(List.of("selection", "detail", "transaction"), rich.surfaces());
        assertNotNull(rich.selection());
        assertEquals(List.of("cliente", "situacao"), rich.selection().filters());
        assertEquals(List.of("id", "cliente"), rich.selection().columns());
        assertNull(rich.detail(), "unset detail stays null");
        assertEquals(List.of("cliente", "veiculo", "observacao"), rich.transaction().fields());
        assertEquals("cliente", rich.prompt().labelField());
    }
}
