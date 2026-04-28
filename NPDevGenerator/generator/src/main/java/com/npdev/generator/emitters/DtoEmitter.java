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

public final class DtoEmitter extends AbstractEmitter {

    public DtoEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        for (CompiledConcept entity : model.getConcepts()) {
            String dtoPackage = "com.npdev.generated.dtos";
            List<Map<String, Object>> createFields = new ArrayList<>();
            List<Map<String, Object>> updateFields = new ArrayList<>();
            List<Map<String, Object>> responseFields = new ArrayList<>();

            for (CompiledField f : entity.getFields()) {
                String javaType = f.getJavaType();
                String boxedJavaType = boxedType(javaType);

                Map<String, Object> fm = new HashMap<>();
                fm.put("name", f.getName());
                fm.put("capName", cap(f.getName()));
                fm.put("javaType", javaType);
                fm.put("boxedJavaType", boxedJavaType);
                boolean isId = "id".equalsIgnoreCase(f.getName());
                fm.put("id", isId);
                boolean required = false;
                try { required = f.isRequired(); } catch (Exception ignored) {}
                fm.put("required", required);
                responseFields.add(fm);
                // Allow caller-supplied IDs on create (optional) while keeping update IDs path-driven.
                createFields.add(new HashMap<>(fm));
                if (!isId) {
                    updateFields.add(new HashMap<>(fm));
                }
            }

            Map<String, Object> createCtx = new HashMap<>();
            createCtx.put("packageName", dtoPackage);
            createCtx.put("entityName", entity.getClassName());
            createCtx.put("fields", createFields);
            writer.writeRelative(
                    "src/main/java/com/npdev/generated/dtos/" + entity.getClassName() + "CreateRequest.java",
                    templates.render("dto-create.mustache", createCtx)
            );

            Map<String, Object> updateCtx = new HashMap<>();
            updateCtx.put("packageName", dtoPackage);
            updateCtx.put("entityName", entity.getClassName());
            updateCtx.put("fields", updateFields);
            writer.writeRelative(
                    "src/main/java/com/npdev/generated/dtos/" + entity.getClassName() + "UpdateRequest.java",
                    templates.render("dto-update.mustache", updateCtx)
            );

            Map<String, Object> respCtx = new HashMap<>();
            respCtx.put("packageName", dtoPackage);
            respCtx.put("entityName", entity.getClassName());
            respCtx.put("fields", responseFields);
            writer.writeRelative(
                    "src/main/java/com/npdev/generated/dtos/" + entity.getClassName() + "Response.java",
                    templates.render("dto-response.mustache", respCtx)
            );
        }
    }

    private String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String boxedType(String javaType) {
        if (javaType == null) return "Object";
        return switch (javaType.trim()) {
            case "int" -> "Integer";
            case "long" -> "Long";
            case "boolean" -> "Boolean";
            default -> javaType.trim();
        };
    }
}
