package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledEvent;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ServiceEmitter extends AbstractEmitter {

    public ServiceEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        for (CompiledConcept entity : model.getConcepts()) {

            List<Map<String, Object>> fields = new ArrayList<>();
            List<Map<String, Object>> uniqueFields = new ArrayList<>();
            List<Map<String, Object>> expressionInvariants = new ArrayList<>();
            List<Map<String, Object>> allowedMutationTopics = allowedMutationTopics(model, entity);
            String persistenceAdapter = resolveBindingAdapter(model, "persistence", "repository");
            String eventBusAdapter = resolveBindingAdapter(model, "eventBus", "inproc");

            if (!"repository".equalsIgnoreCase(persistenceAdapter)) {
                throw new IllegalStateException(
                        "Entity " + entity.getClassName() + " uses unsupported persistence adapter binding: "
                                + persistenceAdapter
                );
            }
            if (!"inproc".equalsIgnoreCase(eventBusAdapter)) {
                throw new IllegalStateException(
                        "Entity " + entity.getClassName() + " uses unsupported eventBus adapter binding: "
                                + eventBusAdapter
                );
            }

            for (CompiledField f : entity.getFields()) {
                boolean isId = f.getName() != null && f.getName().equalsIgnoreCase("id");
                if (isId) continue;

                String javaType = f.getJavaType();
                String boxedJavaType = boxedType(javaType);
                boolean isString = javaType != null && javaType.trim().equals("String");
                boolean required = false;
                try { required = f.isRequired(); } catch (Exception ignored) {}

                Map<String, Object> fm = new HashMap<>();
                fm.put("name", f.getName());
                fm.put("capName", cap(f.getName()));
                fm.put("javaType", javaType);
                fm.put("boxedJavaType", boxedJavaType);
                fm.put("isString", isString);
                fm.put("required", required);

                fields.add(fm);

                boolean unique = false;
                try { unique = f.isUnique(); } catch (Exception ignored) {}
                if (unique) {
                    // IMPORTANT: uniqueFields must carry isString/capName/name for the template logic
                    uniqueFields.add(fm);
                }
            }

            for (String expression : entity.getExpressionInvariants()) {
                Map<String, Object> expr = new HashMap<>();
                expr.put("javaString", escapeForJavaString(expression));
                expressionInvariants.add(expr);
            }

            Map<String, Object> ctx = new HashMap<>();
            ctx.put("packageName", "com.npdev.generated.services");
            ctx.put("conceptName", entity.getName());
            ctx.put("entityName", entity.getClassName());
            ctx.put("entityPackage", "com.npdev.generated.entities");
            ctx.put("repoPackage", "com.npdev.generated.repositories");
            ctx.put("dtoPackage", "com.npdev.generated.dtos");
            ctx.put("fields", fields);
            ctx.put("uniqueFields", uniqueFields);
            ctx.put("expressionInvariants", expressionInvariants);
            ctx.put("allowedMutationTopics", allowedMutationTopics);
            ctx.put("emitCreatedEvent", hasMutationTopic(allowedMutationTopics, entity.getClassName() + ".created"));
            ctx.put("emitUpdatedEvent", hasMutationTopic(allowedMutationTopics, entity.getClassName() + ".updated"));
            ctx.put("emitDeletedEvent", hasMutationTopic(allowedMutationTopics, entity.getClassName() + ".deleted"));
            ctx.put("persistenceAdapter", persistenceAdapter);
            ctx.put("eventBusAdapter", eventBusAdapter);
            ctx.put("persistenceRepository", "repository".equalsIgnoreCase(persistenceAdapter));
            ctx.put("eventBusInproc", "inproc".equalsIgnoreCase(eventBusAdapter));

            System.out.println("[NPDev] Entity " + entity.getClassName() + " uniqueFields=" + uniqueFields.size());
            for (Map<String, Object> uf : uniqueFields) {
                System.out.println("  - unique: " + uf.get("name"));
            }

            writer.writeRelative(
                    "src/main/java/com/npdev/generated/services/" + entity.getClassName() + "ServiceBase.java",
                    templates.render("service-base.mustache", ctx)
            );

            writer.writeRelative(
                    "src/main/java/com/npdev/generated/services/" + entity.getClassName() + "Service.java",
                    templates.render("service-custom.mustache", ctx)
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

    private static List<Map<String, Object>> allowedMutationTopics(CompiledModel model, CompiledConcept entity) {
        String entityName = entity.getClassName();
        List<String> defaultTopics = List.of(
                entityName + ".created",
                entityName + ".updated",
                entityName + ".deleted"
        );

        List<String> effectiveTopics = defaultTopics;
        List<CompiledEvent> declaredEvents = model.getEvents();
        if (!declaredEvents.isEmpty()) {
            Set<String> declaredTopics = new HashSet<>();
            for (CompiledEvent event : declaredEvents) {
                String topic = mapEventNameToTopic(event.getName());
                if (topic != null && !topic.isBlank()) {
                    declaredTopics.add(topic);
                }
            }

            effectiveTopics = defaultTopics.stream()
                    .filter(declaredTopics::contains)
                    .toList();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (String topic : effectiveTopics) {
            Map<String, Object> m = new HashMap<>();
            m.put("value", topic);
            out.add(m);
        }
        return out;
    }

    private static boolean hasMutationTopic(List<Map<String, Object>> allowedMutationTopics, String topic) {
        for (Map<String, Object> candidate : allowedMutationTopics) {
            if (topic.equals(candidate.get("value"))) {
                return true;
            }
        }
        return false;
    }

    private static String mapEventNameToTopic(String eventName) {
        if (eventName == null || eventName.isBlank()) return null;
        String trimmed = eventName.trim();
        if (trimmed.contains(".")) return trimmed;

        String[] suffixes = {"Created", "Updated", "Deleted"};
        for (String suffix : suffixes) {
            if (trimmed.endsWith(suffix) && trimmed.length() > suffix.length()) {
                String entityPart = trimmed.substring(0, trimmed.length() - suffix.length());
                return entityPart + "." + suffix.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static String resolveBindingAdapter(CompiledModel model, String capability, String defaultAdapter) {
        Map<String, String> bindingByCapability = new LinkedHashMap<>();
        model.getBindings().forEach(binding -> {
            if (binding.getCapability() == null) return;
            bindingByCapability.put(binding.getCapability().trim().toLowerCase(Locale.ROOT), binding.getAdapter());
        });

        return bindingByCapability.getOrDefault(
                capability.toLowerCase(Locale.ROOT),
                defaultAdapter
        );
    }

    private static String escapeForJavaString(String raw) {
        if (raw == null) return "";
        return raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
