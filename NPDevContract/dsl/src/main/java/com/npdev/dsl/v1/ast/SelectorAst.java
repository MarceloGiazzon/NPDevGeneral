package com.npdev.dsl.v1.ast;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A standalone, reusable modal selector (e.g. WMS "Seleciona Ruas"): a named
 * multi- or single-select picker over a concept, referenced by grids/fields via
 * {@code picker}. Conceptually a prompt surface promoted to a first-class,
 * shareable declaration; the compiler expands it into an ordinary picker panel.
 * See ADR-0005 and docs/architecture/AGGREGATE_WORKBENCH_PLAN.md.
 *
 * <p>REAL_LIFT_PLAN_2026-09-03 package C2 (boundary B16 Step 2, EDIT-18): {@code
 * field.picker.selectorRef}/{@code transaction.bandPickers.<name>.selectorRef} finally wire the
 * "referenced by grids/fields via picker" claim above -- true for the first time. {@code filters}
 * (searchable field NAMES) and {@code columns} (display field names) map onto that reference's
 * search[]/display[] directly; {@code orderBy} (display field names, sort order) and {@code filter}
 * (a real {@link com.npdev.dsl.v1.query.PickerFilterGrammar} predicate, distinct from the
 * search-field-name {@code filters} list above) are the two properties that were missing.
 *
 * <p>FORM P5-T9a: the top-level {@code selectors[]} key no longer exists. The authoring form is a
 * {@code screens[]} entry of kind {@code picker}; this record is now the compile-time IR that
 * {@link #fromPickerScreens(List)} derives from such screens ({@code ModelCompiler} expands each into the same
 * picker panel the old key produced, so everything downstream is unchanged). Property mapping:
 * {@code name}, {@code concept}, {@code multiSelect}, {@code filters}, {@code returnMapping}, {@code columns}
 * (field names, or {@code {value}} objects), {@code orderBy} (a field name or a list of them) map 1:1; the
 * selector's {@code filter} predicate is the screen's {@code where}. {@code metadata} has no screen counterpart.
 */
public record SelectorAst(
        String name,
        String concept,
        boolean multiSelect,
        List<String> filters,
        List<String> columns,
        Map<String, Object> returnMapping,
        Map<String, Object> metadata,
        List<String> orderBy,
        String filter
) {
    public SelectorAst {
        filters = filters == null ? List.of() : List.copyOf(filters);
        columns = columns == null ? List.of() : List.copyOf(columns);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        // REG-175/REG-146: AutoPanelExpander.expandSelector nests this straight into
        // CompiledPanel.metadata["returnMapping"] -- the outer metadata map's own keys get sorted
        // before JSON serialization, but nested map VALUES are not, so this map's own iteration
        // order (randomized by Map.copyOf's JEP 269 mitigation) was reaching compiled-model.json.
        returnMapping = returnMapping == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(returnMapping));
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * The picker declarations among {@code screens} (entries with {@code kind: "picker"} and a name), in order.
     * A picker without a {@code concept} is skipped here: {@code ExperienceValidation} reports it, and there is
     * nothing to expand.
     */
    public static List<SelectorAst> fromPickerScreens(List<JsonNode> screens) {
        List<SelectorAst> out = new ArrayList<>();
        if (screens == null) {
            return out;
        }
        for (JsonNode screen : screens) {
            if (screen == null || !"picker".equals(text(screen, "kind"))) {
                continue;
            }
            String name = text(screen, "name");
            String concept = text(screen, "concept");
            if (name == null || name.isBlank() || concept == null || concept.isBlank()) {
                continue;
            }
            Map<String, Object> returnMapping = new LinkedHashMap<>();
            JsonNode mapping = screen.get("returnMapping");
            if (mapping != null && mapping.isObject()) {
                mapping.fields().forEachRemaining(e -> returnMapping.put(e.getKey(), plain(e.getValue())));
            }
            out.add(new SelectorAst(
                    name,
                    concept,
                    screen.path("multiSelect").asBoolean(false),
                    textList(screen.get("filters"), false),
                    textList(screen.get("columns"), true),
                    returnMapping,
                    Map.of(),
                    textList(screen.get("orderBy"), false),
                    text(screen, "where")));
        }
        return out;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /** A JSON string, or a JSON array of strings (or, when {@code allowValueObjects}, of {@code {"value": ..}} objects). */
    private static List<String> textList(JsonNode node, boolean allowValueObjects) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (node.isTextual()) {
            out.add(node.asText());
            return out;
        }
        if (!node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            } else if (allowValueObjects && item.isObject() && item.path("value").isTextual()) {
                out.add(item.get("value").asText());
            }
        }
        return out;
    }

    private static Object plain(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isArray()) {
            List<Object> items = new ArrayList<>();
            value.forEach(item -> items.add(plain(item)));
            return items;
        }
        if (value.isObject()) {
            Map<String, Object> entries = new LinkedHashMap<>();
            value.fields().forEachRemaining(e -> entries.put(e.getKey(), plain(e.getValue())));
            return entries;
        }
        return null;
    }
}
