package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.FieldWidgetDefaults;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave 2.2: emits {@code static/widget-catalog.json} + {@code static/widget-catalog.html} into
 * every generated app, both built from {@link FieldWidgetDefaults#catalogue()} -- the single
 * source of truth also read by the {@code npdev widgets} CLI command, so no caller hand-maintains
 * its own copy of the widget list. Unconditional, like {@link ModelSurfaceEmitter} and
 * {@link ModelAuthoringEmitter}: the catalogue describes the widget SYSTEM, not this app's own
 * panels, so it has no dependency on {@code UI_GENERATE_BUSINESS_UI}.
 *
 * <p>The HTML page renders a real, interactive control for every widget whose behavior is fully
 * self-contained (slider, toggle, rating, currency, password, uuid, richtext, file, plus the plain
 * native-input widgets). Widgets whose rendering depends on live model data that does not exist in
 * a catalogue context -- a reference's actual rows, an enum's declared values, a custom widget's
 * registration -- get a labeled placeholder explaining why, rather than a fake demo.
 */
public final class WidgetCatalogueEmitter extends AbstractEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** Widgets whose live demo needs data (reference rows, enum values, a custom registration)
     * that a standalone catalogue page has none of -- these get an explanatory placeholder instead
     * of a fake control. */
    private static final List<String> NEEDS_MODEL_CONTEXT = List.of(
            FieldWidgetDefaults.SELECT, FieldWidgetDefaults.AUTOCOMPLETE, FieldWidgetDefaults.LOOKUP,
            FieldWidgetDefaults.SEARCH_DIALOG, FieldWidgetDefaults.MULTISELECT,
            FieldWidgetDefaults.IMAGE_SELECT, FieldWidgetDefaults.CUSTOM,
            FieldWidgetDefaults.GROUP, FieldWidgetDefaults.LIST
    );

    public WidgetCatalogueEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        List<FieldWidgetDefaults.WidgetCatalogueEntry> entries = FieldWidgetDefaults.catalogue();

        List<Map<String, Object>> jsonEntries = new ArrayList<>();
        for (FieldWidgetDefaults.WidgetCatalogueEntry entry : entries) {
            Map<String, Object> jsonEntry = new LinkedHashMap<>();
            jsonEntry.put("name", entry.name());
            jsonEntry.put("compatibleTypes", entry.compatibleTypes());
            jsonEntry.put("isDefaultFor", entry.isDefaultFor());
            jsonEntry.put("description", entry.description());
            jsonEntries.add(jsonEntry);
        }
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(jsonEntries);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize widget catalogue", e);
        }
        writer.writeRelative("src/main/resources/static/widget-catalog.json", json);

        List<Map<String, Object>> templateEntries = new ArrayList<>();
        for (FieldWidgetDefaults.WidgetCatalogueEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", entry.name());
            row.put("compatibleTypesJoined", String.join(", ", entry.compatibleTypes()));
            row.put("isDefaultForJoined", entry.isDefaultFor().isEmpty()
                    ? "" : String.join(", ", entry.isDefaultFor()));
            row.put("hasDefaults", !entry.isDefaultFor().isEmpty());
            row.put("description", entry.description());
            row.put("needsModelContext", NEEDS_MODEL_CONTEXT.contains(entry.name()));
            templateEntries.add(row);
        }

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("namespace", model.getNamespace() == null ? "" : model.getNamespace());
        ctx.put("widgets", templateEntries);
        ctx.put("widgetCount", entries.size());
        writer.writeRelative("src/main/resources/static/widget-catalog.html",
                templates.render("widget-catalog.mustache", ctx));
    }
}
