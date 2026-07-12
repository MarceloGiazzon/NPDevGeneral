package com.npdev.dsl.v1.compiler;

import com.npdev.dsl.v1.compiled.CompiledAutoPanel;
import com.npdev.dsl.v1.compiled.CompiledAutoPanelSurface;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelFieldBinding;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Expands a concept-bound {@link CompiledAutoPanel} into the coordinated set of
 * ordinary {@link CompiledPanel} surfaces it stands for — Selection (list),
 * Detail (view), Transaction (form) — reading defaults from the concept's fields
 * (ADR-0005). The synthesized panels reuse the entire existing panel pipeline;
 * the AutoPanel record is retained separately as the declarative intent.
 *
 * <p>Aggregate-bound AutoPanels are NOT expanded here — their Transaction becomes
 * the multi-level Aggregate Workbench, delivered in a later phase (P4).
 */
final class AutoPanelExpander {

    private AutoPanelExpander() {
    }

    /**
     * @param autoPanel  a concept-bound compiled AutoPanel
     * @param fieldNames declared field names of the bound concept, in order
     * @param idField    the concept's id field name (may be null)
     */
    static List<CompiledPanel> expand(CompiledAutoPanel autoPanel, List<String> fieldNames, String idField) {
        List<CompiledPanel> panels = new ArrayList<>();
        String concept = autoPanel.concept();
        if (concept == null || concept.isBlank()) {
            return panels; // aggregate-bound (or unbound) — not handled here
        }
        String base = hasText(autoPanel.name()) ? autoPanel.name() : concept;
        String baseRoute = hasText(autoPanel.route())
                ? autoPanel.route()
                : "/" + concept.toLowerCase(Locale.ROOT);

        if (surfaceEnabled(autoPanel, "selection")) {
            panels.add(selectionPanel(autoPanel, concept, base, baseRoute, fieldNames));
        }
        if (surfaceEnabled(autoPanel, "detail")) {
            panels.add(detailPanel(autoPanel, concept, base, baseRoute, fieldNames));
        }
        if (surfaceEnabled(autoPanel, "transaction")) {
            panels.add(transactionPanel(autoPanel, concept, base, baseRoute, fieldNames, idField));
        }
        if (surfaceEnabled(autoPanel, "prompt")) {
            panels.add(promptPanel(autoPanel, concept, base, baseRoute, fieldNames, idField));
        }
        return panels;
    }

    private static boolean surfaceEnabled(CompiledAutoPanel autoPanel, String surface) {
        List<String> surfaces = autoPanel.surfaces();
        if (surfaces.isEmpty()) {
            return true; // default: all surfaces
        }
        return surfaces.stream().anyMatch(s -> s != null && s.trim().equalsIgnoreCase(surface));
    }

    private static CompiledPanel selectionPanel(
            CompiledAutoPanel autoPanel, String concept, String base, String baseRoute, List<String> fieldNames) {
        List<String> columns = override(autoPanel.selection(), CompiledAutoPanelSurface::columns, fieldNames);
        CompiledPanelDataSource rows = conceptDataSource(concept);
        CompiledPanelLayout layout = new CompiledPanelLayout("table", List.of(), columns, Map.of());
        return new CompiledPanel(
                base + "Selection", baseRoute, concept,
                List.of(rows), layout, List.of(), null, null,
                List.of(newRecordAction(concept)),
                Map.of(), surfaceMetadata(base, "selection", concept), null);
    }

    private static CompiledPanel detailPanel(
            CompiledAutoPanel autoPanel, String concept, String base, String baseRoute, List<String> fieldNames) {
        List<String> fields = override(autoPanel.detail(), CompiledAutoPanelSurface::fields, fieldNames);
        CompiledPanelLayout layout = new CompiledPanelLayout("detail", List.of(), fields, Map.of());
        List<CompiledPanelFieldBinding> bindings = bindings(fields, false);
        return new CompiledPanel(
                base + "Detail", baseRoute + "/{id}", concept,
                List.of(conceptDataSource(concept)), layout, bindings, null, null,
                List.of(), Map.of(), surfaceMetadata(base, "detail", concept), null);
    }

