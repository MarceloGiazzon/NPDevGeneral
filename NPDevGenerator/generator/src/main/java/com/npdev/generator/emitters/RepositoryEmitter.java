package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class RepositoryEmitter extends AbstractEmitter {

    public RepositoryEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        for (CompiledConcept e : model.getConcepts()) {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("packageName", "com.npdev.generated.repositories");
            ctx.put("entityPackage", "com.npdev.generated.entities");
            ctx.put("entityName", e.getClassName());

            List<Map<String, Object>> uniqueFields = new ArrayList<>();
            HashSet<String> added = new HashSet<>();

            for (CompiledField f : e.getFields()) {
                boolean isUnique = false;
                try { isUnique = f.isUnique(); } catch (Exception ignored) {}
                if (!isUnique) continue;

                if (added.contains(f.getName())) continue;
                added.add(f.getName());

                String javaType = f.getJavaType();
                boolean isString = javaType != null && javaType.trim().equals("String");

                Map<String, Object> uf = new HashMap<>();
                uf.put("name", f.getName());
                uf.put("capName", cap(f.getName()));
                uf.put("javaType", javaType);
                uf.put("isString", isString);

                uniqueFields.add(uf);
            }

            ctx.put("uniqueFields", uniqueFields);

            writer.writeRelative(
                    "src/main/java/com/npdev/generated/repositories/" + e.getClassName() + "Repository.java",
                    templates.render("repository.mustache", ctx)
            );
        }
    }

    private String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
