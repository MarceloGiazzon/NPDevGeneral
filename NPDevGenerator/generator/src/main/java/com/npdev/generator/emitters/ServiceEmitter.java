package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledEvent;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.generator.bonds.BondModelSupport;
import com.npdev.generator.bonds.BondModelSupport.Bond;
import com.npdev.generator.bonds.BondModelSupport.Cardinality;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ServiceEmitter extends AbstractEmitter {

    public ServiceEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        emit(model, true);
    }

    public void emit(CompiledModel model, boolean kernelControlled) {
        emit(model, kernelControlled, null);
    }

    public void emit(CompiledModel model, boolean kernelControlled, SettingResolver settingResolver) {
        Map<String, CompiledConcept> conceptsByName = BondModelSupport.conceptsByName(model);
        for (CompiledConcept entity : model.getConcepts()) {

            List<Map<String, Object>> fields = new ArrayList<>();
            List<Map<String, Object>> uniqueFields = new ArrayList<>();
            List<Map<String, Object>> fileFields = new ArrayList<>();
            List<Map<String, Object>> referenceFinders = new ArrayList<>();
            List<Map<String, Object>> manyToManyBonds = new ArrayList<>();
            List<Map<String, Object>> expressionInvariants = new ArrayList<>();
            List<Map<String, Object>> allowedMutationTopics = allowedMutationTopics(model, entity);
            String persistenceAdapter = resolveBindingAdapter(model, "persistence", "repository");
            String eventBusAdapter = resolveBindingAdapter(model, "eventBus", "inproc");
            CompiledField idField = idField(entity);

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
                Optional<Bond> bond = BondModelSupport.resolveBond(entity, f, conceptsByName);
                if (bond.map(value -> value.cardinality() == Cardinality.MANY_TO_MANY).orElse(false)) {
                    Map<String, Object> bm = new HashMap<>();
                    bm.put("name", f.getName());
                    bm.put("capName", cap(f.getName()));
                    bm.put("targetJavaType", bond.map(Bond::effectiveJavaType).orElse("Object"));
                    manyToManyBonds.add(bm);
                    continue;
                }
                bond.ifPresent(value -> {
                    Map<String, Object> rf = new HashMap<>();
                    rf.put("name", f.getName());
                    rf.put("capName", cap(f.getName()));
                    referenceFinders.add(rf);
                });
                boolean isId = f.isId();
                if (isId) continue;

                String javaType = bond.map(Bond::effectiveJavaType).orElse(f.getJavaType());
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

                // HARDEN-GC-P1/P2: a file-typed field's stored FileHandle(s) must be reclaimed
                // through FileStoreContract when the owning record is deleted or the field is
                // replaced -- the generated delete()/update() bodies below need this concept's
                // file-field list at generation time (there is no cheap way to discover it from
                // the entity's runtime type alone).
                if ("file".equalsIgnoreCase(f.getDslType())) {
                    Map<String, Object> ffm = new HashMap<>();
                    ffm.put("name", f.getName());
                    ffm.put("capName", cap(f.getName()));
                    fileFields.add(ffm);
                }

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
            ctx.put("idFieldName", idField.getName());
            ctx.put("idFieldCapName", cap(idField.getName()));
            ctx.put("entityPackage", "com.npdev.generated.entities");
            ctx.put("repoPackage", "com.npdev.generated.repositories");
            ctx.put("dtoPackage", "com.npdev.generated.dtos");
            ctx.put("fields", fields);
            ctx.put("uniqueFields", uniqueFields);
            ctx.put("fileFields", fileFields);
            ctx.put("hasFileFields", !fileFields.isEmpty());
            ctx.put("referenceFinders", referenceFinders);
            ctx.put("manyToManyBonds", manyToManyBonds);
            // REG-16-resid Round 3 (R3-F2): the bond-authorization helpers are emitted ONCE per
            // service, not once per bond, so they need a boolean the per-bond list cannot provide.
            ctx.put("hasManyToManyBonds", !manyToManyBonds.isEmpty());
            ctx.put("expressionInvariants", expressionInvariants);
            ctx.put("allowedMutationTopics", allowedMutationTopics);
            ctx.put("emitCreatedEvent", hasMutationTopic(allowedMutationTopics, entity.getClassName() + ".created"));
            ctx.put("emitUpdatedEvent", hasMutationTopic(allowedMutationTopics, entity.getClassName() + ".updated"));
            ctx.put("emitDeletedEvent", hasMutationTopic(allowedMutationTopics, entity.getClassName() + ".deleted"));
            ctx.put("persistenceAdapter", persistenceAdapter);
            ctx.put("eventBusAdapter", eventBusAdapter);
            ctx.put("persistenceRepository", "repository".equalsIgnoreCase(persistenceAdapter));
            ctx.put("eventBusInproc", "inproc".equalsIgnoreCase(eventBusAdapter));
            ctx.put("kernelControlled", kernelControlled);

            // Adapters personalization cascade: resolved once at generation time (mirrors
            // kernelControlled/field.widget). Empty (default) leaves the binding-declared adapter
            // untouched for every existing sample. Two non-empty values exist: "audited" is a fixed,
            // permanent wrap decided here at generation time (every request, every tenant); "tenant"
            // defers the actual choice to a live, per-tenant runtime setting (npdev_tenant.
            // persistence_mode, toggleable via the admin API with no regenerate) -- this is the real
            // live per-request adapter switch, scoped to what tenant-level runtime data can honestly
            // drive without a larger capability-dispatcher bridge.
            String persistenceAdapterOverride = settingResolver == null
                    ? ""
                    : settingResolver.value(NpdevSettings.PERSISTENCE_ADAPTER, SettingTarget.forConcept(entity.getModule(), entity.getName()));
            persistenceAdapterOverride = persistenceAdapterOverride == null ? "" : persistenceAdapterOverride.trim();
            // Fail fast on an unsupported value instead of letting the template's equality checks
            // silently fall through to the unwrapped store -- a typo'd or stale override (e.g.
            // "audit", "Audited ") would otherwise generate without complaint and just quietly not
            // apply. Mirrors the binding-adapter validation immediately above: an unrecognised value
            // is a model authoring error, not a thing to ignore.
            if (!persistenceAdapterOverride.isEmpty()
                    && !"audited".equalsIgnoreCase(persistenceAdapterOverride)
                    && !"tenant".equalsIgnoreCase(persistenceAdapterOverride)) {
                throw new IllegalStateException(
                        "Entity " + entity.getClassName() + " has unsupported persistence.adapter override: \""
                                + persistenceAdapterOverride + "\" (supported: \"\" | \"audited\" | \"tenant\")"
                );
            }
            ctx.put("hasPersistenceAdapterOverride", "audited".equalsIgnoreCase(persistenceAdapterOverride));
            ctx.put("hasTenantControlledPersistenceAdapter", "tenant".equalsIgnoreCase(persistenceAdapterOverride));
            ctx.put("persistenceAdapterOverride", persistenceAdapterOverride);

            // Coda: the single defined author-code hook point, gated per concept by coda.allowed
            // (resolved at generation time, mirrors every other concept-scope setting). When false
            // (the platform default for every existing sample), the generated service never even
            // references CodaHook beyond the always-present, always-empty-by-default constructor
            // parameter -- zero behavior change.
            boolean codaAllowed = settingResolver != null
                    && settingResolver.value(NpdevSettings.CODA_ALLOWED, SettingTarget.forConcept(entity.getModule(), entity.getName()));
            ctx.put("codaAllowed", codaAllowed);

            // Custom events direct from CRUD: a concept-nested event declaring mode:create/update/
            // delete is published from generated CRUD's matching mutation step directly -- no Flow
            // required to reach it (previously the ONLY way to trigger a custom, non-built-in event
            // was an explicit Flow's emitEvent step).
            List<String> extraCreateEvents = customTriggeredEventNames(model, entity, "create");
            List<String> extraUpdateEvents = customTriggeredEventNames(model, entity, "update");
            List<String> extraDeleteEvents = customTriggeredEventNames(model, entity, "delete");
            ctx.put("extraCreateEvents", toEventViews(extraCreateEvents));
            ctx.put("extraUpdateEvents", toEventViews(extraUpdateEvents));
            ctx.put("extraDeleteEvents", toEventViews(extraDeleteEvents));
            ctx.put("hasExtraCreateEvents", !extraCreateEvents.isEmpty());
            ctx.put("hasExtraUpdateEvents", !extraUpdateEvents.isEmpty());
            ctx.put("hasExtraDeleteEvents", !extraDeleteEvents.isEmpty());

            // Flow-CRUD wrapper integration: looked up once at generation time (mirrors
            // kernelControlled exactly, not a runtime FlowDefinitionProvider lookup per call).
            // Permission/tenant/idempotency/optimistic-concurrency/audit stay in the template
            // exactly as today; only the core mutation sub-step optionally delegates to this
            // Flow's own steps. See service-base.mustache's create()/update()/delete() bodies.
            model.findFlow(entity.getName(), "create").ifPresent(flow -> {
                ctx.put("hasCreateFlow", true);
                ctx.put("createFlowName", flow.getName());
                // REG-120: author-time precedence signal -- a concept combining a create-mode Flow
                // with generic CRUD create exposure only ever persists through the Flow's own write
                // (createFromSource fetches, rather than re-saves, once the Flow has run); the CRUD
                // create endpoint's permission/tenant/idempotency/audit handling still applies
                // unchanged, only the actual row write is delegated. Printed (not a generation
                // failure) because both a Flow-owned create AND CRUD create exposure are individually
                // legitimate and their combination is intentionally supported, just with one
                // authoritative writer.
                System.out.println("[NPDev] Entity " + entity.getClassName() + ": create is delegated to Flow \""
                        + flow.getName() + "\" -- that Flow's write is authoritative, the generated CRUD "
                        + "create endpoint will not write a second time (REG-120)");
            });
            model.findFlow(entity.getName(), "update").ifPresent(flow -> {
                ctx.put("hasUpdateFlow", true);
                ctx.put("updateFlowName", flow.getName());
            });
            model.findFlow(entity.getName(), "delete").ifPresent(flow -> {
                ctx.put("hasDeleteFlow", true);
                ctx.put("deleteFlowName", flow.getName());
            });
            ctx.put("hasAnyFlow", ctx.containsKey("hasCreateFlow") || ctx.containsKey("hasUpdateFlow") || ctx.containsKey("hasDeleteFlow"));

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

    /** Concept-nested events declaring {@code mode} matching the given CRUD mode, for direct publish. */
    private static List<String> customTriggeredEventNames(CompiledModel model, CompiledConcept entity, String mode) {
        List<String> names = new ArrayList<>();
        for (CompiledEvent event : model.getEvents()) {
            if (event == null || event.getName() == null || event.getName().isBlank()) {
                continue;
            }
            if (!entity.getName().equals(event.getConceptName())) {
                continue;
            }
            if (!mode.equalsIgnoreCase(event.getTriggerMode())) {
                continue;
            }
            names.add(event.getName().trim());
        }
        return names;
    }

    private static List<Map<String, Object>> toEventViews(List<String> eventNames) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : eventNames) {
            Map<String, Object> view = new HashMap<>();
            view.put("name", name);
            view.put("javaString", escapeForJavaString(name));
            out.add(view);
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
