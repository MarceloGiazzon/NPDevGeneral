package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.generator.bonds.BondModelSupport;
import com.npdev.generator.bonds.BondModelSupport.Cardinality;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ControllerEmitter extends AbstractEmitter {

    public ControllerEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        Map<String, CompiledConcept> conceptsByName = BondModelSupport.conceptsByName(model);
        for (CompiledConcept entity : model.getConcepts()) {

            Map<String, Object> ctx = new HashMap<>();
            ctx.put("packageName", "com.npdev.generated.controllers");
            ctx.put("entityName", entity.getClassName());
            ctx.put("servicePackage", "com.npdev.generated.services");
            ctx.put("entityPackage", "com.npdev.generated.entities");
            ctx.put("dtoPackage", "com.npdev.generated.dtos");
            ctx.put("referenceFinders", referenceFinders(entity, conceptsByName));
            ctx.put("manyToManyBonds", manyToManyBonds(entity, conceptsByName));

            ctx.put("route", SqlIdentifierSupport.tableName(entity));

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

    private List<Map<String, Object>> referenceFinders(
            CompiledConcept entity,
            Map<String, CompiledConcept> conceptsByName
    ) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompiledField field : entity.getFields()) {
            BondModelSupport.resolveBond(entity, field, conceptsByName)
                    .filter(bond -> bond.cardinality() != Cardinality.MANY_TO_MANY)
                    .ifPresent(bond -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("name", field.getName());
                        item.put("capName", cap(field.getName()));
                        out.add(item);
                    });
        }
        return out;
    }

    private List<Map<String, Object>> manyToManyBonds(
            CompiledConcept entity,
            Map<String, CompiledConcept> conceptsByName
    ) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompiledField field : entity.getFields()) {
            BondModelSupport.resolveBond(entity, field, conceptsByName)
                    .filter(bond -> bond.cardinality() == Cardinality.MANY_TO_MANY)
                    .ifPresent(bond -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("name", field.getName());
                        item.put("capName", cap(field.getName()));
                        out.add(item);
                    });
        }
        return out;
    }

    private static String cap(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