    private static CompiledPanel transactionPanel(
            CompiledAutoPanel autoPanel, String concept, String base, String baseRoute,
            List<String> fieldNames, String idField) {
        List<String> defaultEditable = new ArrayList<>();
        for (String field : fieldNames) {
            if (idField == null || !field.equalsIgnoreCase(idField)) {
                defaultEditable.add(field);
            }
        }
        List<String> fields = override(autoPanel.transaction(), CompiledAutoPanelSurface::fields, defaultEditable);
        CompiledPanelLayout layout = new CompiledPanelLayout("form", List.of(), fields, Map.of());
        List<CompiledPanelFieldBinding> bindings = bindings(fields, true);
        List<CompiledPanelAction> actions = List.of(
                mutationAction("save", "Save", concept, "save"),
                mutationAction("delete", "Delete", concept, "delete"));
        return new CompiledPanel(
                base + "Form", baseRoute + "/edit", concept,
                List.of(conceptDataSource(concept)), layout, bindings, null, null,
                actions, Map.of(), surfaceMetadata(base, "transaction", concept), null);
    }

    /**
     * A modal picker over the concept: choose one row (for foreign-key selection from other forms).
     * Read-only table; metadata declares the display {@code labelField} and the {@code returnField}
     * (the value handed back to the caller). Multi-select pickers over stock locations are an
     * aggregate/band concern (P4), not a single-concept surface.
     */
    private static CompiledPanel promptPanel(
            CompiledAutoPanel autoPanel, String concept, String base, String baseRoute,
            List<String> fieldNames, String idField) {
        String labelField = promptLabelField(autoPanel, fieldNames, idField);
        List<String> columns = override(autoPanel.prompt(), CompiledAutoPanelSurface::columns, fieldNames);
        CompiledPanelLayout layout = new CompiledPanelLayout("table", List.of(), columns, Map.of());
        Map<String, Object> metadata = surfaceMetadata(base, "prompt", concept);
        metadata.put("labelField", labelField == null ? "" : labelField);
        metadata.put("returnField", idField == null ? "" : idField);
        metadata.put("multiSelect", false);
        return new CompiledPanel(
                base + "Prompt", baseRoute + "/prompt", concept,
                List.of(conceptDataSource(concept)), layout, List.of(), null, null,
                List.of(), Map.of(), metadata, null);
    }

    /** Prompt display field: explicit override, else the first non-id field, else the id field. */
    private static String promptLabelField(CompiledAutoPanel autoPanel, List<String> fieldNames, String idField) {
        if (autoPanel.prompt() != null && hasText(autoPanel.prompt().labelField())) {
            return autoPanel.prompt().labelField();
        }
        for (String field : fieldNames) {
            if (idField == null || !field.equalsIgnoreCase(idField)) {
                return field;
            }
        }
        return idField;
    }

    private static CompiledPanelDataSource conceptDataSource(String concept) {
        return new CompiledPanelDataSource("rows", concept, null, null, Map.of(), null, null, null);
    }

    private static List<CompiledPanelFieldBinding> bindings(List<String> fields, boolean editable) {
        List<CompiledPanelFieldBinding> out = new ArrayList<>();
        for (String field : fields) {
            out.add(new CompiledPanelFieldBinding(field, "rows", null, null, null, null, editable));
        }
        return out;
    }

    private static CompiledPanelAction newRecordAction(String concept) {
        return mutationAction("new", "New", concept, "create");
    }

    private static CompiledPanelAction mutationAction(String name, String label, String concept, String operation) {
        return new CompiledPanelAction(
                name, label, "conceptMutation", concept, operation,
                null, null, null, null, List.of(), Map.of(), Map.of());
    }

    private static Map<String, Object> surfaceMetadata(String autoPanel, String surface, String concept) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("generatedBy", "autoPanel");
        metadata.put("autoPanel", autoPanel);
        metadata.put("surface", surface);
        metadata.put("concept", concept);
        return metadata;
    }

    private static List<String> override(
            CompiledAutoPanelSurface surface,
            java.util.function.Function<CompiledAutoPanelSurface, List<String>> accessor,
            List<String> fallback) {
        if (surface != null) {
            List<String> declared = accessor.apply(surface);
            if (declared != null && !declared.isEmpty()) {
                return new ArrayList<>(declared);
            }
        }
        return new ArrayList<>(fallback);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
