package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.HashMap;
import java.util.Map;

public final class UiEmitter extends AbstractEmitter {

    public UiEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        for (CompiledConcept e : model.getConcepts()) {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("entityName", e.getClassName());

            writer.writeRelative(
                    "src/main/resources/templates/" + e.getClassName().toLowerCase() + "-list.html",
                    templates.render("ui-list.mustache", ctx)
            );

            writer.writeRelative(
                    "src/main/resources/templates/" + e.getClassName().toLowerCase() + "-form.html",
                    templates.render("ui-form.mustache", ctx)
            );
        }

        writer.writeRelative(
                "src/main/resources/templates/layout.html",
                templates.render("layout.mustache", Map.of())
        );
    }
}
