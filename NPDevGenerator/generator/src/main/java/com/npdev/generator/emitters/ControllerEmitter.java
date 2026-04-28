package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.HashMap;
import java.util.Map;

public final class ControllerEmitter extends AbstractEmitter {

    public ControllerEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        for (CompiledConcept entity : model.getConcepts()) {

            Map<String, Object> ctx = new HashMap<>();
            ctx.put("packageName", "com.npdev.generated.controllers");
            ctx.put("entityName", entity.getClassName());
            ctx.put("servicePackage", "com.npdev.generated.services");
            ctx.put("entityPackage", "com.npdev.generated.entities");
            ctx.put("dtoPackage", "com.npdev.generated.dtos");

            String route = entity.getTableName();
            if (route == null || route.trim().isEmpty()) {
                route = entity.getName().toLowerCase() + "s";
            }
            ctx.put("route", route);

            // Base logic (no annotations)
            writer.writeRelative(
                    "src/main/java/com/npdev/generated/controllers/" + entity.getClassName() + "ControllerBase.java",
                    templates.render("controller-base.mustache", ctx)
            );

            // Concrete @RestController
            writer.writeRelative(
                    "src/main/java/com/npdev/generated/controllers/" + entity.getClassName() + "Controller.java",
                    templates.render("controller-custom.mustache", ctx)
            );
        }
    }
}
