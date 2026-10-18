package com.finalexec;

import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;
import com.npdev.dsl.v1.compiled.CompiledPanelSlot;
import com.npdev.dsl.v1.compiled.CompiledPanelWidget;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6 (layoutSlot, dashboard widgets): {@code loadPanel}'s {@code layout} echoes {@code slots} and
 * {@code widgets} to the browser only when the panel declares them, and a widget carries only the
 * optional keys it actually set -- so the client never has to tell "absent" from "empty".
 */
class PanelRuntimeLayoutResponseTest {
    private final RuntimeMetadataService metadataService = new RuntimeMetadataService(new ObjectMapper());

    @Test
    void layoutWithoutSlotsOrWidgetsOmitsBothKeys() {
        Map<String, Object> layout = layoutOf(new CompiledPanelLayout("table", List.of(), List.of("situacao"), Map.of()));

        assertEquals("table", layout.get("type"));
        assertEquals(List.of("situacao"), layout.get("fields"));
        assertFalse(layout.containsKey("slots"));
        assertFalse(layout.containsKey("widgets"));
    }

    @Test
    void panelWithoutLayoutServesAnEmptyLayout() {
        Map<String, Object> layout = layoutOf(null);

        assertTrue(layout.isEmpty());
    }

    @Test
    void declaredSlotsAreEchoedAndLabelAppearsOnlyWhenSet() {
        Map<String, Object> layout = layoutOf(new CompiledPanelLayout(
                "table", List.of(), List.of(), Map.of(),
                List.of(new CompiledPanelSlot("top", "Banner", "Aviso"), new CompiledPanelSlot("bottom", "Footer", null))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slots = (List<Map<String, Object>>) layout.get("slots");
        assertEquals(2, slots.size());
        assertEquals(Map.of("placement", "top", "component", "Banner", "label", "Aviso"), slots.get(0));
        assertEquals(Map.of("placement", "bottom", "component", "Footer"), slots.get(1));
        assertFalse(layout.containsKey("widgets"));
    }

    @Test
    void declaredWidgetsAreEchoedWithOnlyTheirSetKeys() {
        Map<String, Object> layout = layoutOf(new CompiledPanelLayout(
                "dashboard", List.of(), List.of(), Map.of(), List.of(),
                List.of(
                        new CompiledPanelWidget("stat", "Abertos", "orders", "count", null, null, null, "status", "OPEN", 2),
                        new CompiledPanelWidget("chart", null, "orders", "sum", "total", "status", "bar", null, null, null),
                        new CompiledPanelWidget("stat", null, "orders", null, null, null, null, "status", null, null))));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> widgets = (List<Map<String, Object>>) layout.get("widgets");
        assertEquals(3, widgets.size());
        assertEquals(
                Map.of("kind", "stat", "title", "Abertos", "dataSource", "orders", "aggregate", "count",
                        "filter", Map.of("field", "status", "equals", "OPEN"), "span", 2),
                widgets.get(0));
        assertEquals(
                Map.of("kind", "chart", "dataSource", "orders", "aggregate", "sum", "field", "total",
                        "groupBy", "status", "chart", "bar"),
                widgets.get(1));
        // a filter with a field but no value still serves an (empty) equals, never a null
        assertEquals(Map.of("field", "status", "equals", ""), widgets.get(2).get("filter"));
        assertFalse(layout.containsKey("slots"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> layoutOf(CompiledPanelLayout layout) {
        PanelRuntime runtime = new PanelRuntime(metadataService, null, model(layout), null, null, null);
        return (Map<String, Object>) runtime.loadPanel("LayoutPanel", Map.of(), ExecutionContext.anonymous()).get("layout");
    }

    private static CompiledModel model(CompiledPanelLayout layout) {
        CompiledPanel panel = new CompiledPanel(
                "LayoutPanel",
                "/layout",
                "Layout",
                List.of(new CompiledPanelDataSource("orders", "Order", null, null, Map.of(), null, null, null)),
                layout,
                List.of(),
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                null
        );
        return new CompiledModel(
                "panel.runtime.layout",
                "1.0.0",
                "1.0.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(panel)
        );
    }
}
