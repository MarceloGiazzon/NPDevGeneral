package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledEnumOption;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPresentationMetadata;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.packs.BuiltinPackComposer;
import com.npdev.generator.templates.TemplateEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BusinessUiEmitter extends AbstractEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public BusinessUiEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model) {
        emit(model, NpdevSettings.SECURITY_SUPER_USER_ROLE.defaultValue());
    }

    public void emit(CompiledModel model, String superUserRole) {
        emit(model, superUserRole, new SettingResolver(com.npdev.dsl.v1.settings.SettingStore.empty()));
    }

    /**
     * @param settingResolver resolves {@code field.widget} cascade overrides
     *     (selector {@code field:<Concept>.<field>} -> {@code concept:<Concept>} -> {@code app})
     *     ahead of a field's direct model.json {@code ui.widget} attribute. An empty resolver
     *     (every call resolves to the platform default, the empty string) reproduces the prior
     *     behavior exactly, so existing callers/samples are unaffected unless they actually
     *     declare an override.
     */
    public void emit(CompiledModel model, String superUserRole, SettingResolver settingResolver) {
        SettingResolver resolver = settingResolver == null
                ? new SettingResolver(com.npdev.dsl.v1.settings.SettingStore.empty())
                : settingResolver;
        List<CompiledConcept> persistedConcepts = persistedConcepts(model);
        Map<String, CompiledConcept> conceptsByName = conceptsByName(persistedConcepts);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("controllerPackage", "com.npdev.generated.controllers");
        ctx.put("servicePackage", "com.npdev.generated.services");
        ctx.put("entityPackage", "com.npdev.generated.entities");
        ctx.put("concepts", conceptTemplateModels(persistedConcepts, conceptsByName, resolver));
        ctx.put("superUserRole", superUserRole == null || superUserRole.isBlank() ? "ADMIN" : superUserRole.trim());

        writer.writeRelative(
                "src/main/java/com/npdev/generated/controllers/GeneratedConceptCrudController.java",
                templates.render("business-concept-crud-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/controllers/GeneratedBusinessUiRouteController.java",
                templates.render("business-ui-route-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/controllers/GeneratedMeController.java",
                templates.render("business-ui-me-controller.mustache", ctx)
        );
        // Phase 7: read-only admin surfaces for the pack catalog ("store") and the per-concept
        // truth-level view ("box view"), both gated to superUserRole.
        writer.writeRelative(
                "src/main/java/com/npdev/generated/controllers/GeneratedPackCatalogController.java",
                templates.render("business-ui-pack-catalog-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/controllers/GeneratedBoxViewController.java",
                templates.render("business-ui-box-view-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/resources/static/npdev-business-ui/index.html",
                templates.render("business-ui-index.mustache", Map.of())
        );
        writer.writeRelative(
                "src/main/resources/static/npdev-business-ui/app.js",
                templates.render("business-ui-app.mustache", Map.of())
        );
        writer.writeRelative(
                "src/main/resources/static/npdev-business-ui/style.css",
                templates.render("business-ui-style.mustache", Map.of())
        );
        writer.writeRelative(
                "src/main/resources/static/npdev-business-ui/generated-ui-manifest.json",
                manifestJson(model, persistedConcepts, superUserRole, resolver)
        );
    }

    private static List<CompiledConcept> persistedConcepts(CompiledModel model) {
        if (model == null) {
            return List.of();
        }
        List<CompiledConcept> out = new ArrayList<>();
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept == null || concept.getName() == null || concept.getName().isBlank()) {
                continue;
            }
            if (concept.getTableName() == null || concept.getTableName().isBlank()) {
                continue;
            }
            idField(concept);
            out.add(concept);
        }
        out.sort(Comparator.comparing(CompiledConcept::getName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private static Map<String, CompiledConcept> conceptsByName(List<CompiledConcept> concepts) {
        Map<String, CompiledConcept> out = new LinkedHashMap<>();
        for (CompiledConcept concept : concepts) {
            out.put(concept.getName(), concept);
        }
        return out;
    }

    private static List<Map<String, Object>> conceptTemplateModels(
            List<CompiledConcept> concepts,
            Map<String, CompiledConcept> conceptsByName,
            SettingResolver settingResolver
    ) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompiledConcept concept : concepts) {
            CompiledField idField = idField(concept);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("conceptName", javaString(concept.getName()));
            view.put("displayName", javaString(displayName(concept)));
            view.put("tableName", javaString(concept.getTableName()));
            view.put("idField", javaString(idField.getName()));
            view.put("endpointBase", javaString(endpointBase(concept)));
            view.put("className", concept.getClassName());
            view.put("serviceVariable", uncap(concept.getClassName()) + "Service");
            view.put("fields", fieldTemplateModels(concept, conceptsByName, settingResolver));
            out.add(view);
        }
        return out;
    }

    private static List<Map<String, Object>> fieldTemplateModels(
            CompiledConcept concept,
            Map<String, CompiledConcept> conceptsByName,
            SettingResolver settingResolver
    ) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", javaString(field.getName()));
            view.put("label", javaString(fieldLabel(field)));
            view.put("columnName", javaString(toSnake(field.getName())));
            view.put("type", javaString(manifestType(field)));
            view.put("required", field.isRequired());
            view.put("id", field.isId());
            view.put("readOnly", field.isId());
            view.put("sortable", isSortable(field));
            view.put("filterable", isFilterable(field));
            view.put("widget", javaString(widget(field, conceptsByName, concept, settingResolver)));
            view.put("hasEnumValues", field.getEnumValues() != null && !field.getEnumValues().isEmpty());
            view.put("enumValues", enumValuesJava(field));
            view.put("defaultEnumValue", javaString(defaultEnumValue(field)));
            out.add(view);
        }
        return out;
    }

    private static String manifestJson(
            CompiledModel model,
            List<CompiledConcept> concepts,
            String superUserRole,
            SettingResolver settingResolver
    ) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "npdev-generated-ui-manifest.v1");
        root.put("appName", model == null || model.getNamespace() == null ? "NPDev Generated App" : model.getNamespace());
        root.put("superUserRole", superUserRole == null ? "" : superUserRole.trim());
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("mode", "apiKey");
        auth.put("headerName", "X-Api-Key");
        auth.put("devKeyHint", "api-dev");
        root.put("auth", auth);

        List<Map<String, Object>> conceptNodes = new ArrayList<>();
        for (CompiledConcept concept : concepts) {
            CompiledField idField = idField(concept);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("conceptName", concept.getName());
            node.put("displayName", displayName(concept));
            node.put("admin", isAdminConcept(concept));
            node.put("route", "/" + SqlIdentifierSupport.tableName(concept));
            node.put("tableName", SqlIdentifierSupport.tableName(concept));
            node.put("idField", idField.getName());
            node.put("endpointBase", endpointBase(concept));
            node.put("formColumns", formColumns(concept));
            node.put("displayMode", displayMode(concept));
            node.put("frameMode", resolveFrameMode(concept, settingResolver));
            node.put("fields", manifestFields(concept, conceptsByName(concepts), settingResolver));
            node.put("list", manifestList(concept, idField));
            Map<String, Object> actions = new LinkedHashMap<>();
            actions.put("list", true);
            actions.put("create", true);
            actions.put("display", true);
            actions.put("update", true);
            actions.put("delete", true);
            node.put("actions", actions);
            conceptNodes.add(node);
        }
        root.put("concepts", conceptNodes);
        root.put("panels", panelNodes(model));
        try {
            return OBJECT_MAPPER.writeValueAsString(root) + System.lineSeparator();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize generated business UI manifest", exception);
        }
    }

    private static List<Map<String, Object>> manifestFields(
            CompiledConcept concept,
            Map<String, CompiledConcept> conceptsByName,
            SettingResolver settingResolver
    ) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", field.getName());
            node.put("label", fieldLabel(field));
            node.put("columnName", toSnake(field.getName()));
            node.put("type", manifestType(field));
            node.put("required", field.isRequired());
            node.put("id", field.isId());
            node.put("readOnly", field.isId());
            node.put("sortable", isSortable(field));
            node.put("filterable", isFilterable(field));
            node.put("showInDefaultWebUi", isShowInUi(field));
            node.put("tab", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getTab), ""));
            node.put("column", fieldUiInt(field, CompiledPresentationMetadata::getColumn));
            node.put("columnSpan", fieldUiInt(field, CompiledPresentationMetadata::getColumnSpan, 1));
            node.put("visibleWhen", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getVisibleWhen), ""));
            node.put("enabledWhen", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getEnabledWhen), ""));
            node.put("readonlyWhen", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getReadonlyWhen), ""));
            node.put("requiredWhen", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getRequiredWhen), ""));
            if ("array".equals(manifestType(field))) {
                CompiledSchema schema = field.getSchema();
                if (schema != null && schema.getItems() != null && !schema.getItems().getProperties().isEmpty()) {
                    node.put("itemsSchema", buildItemsSchemaNode(schema.getItems()));
                }
            } else if ("object".equals(manifestType(field))) {
                CompiledSchema schema = field.getSchema();
                if (schema != null && !schema.getProperties().isEmpty()) {
                    node.put("objectSchema", buildItemsSchemaNode(schema));
                }
            }
            Optional<Map<String, Object>> reference = referenceMetadata(field, conceptsByName, concept);
            node.put("widget", widget(field, conceptsByName, concept, settingResolver));
            node.put("enumValues", field.getEnumValues());
            node.put("enumOptions", enumOptionsManifest(field));
            node.put("enumName", enumName(concept, field));
            node.put("defaultEnumValue", defaultEnumValue(field));
            reference.ifPresent(value -> {
                node.put("reference", value);
                node.put("tableDisplay", referenceTableDisplayMetadata());
            });
            fields.add(node);
        }
        return fields;
    }

    private static Map<String, Object> referenceTableDisplayMetadata() {
        Map<String, Object> tableDisplay = new LinkedHashMap<>();
        tableDisplay.put("mode", "referenceLabel");
        tableDisplay.put("fallback", "rawId");
        tableDisplay.put("showRawIdTooltip", true);
        return tableDisplay;
    }

    private static Map<String, Object> manifestList(CompiledConcept concept, CompiledField idField) {
        List<String> columns = concept.getFields().stream()
                .filter(BusinessUiEmitter::isShowInUi)
                .map(CompiledField::getName)
                .toList();
        Map<String, Object> sort = new LinkedHashMap<>();
        sort.put("field", idField.getName());
        sort.put("direction", "asc");

        Map<String, Object> list = new LinkedHashMap<>();
        list.put("columns", columns);
        list.put("defaultSort", sort);
        list.put("pageSize", 20);
        list.put("maxPageSize", 100);
        list.put("filterMode", "bounded-in-memory-v1");
        return list;
    }

    private static CompiledField idField(CompiledConcept concept) {
        CompiledField found = null;
        for (CompiledField field : concept.getFields()) {
            if (field == null || !field.isId()) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("Concept " + concept.getName() + " must have exactly one id field.");
            }
            found = field;
        }
        if (found == null) {
            throw new IllegalStateException("Concept " + concept.getName() + " must have exactly one id field.");
        }
        return found;
    }

    private static String endpointBase(CompiledConcept concept) {
        return "/api/concepts/" + SqlIdentifierSupport.tableName(concept);
    }

    private static String displayName(CompiledConcept concept) {
        CompiledPresentationMetadata ui = concept.getUi();
        String label = ui == null ? null : ui.getLabel();
        if (label != null && !label.isBlank()) {
            return label.trim();
        }
        return pluralize(displayBaseName(concept.getName()));
    }

    /**
     * Turns a (possibly pack-aliased) concept name into a human display base, folding the alias into
     * a leading category word: {@code "identity::User" -> "Identity User"}, {@code "User" -> "User"}.
     */
    private static String displayBaseName(String conceptName) {
        if (conceptName == null) {
            return "";
        }
        int sep = conceptName.lastIndexOf("::");
        if (sep < 0) {
            return conceptName;
        }
        String alias = conceptName.substring(0, sep);
        String name = conceptName.substring(sep + 2);
        return (humanize(alias) + " " + name).trim();
    }

    /** True for concepts contributed by a built-in platform pack (the internal NPDev tables). */
    private static boolean isAdminConcept(CompiledConcept concept) {
        String name = concept == null ? null : concept.getName();
        if (name == null) {
            return false;
        }
        int sep = name.indexOf("::");
        if (sep < 0) {
            return false;
        }
        return BuiltinPackComposer.BUILTIN_PACK_ALIASES.contains(name.substring(0, sep));
    }

    /**
     * Declared Panel Objects surfaced as their own dedicated nav entry + section in the generated
     * business UI, closing the gap where they were metadata the generator validated but the
     * rendered UI never showed. The manifest only carries enough to build the nav entry and each
     * action's button label -- the actual field/data payload is fetched lazily from the existing,
     * already-real generic {@code PanelRuntime} endpoints
     * ({@code GET .../panels/{name}}, {@code POST .../panels/{name}/actions/{action}}) the same way
     * the Store/Box View sections already fetch their own data on demand.
     */
    private static List<Map<String, Object>> panelNodes(CompiledModel model) {
        List<Map<String, Object>> panels = new ArrayList<>();
        if (model == null) {
            return panels;
        }
        for (CompiledPanel panel : model.getPanels()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", panel.name());
            node.put("route", panel.route());
            node.put("title", panel.title() == null || panel.title().isBlank() ? panel.name() : panel.title());
            node.put("visibility", panel.visibility() == null ? "" : panel.visibility());
            node.put("enabledWhen", panel.enabledWhen() == null ? "" : panel.enabledWhen());
            List<Map<String, Object>> actions = new ArrayList<>();
            for (CompiledPanelAction action : panel.actions()) {
                Map<String, Object> actionNode = new LinkedHashMap<>();
                actionNode.put("name", action.name());
                actionNode.put("label", action.label() == null || action.label().isBlank() ? action.name() : action.label());
                actionNode.put("binding", action.binding());
                actionNode.put("visibleWhen", action.visibleWhen() == null ? "" : action.visibleWhen());
                actionNode.put("enabledWhen", action.enabledWhen() == null ? "" : action.enabledWhen());
                actions.add(actionNode);
            }
            node.put("actions", actions);
            panels.add(node);
        }
        return panels;
    }

    private static int formColumns(CompiledConcept concept) {
        CompiledPresentationMetadata ui = concept.getUi();
        Integer columns = ui == null ? null : ui.getFormColumns();
        return columns == null || columns < 1 ? 1 : columns;
    }

    private static String displayMode(CompiledConcept concept) {
        CompiledPresentationMetadata ui = concept.getUi();
        return firstNonBlank(ui == null ? null : ui.getDisplayMode(), "");
    }

    private static String fieldUiString(
            CompiledField field,
            java.util.function.Function<CompiledPresentationMetadata, String> accessor
    ) {
        CompiledPresentationMetadata ui = field.getUi();
        return ui == null ? null : accessor.apply(ui);
    }

    private static Integer fieldUiInt(
            CompiledField field,
            java.util.function.Function<CompiledPresentationMetadata, Integer> accessor
    ) {
        return fieldUiInt(field, accessor, null);
    }

    private static Integer fieldUiInt(
            CompiledField field,
            java.util.function.Function<CompiledPresentationMetadata, Integer> accessor,
            Integer fallback
    ) {
        CompiledPresentationMetadata ui = field.getUi();
        Integer value = ui == null ? null : accessor.apply(ui);
        return value == null ? fallback : value;
    }

    private static String fieldLabel(CompiledField field) {
        CompiledPresentationMetadata ui = field.getUi();
        String label = ui == null ? null : ui.getLabel();
        return label == null || label.isBlank() ? humanize(field.getName()) : label.trim();
    }

    private static String widget(
            CompiledField field,
            Map<String, CompiledConcept> conceptsByName,
            CompiledConcept concept,
            SettingResolver settingResolver
    ) {
        String conceptName = concept == null ? null : concept.getName();
        boolean isReference = referenceMetadata(field, conceptsByName, concept).isPresent();
        boolean isMultiReference = isReference
                && field.getReferenceSemantics() != null
                && field.getReferenceSemantics().isMultiple();
        // A many-to-many bond has exactly one working widget today -- there is no scalar value to
        // "lookup" or render as a "select" (the field isn't even a property on the generated
        // entity; it's a set of junction-table rows). Settled before the cascade check below so an
        // unrelated field.widget override on this field can't silently break it.
        if (isMultiReference) {
            return "multiselect";
        }
        // field.widget cascade (field:<Concept>.<field> -> concept:<Concept> -> app -> platform
        // default) takes priority over the field's own model.json ui.widget when explicitly
        // overridden -- this is the actual personalization mechanism the setting was registered
        // for. An unconfigured app resolves to the platform default (empty string) for every
        // field, so this is a no-op unless an author has actually declared an override. Checked
        // before the reference-field default so a single (N:1/1:1) reference field can opt into a
        // plain "select" dropdown instead of the picker dialog.
        if (settingResolver != null && conceptName != null && !conceptName.isBlank() && field.getName() != null) {
            String cascadeWidget = settingResolver.value(
                    NpdevSettings.FIELD_WIDGET,
                    SettingTarget.field(conceptName, field.getName())
            );
            if (cascadeWidget != null && !cascadeWidget.isBlank()) {
                String normalized = cascadeWidget.trim();
                if (isReference) {
                    // "select" (plain <select>, whole candidate set fetched upfront) and
                    // "autocomplete" (live-search suggestions, for a candidate set too large for a
                    // <select>) are the only supported alternatives for a reference field; any other
                    // override falls back to the picker rather than silently rendering a bare text
                    // input over a foreign-key value.
                    if ("select".equalsIgnoreCase(normalized)) {
                        return "select";
                    }
                    if ("autocomplete".equalsIgnoreCase(normalized)) {
                        return "autocomplete";
                    }
                    return "lookup";
                }
                return normalized;
            }
        }
        if (isReference) {
            return "lookup";
        }
        String type = manifestType(field);
        if ("enum".equals(type) && field.getEnumValues() != null && !field.getEnumValues().isEmpty()) {
            return "select";
        }
        CompiledPresentationMetadata ui = field.getUi();
        String widget = ui == null ? null : ui.getWidget();
        if (widget != null && !widget.isBlank()) {
            return widget.trim();
        }
        return switch (type) {
            case "date" -> "date";
            case "datetime" -> "datetime-local";
            case "boolean" -> "checkbox";
            default -> "text";
        };
    }

    /**
     * "full" (default: header + #sideNav + #app, today's only behavior) | "minimal" (no header/
     * sidenav, just the section's own content) | "none" (raw -- the section's own markup owns the
     * whole viewport, e.g. a login screen). Resolved via the same concept-scope cascade as every
     * other per-concept setting (concept:&lt;Name&gt; -> app -> platform default "full"), so an
     * unconfigured app's sections all render exactly as before.
     */
    private static String resolveFrameMode(CompiledConcept concept, SettingResolver settingResolver) {
        if (settingResolver == null || concept == null || concept.getName() == null || concept.getName().isBlank()) {
            return NpdevSettings.UI_FRAME_MODE.defaultValue();
        }
        String mode = settingResolver.value(NpdevSettings.UI_FRAME_MODE, SettingTarget.forConcept(concept.getModule(), concept.getName()));
        if (mode == null || mode.isBlank()) {
            return NpdevSettings.UI_FRAME_MODE.defaultValue();
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return List.of("full", "minimal", "none").contains(normalized) ? normalized : NpdevSettings.UI_FRAME_MODE.defaultValue();
    }

    private static String manifestType(CompiledField field) {
        String type = field.getDslType();
        if (type == null || type.isBlank()) {
            return "string";
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isSortable(CompiledField field) {
        return !List.of("object", "array").contains(manifestType(field));
    }

    private static boolean isFilterable(CompiledField field) {
        String type = manifestType(field);
        return "string".equals(type) || "enum".equals(type);
    }

    private static boolean isShowInUi(CompiledField field) {
        CompiledPresentationMetadata ui = field.getUi();
        if (ui != null) {
            Boolean explicit = ui.getShowInDefaultWebUi();
            if (explicit != null) {
                return explicit;
            }
        }
        // A many-to-many bond field has no scalar value on the record (it's junction-table rows,
        // not an entity property) -- a grid column would just read undefined for every row.
        // Hidden from the default grid for the same reason array/object are: showing it would
        // need a per-row async fetch the list view isn't set up to do.
        if (field.getReferenceSemantics() != null && field.getReferenceSemantics().isMultiple()) {
            return false;
        }
        // array and object fields are complex structures; hide by default
        String type = manifestType(field);
        return !"array".equals(type) && !"object".equals(type);
    }


    private static Optional<Map<String, Object>> referenceMetadata(
            CompiledField field,
            Map<String, CompiledConcept> conceptsByName,
            CompiledConcept sourceConcept
    ) {
        String targetName = referenceTarget(field, conceptsByName);
        if (targetName == null || targetName.isBlank()) {
            return Optional.empty();
        }
        CompiledConcept target = conceptsByName.get(targetName);
        if (target == null) {
            throw new IllegalStateException(
                    "Reference field " + field.getName() + " targets non-persisted or unknown concept: " + targetName
            );
        }

        CompiledField targetId = idField(target);
        String displayField = firstExistingField(
                target,
                firstNonBlank(referenceDisplayField(field), inferReferenceDisplayField(target))
        );
        if (displayField == null || displayField.isBlank()) {
            displayField = targetId.getName();
        }

        List<String> searchFields = effectiveReferenceSearchFields(field, target, displayField);
        List<String> displayFields = effectiveReferenceDisplayFields(field, target, displayField, searchFields);
        List<String> pickerColumns = effectiveReferencePickerColumns(field, target, displayFields);
        String defaultFilter = referenceDefaultFilter(field);
        String via = field.getReferenceSemantics() == null ? "" : firstNonBlank(field.getReferenceSemantics().getVia(), "");
        String onDelete = field.getReferenceSemantics() == null ? "restrict" : firstNonBlank(field.getReferenceSemantics().getOnDelete(), "restrict");
        CompiledField anchorField = via.isBlank() ? targetId : fieldByName(target, via);
        if (anchorField == null) {
            anchorField = targetId;
        }

        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("targetConcept", target.getName());
        reference.put("targetIdField", targetId.getName());
        reference.put("via", via);
        reference.put("onDelete", onDelete);
        reference.put("anchorField", anchorField.getName());
        reference.put("anchorType", anchorField.getDslType());
        reference.put("endpointBase", endpointBase(target));
        reference.put("displayField", displayField);
        reference.put("displayFields", displayFields);
        reference.put("searchFields", searchFields);
        reference.put("pickerColumns", pickerColumns);
        reference.put("displayTemplate", firstNonBlank(referenceDisplayTemplate(field), ""));
        reference.put("previewFields", effectiveReferencePreviewFields(field, target, displayFields));
        reference.put("defaultFilter", firstNonBlank(defaultFilter, ""));
        parseDefaultFilterExpression(defaultFilter, target).ifPresent(expression -> reference.put("defaultFilterExpression", expression));
        reference.put("filterMode", "bounded-lookup-v1.1");
        boolean multiple = field.getReferenceSemantics() != null && field.getReferenceSemantics().isMultiple();
        reference.put("multiple", multiple);
        if (multiple && sourceConcept != null) {
            // The many-to-many bond's own member list/add/remove/replace routes
            // (controller-custom.mustache's {{#manyToManyBonds}} block) are generated onto the
            // PER-ENTITY controller, route "/api/" + tableName (ControllerEmitter.java) -- a
            // DIFFERENT base than endpointBase()'s "/api/concepts/" + tableName, which only the
            // generic binding-map GeneratedConceptCrudController serves (regular CRUD, reference
            // lookups). Confirmed live: PUT /api/concepts/notes/{id}/tags 404s; PUT
            // /api/notes/{id}/tags is the real route. The {id} is a runtime value the generator
            // doesn't have, so this is a base for the frontend to complete itself
            // (bondEndpointBase + "/" + recordId + "/" + bondFieldName).
            reference.put("bondEndpointBase", "/api/" + SqlIdentifierSupport.tableName(sourceConcept));
            reference.put("bondFieldName", field.getName());
        }
        return Optional.of(reference);
    }

    private static String referenceTarget(CompiledField field, Map<String, CompiledConcept> conceptsByName) {
        String direct = field.getReferenceTarget();
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }

        String semanticTarget = field.getReferenceSemantics() == null
                ? null
                : firstNonBlank(field.getReferenceSemantics().getTarget(), null);
        if (semanticTarget != null && !semanticTarget.isBlank()) {
            return semanticTarget;
        }

        if (!canInferReference(field)) {
            return null;
        }

        return inferReferenceTargetFromIdField(field, conceptsByName);
    }

    private static CompiledField fieldByName(CompiledConcept concept, String fieldName) {
        if (concept == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        for (CompiledField field : concept.getFields()) {
            if (field != null && fieldName.equalsIgnoreCase(field.getName())) {
                return field;
            }
        }
        return null;
    }

    private static boolean canInferReference(CompiledField field) {
        if (field == null || field.isId() || field.getName() == null || field.getName().isBlank()) {
            return false;
        }
        String fieldName = field.getName().trim();
        if (!fieldName.endsWith("Id") || fieldName.length() <= 2) {
            return false;
        }
        String type = manifestType(field);
        return "uuid".equals(type) || "string".equals(type) || "reference".equals(type);
    }

    private static String inferReferenceTargetFromIdField(
            CompiledField field,
            Map<String, CompiledConcept> conceptsByName
    ) {
        String fieldName = field.getName().trim();
        String foundTarget = null;
        for (CompiledConcept candidate : conceptsByName.values()) {
            CompiledField candidateId = idField(candidate);
            if (!fieldName.equals(candidateId.getName())) {
                continue;
            }
            if (foundTarget != null) {
                return null;
            }
            foundTarget = candidate.getName();
        }
        return foundTarget;
    }

    private static String referenceDisplayField(CompiledField field) {
        return field.getReferenceSemantics() == null ? null : firstNonBlank(field.getReferenceSemantics().getDisplayField(), null);
    }

    private static String referenceDisplayTemplate(CompiledField field) {
        return field.getReferenceSemantics() == null ? null : firstNonBlank(field.getReferenceSemantics().getDisplayTemplate(), null);
    }

    private static String referenceDefaultFilter(CompiledField field) {
        return field.getReferenceSemantics() == null ? null : firstNonBlank(field.getReferenceSemantics().getDefaultFilter(), null);
    }

    private static List<String> effectiveReferenceSearchFields(
            CompiledField field,
            CompiledConcept target,
            String displayField
    ) {
        List<String> explicit = field.getReferenceSemantics() == null
                ? List.of()
                : field.getReferenceSemantics().getSearchFields();
        List<String> searchFields = existingFields(target, explicit);
        if (!searchFields.isEmpty()) {
            return searchFields;
        }
        List<String> inferred = target.getFields().stream()
                .filter(candidate -> isFilterable(candidate) && !candidate.isId())
                .map(CompiledField::getName)
                .limit(3)
                .toList();
        if (!inferred.isEmpty()) {
            return inferred;
        }
        return List.of(displayField);
    }

    private static List<String> effectiveReferenceDisplayFields(
            CompiledField field,
            CompiledConcept target,
            String displayField,
            List<String> searchFields
    ) {
        List<String> previewFields = field.getReferenceSemantics() == null
                ? List.of()
                : field.getReferenceSemantics().getPreviewFields();
        List<String> explicitPreview = existingFields(target, previewFields);
        if (!explicitPreview.isEmpty()) {
            return explicitPreview;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (displayField != null && !displayField.isBlank()) {
            out.add(displayField);
        }
        out.addAll(searchFields);
        if (out.isEmpty()) {
            out.add(idField(target).getName());
        }
        return List.copyOf(out);
    }

    private static List<String> effectiveReferencePickerColumns(
            CompiledField field,
            CompiledConcept target,
            List<String> displayFields
    ) {
        List<String> explicit = field.getReferenceSemantics() == null
                ? List.of()
                : field.getReferenceSemantics().getPickerColumns();
        List<String> pickerColumns = existingFields(target, explicit);
        return pickerColumns.isEmpty() ? displayFields : pickerColumns;
    }

    private static List<String> effectiveReferencePreviewFields(
            CompiledField field,
            CompiledConcept target,
            List<String> displayFields
    ) {
        List<String> explicit = field.getReferenceSemantics() == null
                ? List.of()
                : field.getReferenceSemantics().getPreviewFields();
        List<String> previewFields = existingFields(target, explicit);
        return previewFields.isEmpty() ? displayFields : previewFields;
    }

    private static Optional<Map<String, Object>> parseDefaultFilterExpression(String expression, CompiledConcept target) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        String trimmed = expression.trim();
        String field;
        String operator;
        String value;
        String[] colonParts = trimmed.split(":", 3);
        if (colonParts.length == 3) {
            field = colonParts[0].trim();
            operator = colonParts[1].trim().toLowerCase(Locale.ROOT);
            value = colonParts[2].trim();
        } else {
            int equalsIndex = trimmed.indexOf('=');
            if (equalsIndex < 1) {
                return Optional.empty();
            }
            field = trimmed.substring(0, equalsIndex).trim();
            operator = "eq";
            value = trimmed.substring(equalsIndex + 1).trim();
        }
        if (!List.of("eq", "ne", "contains").contains(operator)) {
            return Optional.empty();
        }
        if (firstExistingField(target, field) == null) {
            return Optional.empty();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", field);
        out.put("operator", operator);
        out.put("value", value);
        return Optional.of(out);
    }

    private static List<String> existingFields(CompiledConcept concept, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        Set<String> existing = concept.getFields().stream()
                .map(CompiledField::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> out = new ArrayList<>();
        for (String name : names) {
            if (name != null && existing.contains(name)) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    private static String firstExistingField(CompiledConcept concept, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (CompiledField field : concept.getFields()) {
            if (field.getName().equals(name)) {
                return field.getName();
            }
        }
        return null;
    }

    private static String inferReferenceDisplayField(CompiledConcept target) {
        List<String> preferred = List.of("displayName", "fullName", "name", "title", "label", "description", "email", "code");
        for (String candidate : preferred) {
            String found = firstExistingField(target, candidate);
            if (found != null) {
                return found;
            }
        }
        for (CompiledField field : target.getFields()) {
            if (isFilterable(field) && !field.isId()) {
                return field.getName();
            }
        }
        return idField(target).getName();
    }

    /** Purely a guard against a pathological/self-referential schema hanging generation -- not a
     *  semantic limit on how deep an author can legitimately nest object/array fields. */
    private static final int MAX_SCHEMA_NESTING_DEPTH = 5;

    private static Map<String, Object> buildItemsSchemaNode(CompiledSchema items) {
        return buildItemsSchemaNode(items, 0);
    }

    /**
     * Recursively mirrors a nested object/array field's full shape into the manifest, the same
     * way a top-level field does (objectSchema/itemsSchema) -- so a property that is itself an
     * object or an array gets its OWN nested objectSchema/itemsSchema instead of a flat
     * {@code {"type": "object"}} the renderer can't actually edit past one level.
     */
    private static Map<String, Object> buildItemsSchemaNode(CompiledSchema items, int depth) {
        Map<String, Object> node = new LinkedHashMap<>();
        String type = items.getType() != null ? items.getType() : "object";
        node.put("type", type);
        Map<String, Object> propsNode = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledSchema> entry : items.getProperties().entrySet()) {
            propsNode.put(entry.getKey(), buildNestedPropertyNode(entry.getValue(), depth));
        }
        node.put("properties", propsNode);
        if ("array".equals(type) && items.getItems() != null && depth < MAX_SCHEMA_NESTING_DEPTH) {
            node.put("items", buildItemsSchemaNode(items.getItems(), depth + 1));
        }
        return node;
    }

    private static Map<String, Object> buildNestedPropertyNode(CompiledSchema propertySchema, int depth) {
        String propType = propertySchema.getType() != null ? propertySchema.getType() : "string";
        Map<String, Object> propNode = new LinkedHashMap<>();
        propNode.put("type", propType);
        if (depth >= MAX_SCHEMA_NESTING_DEPTH) {
            return propNode;
        }
        if ("object".equals(propType) && !propertySchema.getProperties().isEmpty()) {
            propNode.put("objectSchema", buildItemsSchemaNode(propertySchema, depth + 1));
        } else if ("array".equals(propType) && propertySchema.getItems() != null
                && !propertySchema.getItems().getProperties().isEmpty()) {
            propNode.put("itemsSchema", buildItemsSchemaNode(propertySchema.getItems(), depth + 1));
        }
        return propNode;
    }

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback;
    }

    private static String pluralize(String value) {
        if (value == null || value.isBlank()) {
            return "Records";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith("y") && trimmed.length() > 1) {
            return trimmed.substring(0, trimmed.length() - 1) + "ies";
        }
        if (trimmed.endsWith("s")) {
            return trimmed;
        }
        return trimmed + "s";
    }

    private static String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                out.append(' ');
            }
            out.append(i == 0 ? Character.toUpperCase(c) : c);
        }
        return out.toString();
    }

    private static String uncap(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String toSnake(String value) {
        return SqlIdentifierSupport.toSnake(value);
    }

    private static String javaString(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }


    private static String enumName(CompiledConcept concept, CompiledField field) {
        if (field == null || !"enum".equals(manifestType(field))) {
            return "";
        }
        String conceptName = concept == null || concept.getName() == null ? "" : concept.getName().trim();
        String fieldName = field.getName() == null ? "" : field.getName().trim();
        if (conceptName.isBlank() || fieldName.isBlank()) {
            return "";
        }
        return conceptName + fieldName.substring(0, 1).toUpperCase(Locale.ROOT) + fieldName.substring(1);
    }

    private static List<Map<String, Object>> enumOptionsManifest(CompiledField field) {
        if (field == null || field.getEnumValues() == null || field.getEnumValues().isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        if (field.getEnumOptions() != null && !field.getEnumOptions().isEmpty()) {
            List<CompiledEnumOption> options = new ArrayList<>(field.getEnumOptions());
            options.sort(Comparator
                    .comparing((CompiledEnumOption option) -> option.getOrder() == null ? Integer.MAX_VALUE : option.getOrder())
                    .thenComparing(option -> option.getValue() == null ? "" : option.getValue(), String.CASE_INSENSITIVE_ORDER));
            for (CompiledEnumOption option : options) {
                if (option == null || option.getValue() == null || option.getValue().isBlank()) {
                    continue;
                }
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("value", option.getValue());
                node.put("label", enumLabel(option.getValue(), option.getLabel()));
                node.put("order", option.getOrder());
                node.put("group", firstNonBlank(option.getGroup(), ""));
                node.put("default", option.isDefaultValue());
                node.put("deprecated", option.isDeprecated());
                node.put("iconHint", firstNonBlank(option.getIconHint(), ""));
                node.put("badgeHint", firstNonBlank(option.getBadgeHint(), ""));
                node.put("description", firstNonBlank(option.getDescription(), ""));
                out.add(node);
            }
            return List.copyOf(out);
        }

        for (String value : field.getEnumValues()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("value", value);
            node.put("label", humanize(value));
            node.put("order", null);
            node.put("group", "");
            node.put("default", false);
            node.put("deprecated", false);
            node.put("iconHint", "");
            node.put("badgeHint", "");
            node.put("description", "");
            out.add(node);
        }
        return List.copyOf(out);
    }

    private static String enumLabel(String value, String explicitLabel) {
        if (explicitLabel != null && !explicitLabel.isBlank()) {
            return explicitLabel.trim();
        }
        return humanize(value);
    }

    private static String defaultEnumValue(CompiledField field) {
        if (field == null || field.getEnumValues() == null || field.getEnumValues().isEmpty()) {
            return "";
        }
        if (field.getEnumOptions() != null) {
            for (CompiledEnumOption option : field.getEnumOptions()) {
                if (option != null && option.isDefaultValue()
                        && option.getValue() != null
                        && !option.getValue().isBlank()) {
                    return option.getValue();
                }
            }
        }
        return "";
    }

    private static String enumValuesJava(CompiledField field) {
        if (field.getEnumValues() == null || field.getEnumValues().isEmpty()) {
            return "java.util.List.of()";
        }
        List<String> values = field.getEnumValues().stream()
                .map(value -> "\"" + javaString(value) + "\"")
                .toList();
        return "java.util.List.of(" + String.join(", ", values) + ")";
    }
}
