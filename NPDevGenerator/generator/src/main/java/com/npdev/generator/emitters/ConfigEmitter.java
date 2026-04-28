package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.HashMap;
import java.util.Map;

public final class ConfigEmitter extends AbstractEmitter {

    public ConfigEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("packageName", "com.npdev.generated.config");
        ctx.put("entityPackage", "com.npdev.generated.entities");
        ctx.put("repoPackage", "com.npdev.generated.repositories");

        writer.writeRelative(
                "src/main/java/com/npdev/generated/config/GeneratedJpaConfig.java",
                templates.render("spring-config.mustache", ctx)
        );
    }
}
