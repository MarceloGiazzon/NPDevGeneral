package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EntityEmitter extends AbstractEmitter {

    public EntityEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        for (CompiledConcept entity : model.getConcepts()) {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("packageName", "com.npdev.generated.entities");
            ctx.put("entityName", entity.getClassName());
            ctx.put("tableName", entity.getTableName());

            List<Map<String, Object>> fields = new ArrayList<>();
            boolean hasJsonFields = false;
            CompiledField idField = idField(entity);
            ctx.put("idFieldName", idField.getName());
            ctx.put("idFieldCapName", cap(idField.getName()));
            ctx.put("idFieldIsNamedId", "id".equals(idField.getName()));
            ctx.put("idJavaType", idField.getJavaType());

            for (CompiledField f : entity.getFields()) {
                Map<String, Object> fm = new HashMap<>();
                fm.put("name", f.getName());
                fm.put("capName", cap(f.getName()));
                fm.put("columnName", toSnake(f.getName()));
                fm.put("javaType", f.getJavaType());
                fm.put("id", f.isId());
                boolean jsonField = isJsonField(f.getDslType());
                fm.put("jsonField", jsonField);
                hasJsonFields = hasJsonFields || jsonField;

                boolean required = false;
                try { required = f.isRequired(); } catch (Exception ignored) {}
                fm.put("required", required);

                fields.add(fm);
            }

            ctx.put("fields", fields);
            ctx.put("hasJsonFields", hasJsonFields);

            writer.writeRelative(
                    "src/main/java/com/npdev/generated/entities/" + entity.getClassName() + ".java",
                    templates.render("entity.mustache", ctx)
            );
        }
    }

    private static CompiledField idField(CompiledConcept entity) {
        CompiledField found = null;
        for (CompiledField field : entity.getFields()) {
            if (field == null || !field.isId()) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("Concept " + entity.getName() + " must have exactly one id field.");
            }
            found = field;
        }
        if (found == null) {
            throw new IllegalStateException("Concept " + entity.getName() + " must have exactly one id field.");
        }
        return found;
    }

    private String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String toSnake(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0) out.append('_');
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private static boolean isJsonField(String dslType) {
        if (dslType == null || dslType.isBlank()) {
            return false;
        }
        String normalized = dslType.trim().toLowerCase();
        return "object".equals(normalized) || "array".equals(normalized);
    }
}
