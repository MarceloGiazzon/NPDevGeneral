package com.npdev.adapters.flowcompiled;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledEvent;
import com.npdev.dsl.v1.compiled.CompiledEventField;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ports.EventSchemaProvider;
import com.npdev.kernel.schema.SchemaObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class CompiledModelEventSchemaProvider implements EventSchemaProvider {
    private final Map<String, SchemaObject> schemaByEventName = new LinkedHashMap<>();

    public CompiledModelEventSchemaProvider(CompiledModel compiledModel) {
        if (compiledModel == null) {
            throw new IllegalArgumentException("compiledModel must be non-null");
        }
        for (CompiledEvent event : compiledModel.getEvents()) {
            if (event == null || event.getName() == null) {
                continue;
            }
            String eventNameKey = normalize(event.getName());
            SchemaObject schema = buildEventSchema(compiledModel, event);
            if (schema != null) {
                schemaByEventName.put(eventNameKey, schema);
            }
        }
    }

    @Override
    public Optional<SchemaObject> findEventPayloadSchema(String eventName) {
        if (eventName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(schemaByEventName.get(normalize(eventName)));
    }

    private static SchemaObject buildEventSchema(CompiledModel compiledModel, CompiledEvent event) {
        List<CompiledEventField> payloadFields = event.getPayloadFields();
        String conceptName = event.getConceptName();

        if ((payloadFields == null || payloadFields.isEmpty()) && conceptName != null && !conceptName.isBlank()) {
            Optional<CompiledConcept> conceptOpt = compiledModel.findConcept(conceptName);
            if (conceptOpt.isPresent()) {
                return schemaFromConcept(conceptOpt.get());
            }
        }

        return schemaFromEventFields(payloadFields);
    }

    private static SchemaObject schemaFromConcept(CompiledConcept entity) {
        Map<String, SchemaObject> properties = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();
        for (CompiledField field : entity.getFields()) {
            if (field == null || field.getName() == null) {
                continue;
            }
            properties.put(field.getName(), schemaFromType(field.getDslType()));
            if (field.isId() || field.isRequired()) {
                required.add(field.getName());
            }
        }
        return new SchemaObject(
                "object",
                properties,
                required,
                "Event payload derived from concept " + entity.getName(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private static SchemaObject schemaFromEventFields(List<CompiledEventField> fields) {
        Map<String, SchemaObject> properties = new LinkedHashMap<>();
        List<String> required = new java.util.ArrayList<>();
        if (fields != null) {
            for (CompiledEventField field : fields) {
                if (field == null || field.getName() == null) {
                    continue;
                }
                properties.put(field.getName(), schemaFromType(field.getType()));
                required.add(field.getName());
            }
        }
        return new SchemaObject(
                "object",
                properties,
                required,
                "Event payload schema",
                null,
                null,
                null,
                null,
                null
        );
    }

    private static SchemaObject schemaFromType(String dslType) {
        String t = normalize(dslType);
        if (t.isBlank()) {
            return new SchemaObject("object", Map.of(), List.of(), null, null, null, null, null, null);
        }
        switch (t) {
            case "string" -> {
                return new SchemaObject("string", Map.of(), List.of(), null, null, null, null, null, null);
            }
            case "boolean", "bool" -> {
                return new SchemaObject("boolean", Map.of(), List.of(), null, null, null, null, null, null);
            }
            case "int", "integer", "long", "double", "decimal", "number" -> {
                return new SchemaObject("number", Map.of(), List.of(), null, null, null, null, null, null);
            }
            default -> {
            // Treat unknown DSL scalar types (like 'uuid') as strings
            return new SchemaObject("string", Map.of(), List.of(), null, null, null, null, null, null);
        }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
