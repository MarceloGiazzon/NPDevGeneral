package com.finalexec.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledContext;
import com.npdev.dsl.v1.compiled.CompiledDocument;
import com.npdev.dsl.v1.compiled.CompiledEnumOption;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFieldAccess;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPresentationMetadata;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.FieldWidgetDefaults;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.settings.NpdevSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * REG-244 Phase 4D: appends a manifest node for a brand-new, live-provisioned concept to the
 * already-on-disk {@code generated-ui-manifest.json}, so the generic business UI can render its
 * list/create/display/update/delete screens with no regenerate/rebuild/restart.
 *
 * <p>Deliberately a MIRROR of (not a shared call with, and not a full port of) {@code
 * BusinessUiEmitter.manifestJson}'s per-concept node loop -- that class lives in {@code
 * NPDevGenerator}, a build-time-only Gradle project RuntimeHost does not depend on at runtime.
 * Rebuilding the WHOLE manifest at reload time is deliberately avoided too: {@code manifestJson}
 * re-resolves every EXISTING concept through a {@code SettingResolver} (field-widget cascade) and
 * pack {@code extensionFieldOrigins}, neither of which has a live equivalent anywhere in
 * RuntimeHost today -- doing so would silently drop cascade overrides/extension attribution for
 * every concept already in the manifest, not just the new one. So this class only ever builds and
 * appends ONE node per newly-provisioned concept, leaving every existing node byte-for-byte
 * untouched.
 *
 * <p>Only ever called with concept names that already appear in {@link
 * NewConceptSchemaProvisioner.Result#provisioned()} -- i.e. concepts that genuinely have a live
 * table (REG-245). That guarantee is exactly what let this class originally skip {@code
 * manifestJson}'s largest dependency, reference-field metadata ({@code referenceMetadata} and its
 * helpers, ~500 lines): before Wave 6.3, {@link NewConceptSchemaProvisioner#outOfScopeReason}
 * refused any concept with a reference field before it could ever reach 4B's provisioning.
 *
 * <p><b>Wave 6.3 (NPDEV_FEATURE_PLAN_2026-09-24.md)</b> widened that scope to a reference targeting
 * an ALREADY-EXISTING concept, so {@link #fieldNodes} now emits a {@code reference} node for one --
 * deliberately a MINIMAL subset of {@code BusinessUiEmitter}'s ~500-line version, not a port of it:
 * {@code targetConcept}/{@code targetIdField}/{@code endpointBase}/{@code displayField} only, which
 * is everything {@code business-ui-app.mustache}'s lookup control (@code createLookupInput},
 * {@code loadReferenceLabel}, {@code openPickerDialog}) actually requires to render and resolve a
 * working picker -- confirmed by reading those functions, not assumed: every OTHER reference
 * property they read (@code displayFields}, {@code searchFields}, {@code pickerColumns}, {@code via},
 * {@code defaultFilterExpression}, ...) already falls back safely to {@code displayField}/{@code
 * targetIdField} or an empty list when absent. A live-provisioned reference field is therefore a
 * working, if plain, lookup -- one search-by-id-or-default-filter picker, not the richer
 * search-fields/columns/filter-expression a REGENERATED app can declare. Widening this further (a
 * declared {@code picker}/{@code referenceSemantics} on the live field) is real future work, not
 * silently claimed here.
 */
