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

    private static final String DEFAULT_PACKAGE_NAME = "com.npdev.generated.repositories";
    private static final String DEFAULT_ENTITY_PACKAGE = "com.npdev.generated.entities";

    public void emit(CompiledModel model) {
        for (CompiledConcept e : model.getConcepts()) {
            emitOne(e, e.getClassName(), DEFAULT_PACKAGE_NAME, DEFAULT_ENTITY_PACKAGE);
        }
    }

    /** BT-2: sibling to {@link EntityEmitter#emitOne} -- see that method's doc. Emits the repository
     *  interface for one concept into an arbitrary namespace, importing the entity from an arbitrary
     *  (not necessarily the same) package, with an arbitrary class name override. */
    public void emitOne(CompiledConcept e, String entityClassNameOverride, String packageName, String entityPackage) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("packageName", packageName);
        ctx.put("entityPackage", entityPackage);
        ctx.put("entityName", entityClassNameOverride);

        List<Map<String, Object>> uniqueFields = new ArrayList<>();
        HashSet<String> added = new HashSet<>();

        for (CompiledField f : e.getFields()) {
            boolean isUnique = f.isUnique();
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
                "src/main/java/" + packageName.replace('.', '/') + "/" + entityClassNameOverride + "Repository.java",
                templates.render("repository.mustache", ctx)
        );
    }

    private String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