public final class LiveConceptUiManifestSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final int MAX_SCHEMA_NESTING_DEPTH = 5;

    private LiveConceptUiManifestSupport() {
    }

    /** {@code failed} maps a concept name to why its manifest node was not written -- an
     *  unreadable manifest file, a concept not found in {@code newModel}, or an I/O failure on
     *  write. A concept already present in the manifest (a repeated reload) counts as {@code
     *  refreshed}, not failed -- same idempotency posture as {@link NewConceptSchemaProvisioner}'s
     *  own {@code IF NOT EXISTS} DDL: the postcondition ("this concept has a manifest node")
     *  already holds, so calling it a failure would be misleading. */
    public record Result(List<String> refreshed, Map<String, String> failed) {
    }

    @SuppressWarnings("unchecked")
    public static Result refresh(Path manifestFile, CompiledModel newModel, List<String> provisionedConceptNames) {
        List<String> refreshed = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        if (provisionedConceptNames == null || provisionedConceptNames.isEmpty()) {
            return new Result(List.of(), Map.of());
        }

        Map<String, Object> root;
        try {
            root = OBJECT_MAPPER.readValue(manifestFile.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException failure) {
            String reason = "could not read existing manifest at " + manifestFile + ": " + failure.getMessage();
            for (String name : provisionedConceptNames) {
                failed.put(name, reason);
            }
            return new Result(List.of(), Map.copyOf(failed));
        }

        Object conceptsRaw = root.get("concepts");
        List<Map<String, Object>> concepts = conceptsRaw instanceof List
                ? (List<Map<String, Object>>) (List<?>) conceptsRaw
                : new ArrayList<>();
        root.put("concepts", concepts);

        Set<String> existingConceptNames = new LinkedHashSet<>();
        for (Map<String, Object> node : concepts) {
            Object name = node.get("conceptName");
            if (name != null) {
                existingConceptNames.add(String.valueOf(name));
            }
        }

        Map<String, CompiledConcept> byName = new LinkedHashMap<>();
        for (CompiledConcept concept : newModel.getConcepts()) {
            byName.put(concept.getName(), concept);
        }

        Object defaultGuidePageRaw = root.get("defaultGuidePage");
        String defaultGuidePage = defaultGuidePageRaw == null ? "" : String.valueOf(defaultGuidePageRaw);
        List<CompiledContext> contexts = newModel.getContexts();
        List<String> newlyBuilt = new ArrayList<>();
        for (String name : provisionedConceptNames) {
            if (existingConceptNames.contains(name)) {
                refreshed.add(name);
                continue;
            }
            CompiledConcept concept = byName.get(name);
            if (concept == null) {
                failed.put(name, "concept not found in the reloaded model");
                continue;
            }
            concepts.add(conceptNode(concept, newModel, contexts, defaultGuidePage));
            newlyBuilt.add(name);
        }

        if (!newlyBuilt.isEmpty()) {
            try {
                Files.createDirectories(manifestFile.getParent());
                Files.writeString(manifestFile, OBJECT_MAPPER.writeValueAsString(root) + System.lineSeparator());
                refreshed.addAll(newlyBuilt);
            } catch (IOException failure) {
                // A write failure here is a single, whole-file problem, not per-concept -- every
                // concept this call would have newly added is reported as failed, never as a
                // partial, unwritten success.
                String reason = "could not write manifest at " + manifestFile + ": " + failure.getMessage();
                for (String name : newlyBuilt) {
                    failed.put(name, reason);
                }
            }
        }

        return new Result(List.copyOf(refreshed), Map.copyOf(failed));
    }

    private static Map<String, Object> conceptNode(
            CompiledConcept concept, CompiledModel model, List<CompiledContext> contexts, String defaultGuidePage
    ) {
        CompiledField idField = idField(concept);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("conceptName", concept.getName());
        node.put("displayName", displayName(concept));
        node.put("admin", false);
        node.put("route", "/" + SqlIdentifierSupport.aliasPreservingTableName(concept, contexts));
        node.put("tableName", SqlIdentifierSupport.tableName(concept));
        node.put("idField", idField.getName());
        node.put("endpointBase", SqlIdentifierSupport.conceptEndpointBase(concept, contexts));
        node.put("formColumns", formColumns(concept));
        node.put("displayMode", displayMode(concept));
        node.put("formPresentation", formPresentation(concept));
        node.put("frameMode", NpdevSettings.UI_FRAME_MODE.defaultValue());
        node.put("guidePage", defaultGuidePage);
        node.put("fields", fieldNodes(concept, model, contexts));
        node.put("list", listNode(concept, idField));
        node.put("documents", documentNodes(model, concept.getName()));
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("list", true);
        actions.put("create", true);
        actions.put("display", true);
        actions.put("update", true);
        actions.put("delete", true);
        node.put("actions", actions);
        return node;
    }

    private static CompiledField idField(CompiledConcept concept) {
        for (CompiledField field : concept.getFields()) {
            if (field.isId()) {
                return field;
            }
        }
        throw new IllegalStateException("Concept " + concept.getName() + " must have exactly one id field.");
    }

    private static List<Map<String, Object>> documentNodes(CompiledModel model, String conceptName) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (CompiledDocument document : model.getDocuments()) {
            if (!conceptName.equals(document.concept())) {
                continue;
            }
            Map<String, Object> documentNode = new LinkedHashMap<>();
            documentNode.put("name", document.name());
            documentNode.put("title", document.title() == null || document.title().isBlank()
                    ? document.name() : document.title());
            documents.add(documentNode);
        }
        return documents;
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

    private static String formPresentation(CompiledConcept concept) {
        CompiledPresentationMetadata ui = concept.getUi();
        String value = ui == null ? null : ui.getFormPresentation();
        return "modal".equals(value) ? "modal" : "standard";
    }

    private static Map<String, Object> listNode(CompiledConcept concept, CompiledField idField) {
        List<String> columns = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            if (isShowInUi(field)) {
                columns.add(field.getName());
            }
        }
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

    private static List<Map<String, Object>> fieldNodes(
            CompiledConcept concept, CompiledModel model, List<CompiledContext> contexts
    ) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", field.getName());
            node.put("concept", concept.getName());
            // No live extensionFieldOrigins to attribute a pack-added field to -- a brand-new
            // concept has no such provenance to report yet.
            node.put("extensionSource", "");
            node.put("label", fieldLabel(field));
            node.put("columnName", SqlIdentifierSupport.toSnake(field.getName()));
            node.put("type", manifestType(field));
            node.put("required", field.isRequired());
            node.put("id", field.isId());
            node.put("readOnly", field.isId());
            node.put("sortable", isSortable(field));
            node.put("filterable", isFilterable(field));
            CompiledFieldAccess access = field.getAccess();
            node.put("accessReadScoped", access != null && access.getRead() != null);
            node.put("accessWriteScoped", access != null && access.getWrite() != null);
            node.put("showInDefaultWebUi", isShowInUi(field));
            node.put("tab", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getTab), ""));
            node.put("column", fieldUiInt(field, CompiledPresentationMetadata::getColumn));
            node.put("columnSpan", fieldUiIntOr(field, CompiledPresentationMetadata::getColumnSpan, 1));
            node.put("visibleWhen", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getVisibleWhen), ""));
            node.put("enabledWhen", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getEnabledWhen), ""));
            node.put("readonlyWhen", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getReadonlyWhen), ""));
            node.put("requiredWhen", firstNonBlank(fieldUiString(field, CompiledPresentationMetadata::getRequiredWhen), ""));
            if ("array".equals(manifestType(field))) {
                CompiledSchema schema = field.getSchema();
                if (schema != null && schema.getItems() != null
                        && (!schema.getItems().getProperties().isEmpty() || "enum".equals(schema.getItems().getType()))) {
                    node.put("itemsSchema", buildItemsSchemaNode(schema.getItems(), 0));
                }
            } else if ("object".equals(manifestType(field))) {
                CompiledSchema schema = field.getSchema();
                if (schema != null && !schema.getProperties().isEmpty()) {
                    node.put("objectSchema", buildItemsSchemaNode(schema, 0));
                }
            }
            // Wave 6.3: a reference field IS possible now (see class javadoc) -- referenceNode
            // resolves it (null for any non-reference field, or one outOfScopeReason would have
            // already refused, so this always resolves when non-null).
            Map<String, Object> referenceNode = referenceNode(field, model, contexts);
            if (referenceNode != null) {
                node.put("reference", referenceNode);
            }
            node.put("widget", widget(field, referenceNode != null));
            node.put("customWidgetRef", firstNonBlank(field.getUi() == null ? null : field.getUi().getCustomWidgetRef(), ""));
            node.put("enumValues", field.getEnumValues());
            node.put("enumOptions", enumOptionsManifest(field));
            node.put("enumName", enumName(concept, field));
            node.put("defaultEnumValue", defaultEnumValue(field));
            if ("file".equals(manifestType(field)) && field.getFile() != null) {
                node.put("fileContentTypes", field.getFile().contentTypes());
                node.put("fileMaxSizeBytes", field.getFile().maxSizeBytes());
                node.put("fileMultiple", field.getFile().multiple());
            }
            fields.add(node);
        }
        return fields;
    }

    private static String widget(CompiledField field, boolean isReference) {
        CompiledPresentationMetadata ui = field.getUi();
        String declaredWidget = ui == null ? null : ui.getWidget();
        if (declaredWidget != null && !declaredWidget.isBlank()) {
            return FieldWidgetDefaults.normalize(declaredWidget);
        }
        String type = manifestType(field);
        boolean hasEnumValues = field.getEnumValues() != null && !field.getEnumValues().isEmpty();
        return FieldWidgetDefaults.defaultWidget(type, isReference, false, hasEnumValues);
    }

    /**
     * Wave 6.3: {@code null} for a non-reference field. For a reference field, a MINIMAL {@code
     * reference} node -- see this class's own javadoc for exactly which four keys and why they are
     * enough for a working (if plain) lookup. {@code outOfScopeReason} already proved the target
     * resolves in {@code model} before this concept could reach {@link #refresh} at all, so {@code
     * findConcept} here is never expected to come back empty -- if it somehow did (a model mutated
     * between provisioning and this call), this field is skipped as non-reference rather than
     * thrown, since a missing lookup on one field must not fail the whole concept's manifest node.
     */
    private static Map<String, Object> referenceNode(
            CompiledField field, CompiledModel model, List<CompiledContext> contexts
    ) {
        String target = field.getReferenceTarget();
        if (target == null || target.isBlank()) {
            return null;
        }
        CompiledConcept targetConcept = model.findConcept(target).orElse(null);
        if (targetConcept == null) {
            return null;
        }
        String targetIdField = idField(targetConcept).getName();
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("targetConcept", targetConcept.getName());
        reference.put("targetIdField", targetIdField);
        reference.put("endpointBase", SqlIdentifierSupport.conceptEndpointBase(targetConcept, contexts));
        reference.put("displayField", targetIdField);
        return reference;
    }

    private static String fieldUiString(CompiledField field, Function<CompiledPresentationMetadata, String> accessor) {
        CompiledPresentationMetadata ui = field.getUi();
        return ui == null ? null : accessor.apply(ui);
    }

    private static Integer fieldUiInt(CompiledField field, Function<CompiledPresentationMetadata, Integer> accessor) {
        return fieldUiIntOr(field, accessor, null);
    }

    private static Integer fieldUiIntOr(
            CompiledField field, Function<CompiledPresentationMetadata, Integer> accessor, Integer fallback
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

    private static String manifestType(CompiledField field) {
        String type = field.getDslType();
        if (type == null || type.isBlank()) {
            return "string";
        }
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isSortable(CompiledField field) {
        return !List.of("object", "array", "file").contains(manifestType(field));
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
        String type = manifestType(field);
        return !"array".equals(type) && !"object".equals(type);
    }

    private static Map<String, Object> buildItemsSchemaNode(CompiledSchema items, int depth) {
        Map<String, Object> node = new LinkedHashMap<>();
        String type = items.getType() != null ? items.getType() : "object";
        node.put("type", type);
        Map<String, Object> propsNode = new LinkedHashMap<>();
        for (Map.Entry<String, CompiledSchema> entry : items.getProperties().entrySet()) {
            propsNode.put(entry.getKey(), buildNestedPropertyNode(entry.getValue(), depth));
        }
        node.put("properties", propsNode);
        if ("enum".equals(type)) {
            node.put("enumValues", items.getEnumValues() == null ? List.of() : items.getEnumValues());
        }
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
                        && option.getValue() != null && !option.getValue().isBlank()) {
                    return option.getValue();
                }
            }
        }
        return "";
    }

    private static String displayName(CompiledConcept concept) {
        CompiledPresentationMetadata ui = concept.getUi();
        String label = ui == null ? null : ui.getLabel();
        if (label != null && !label.isBlank()) {
            return label.trim();
        }
        return pluralize(displayBaseName(concept.getName()));
    }

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

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback;
    }
}
