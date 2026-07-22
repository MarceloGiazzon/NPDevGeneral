package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class CompiledMetadataCanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final String METADATA_VERSION = "1.0.0";

    private CompiledMetadataCanonicalJson() {
    }

    public static String toJson(CompiledModel model) {
        return toJson((Path) null, model);
    }

    public static String toJson(Path modelPath, CompiledModel model) {
        ObjectNode canonical = toCanonicalObject(readRawModel(modelPath), model);
        try {
            return MAPPER.writeValueAsString(canonical) + "\n";
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize compiled metadata as canonical JSON", exception);
        }
    }

    public static String toJson(JsonNode resolvedModelRoot, CompiledModel model) {
        ObjectNode canonical = toCanonicalObject(resolvedModelRoot, model);
        try {
            return MAPPER.writeValueAsString(canonical) + "\n";
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize compiled metadata as canonical JSON", exception);
        }
    }

    public static void write(Path outFile, CompiledModel model) throws IOException {
        write(outFile, null, model);
    }

    public static void write(Path outFile, Path modelPath, CompiledModel model) throws IOException {
        Path parent = outFile.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outFile, toJson(modelPath, model));
    }

    private static ObjectNode toCanonicalObject(JsonNode rawModelRoot, CompiledModel model) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("metadataVersion", METADATA_VERSION);
        root.put("dslVersion", safe(model.getDslVersion()));
        root.put("namespace", safe(model.getNamespace()));
        root.put("version", safe(model.getVersion()));

        ObjectNode catalogs = JsonNodeFactory.instance.objectNode();
        catalogs.set("concepts", toConceptCatalog(model));
        catalogs.set("procedures", toProcedureCatalog(model));
        catalogs.set("panels", toPanelCatalog(model));
        catalogs.set("domainTypes", toDomainTypeCatalog(model));
        catalogs.set("fields", toFieldCatalog(rawModelRoot, model));
        catalogs.set("enums", toEnumCatalog(model));
        catalogs.set("references", toReferenceCatalog(model));
        catalogs.set("actions", toActionCatalog(model));
        catalogs.set("transitions", toTransitionCatalog(model));
        catalogs.set("layout", toLayoutCatalog(rawModelRoot, model));
        catalogs.set("validation", toValidationCatalog(model));

        root.set("catalogs", catalogs);
        return root;
    }

    private static ArrayNode toConceptCatalog(CompiledModel model) {
        Map<String, List<String>> flowNamesByConcept = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (CompiledFlow flow : sortedFlows(model)) {
            if (flow.getConcept() == null || flow.getConcept().isBlank()) {
                continue;
            }
            flowNamesByConcept
                    .computeIfAbsent(flow.getConcept(), ignored -> new ArrayList<>())
                    .add(flow.getName());
        }

        Map<String, List<String>> eventNamesByConcept = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (CompiledEvent event : sortedEvents(model)) {
            if (event.getConceptName() == null || event.getConceptName().isBlank()) {
                continue;
            }
            eventNamesByConcept
                    .computeIfAbsent(event.getConceptName(), ignored -> new ArrayList<>())
                    .add(event.getName());
        }

        ArrayNode concepts = JsonNodeFactory.instance.arrayNode();
        for (CompiledConcept entity : sortedConcepts(model)) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(entity.getName()));
            node.put("className", safe(entity.getClassName()));
            node.put("tableName", safe(entity.getTableName()));
            node.put("label", conceptLabel(entity));
            node.put("shortLabel", conceptUiText(entity.getUi(), "shortLabel", ""));
            node.put("description", conceptUiText(entity.getUi(), "description", ""));
            node.put("helpText", conceptUiText(entity.getUi(), "helpText", ""));
            node.put("group", conceptUiText(entity.getUi(), "group", ""));
            node.put("section", conceptUiText(entity.getUi(), "section", ""));
            node.put("displayMode", conceptUiText(entity.getUi(), "displayMode", ""));
            node.put("defaultSort", conceptUiText(entity.getUi(), "defaultSort", ""));
            node.put("defaultGroup", conceptUiText(entity.getUi(), "defaultGroup", ""));
            if (entity.getUi() == null || entity.getUi().getOrder() == null) {
                node.putNull("order");
            } else {
                node.put("order", entity.getUi().getOrder());
            }
            if (entity.getUi() == null || entity.getUi().getFormColumns() == null) {
                node.putNull("formColumns");
            } else {
                node.put("formColumns", entity.getUi().getFormColumns());
            }
            node.put("advanced", entity.getUi() != null && Boolean.TRUE.equals(entity.getUi().getAdvanced()));
            node.put("deprecated", entity.getUi() != null && Boolean.TRUE.equals(entity.getUi().getDeprecated()));
            node.set("examples", toStringArray(entity.getUi() == null ? List.of() : entity.getUi().getExamples()));
            node.put("lifecycleStatusField", entity.getLifecycle() == null ? "" : safe(entity.getLifecycle().getStatusField()));
            node.put("initialState", entity.getLifecycle() == null ? "" : safe(initialState(entity.getLifecycle())));
            node.set("terminalStates", entity.getLifecycle() == null
                    ? JsonNodeFactory.instance.arrayNode()
                    : toStringArray(terminalStates(entity.getLifecycle())));
            node.put("stateCount", entity.getLifecycle() == null ? 0 : entity.getLifecycle().getStates().size());
            node.put("transitionCount", entity.getLifecycle() == null ? 0 : entity.getLifecycle().getTransitions().size());
            node.set("fieldPaths", toStringArray(sortedFieldPaths(entity)));
            node.set("referenceFields", toStringArray(sortedReferenceFields(entity)));
            node.set("enumFields", toStringArray(sortedEnumFields(entity)));
            node.set("invariantRefs", toStringArray(sortedInvariantRefs(entity)));
            node.set("flowNames", toStringArray(sortStrings(flowNamesByConcept.get(entity.getName()))));
            node.set("eventNames", toStringArray(sortStrings(eventNamesByConcept.get(entity.getName()))));
            concepts.add(node);
        }
        return concepts;
    }

    private static ArrayNode toProcedureCatalog(CompiledModel model) {
        List<ObjectNode> entries = new ArrayList<>();
        for (CompiledProcedure procedure : sortedProcedures(model)) {
            LinkedHashSet<String> stepTypes = new LinkedHashSet<>();
            LinkedHashSet<String> concepts = new LinkedHashSet<>();
            LinkedHashSet<String> queries = new LinkedHashSet<>();
            LinkedHashSet<String> calledProcedures = new LinkedHashSet<>();
            collectProcedureStepMetadata(procedure.steps(), stepTypes, concepts, queries, calledProcedures);

            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("kind", "procedure");
            node.put("ownerName", safe(procedure.name()));
            node.put("name", safe(procedure.name()));
            node.put("description", safe(procedure.description()));
            node.put("parameterCount", procedure.parameters().size());
            node.put("variableCount", procedure.variables().size());
            node.put("stepCount", countProcedureSteps(procedure.steps()));
            node.set("stepTypes", toStringArray(new ArrayList<>(stepTypes)));
            node.set("concepts", toStringArray(new ArrayList<>(concepts)));
            node.set("queries", toStringArray(new ArrayList<>(queries)));
            node.set("calledProcedures", toStringArray(new ArrayList<>(calledProcedures)));
            node.set("permissionRequirements", toStringArray(procedure.permissionRequirements()));
            node.put("returnsType", procedure.returns() == null ? "" : safe(procedure.returns().getType()));
            node.put("tracePolicy", safe(procedure.tracePolicy()));
            node.put("auditPolicy", safe(procedure.auditPolicy()));
            entries.add(node);
        }
        entries.sort(Comparator.comparing(node -> text(node, "name")));
        return toArray(entries);
    }

    private static ArrayNode toPanelCatalog(CompiledModel model) {
        List<ObjectNode> entries = new ArrayList<>();
        for (CompiledPanel panel : sortedPanels(model)) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("kind", "panel");
            node.put("ownerName", safe(panel.name()));
            node.put("name", safe(panel.name()));
            node.put("route", safe(panel.route()));
            node.put("title", safe(panel.title()));
            node.put("layoutType", panel.layout() == null ? "" : safe(panel.layout().type()));
            node.put("dataSourceCount", panel.dataSources().size());
            node.put("fieldBindingCount", panel.fieldBindings().size());
            node.put("actionCount", panel.actions().size());
            node.put("visibility", safe(panel.visibility()));
            node.put("enabledWhen", safe(panel.enabledWhen()));
            node.set("dataSources", toPanelDataSourceSummaries(panel.dataSources()));
            node.set("fields", toStringArray(panelFields(panel)));
            node.set("actions", toPanelActionSummaries(panel.actions()));
            entries.add(node);
        }
        entries.sort(Comparator.comparing(node -> text(node, "name")));
        return toArray(entries);
    }

    private static ArrayNode toDomainTypeCatalog(CompiledModel model) {
        List<ObjectNode> entries = new ArrayList<>();
        for (CompiledDomainType domainType : sortedDomainTypes(model)) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(domainType.getName()));
            node.put("baseType", safe(domainType.getBaseType()));
            node.put("javaType", safe(domainType.getJavaType()));
            node.put("schemaType", inferSchemaType(domainType.getBaseType(), domainType.getValidationSchema()));
            node.put("formatHint", safe(domainType.getFormatHint()));
            node.set("normalizationRules", toStringArray(domainType.getNormalizationRules()));
            node.set("examples", toStringArray(domainType.getExamples()));
            node.put("label", domainType.getUi() == null ? "" : safe(domainType.getUi().getLabel()));
            node.put("placeholder", domainType.getUi() == null ? "" : safe(domainType.getUi().getPlaceholder()));
            node.put("helpText", domainType.getUi() == null ? "" : safe(domainType.getUi().getHelpText()));
            node.put("widget", domainType.getUi() == null ? "" : safe(domainType.getUi().getWidget()));
            CompiledSchema schema = domainType.getValidationSchema();
            node.put("description", schema == null ? "" : safe(schema.getDescription()));
            if (schema == null || schema.getMinLength() == null) {
                node.putNull("minLength");
            } else {
                node.put("minLength", schema.getMinLength());
            }
            if (schema == null || schema.getMaxLength() == null) {
                node.putNull("maxLength");
            } else {
                node.put("maxLength", schema.getMaxLength());
            }
            if (schema == null || schema.getMin() == null) {
                node.putNull("min");
            } else {
                node.put("min", schema.getMin());
            }
            if (schema == null || schema.getMax() == null) {
                node.putNull("max");
            } else {
                node.put("max", schema.getMax());
            }
            node.put("regex", schema == null ? "" : safe(schema.getRegex()));
            entries.add(node);
        }
        entries.sort(Comparator.comparing(node -> text(node, "name")));
        return toArray(entries);
    }

    private static ArrayNode toFieldCatalog(JsonNode rawModelRoot, CompiledModel model) {
        RawModelIndex rawModelIndex = RawModelIndex.from(rawModelRoot);
        Map<String, CompiledDomainType> domainTypesByName = indexDomainTypes(model);
        List<ObjectNode> fieldEntries = new ArrayList<>();
        for (CompiledConcept entity : sortedConcepts(model)) {
            JsonNode rawEntityNode = rawModelIndex.entity(entity.getName());
            for (CompiledField field : sortedFields(entity)) {
                JsonNode rawFieldNode = rawEntityField(rawEntityNode, field.getName());
                CompiledDomainType domainType = domainTypesByName.get(normalize(field.getDomainType()));
                fieldEntries.add(fieldEntry(
                        entity.getName(),
                        field.getName(),
                        "",
                        field.getName(),
                        inferSchemaType(field.getDslType(), field.getSchema()),
                        field.getDslType(),
                        safe(field.getJavaType()),
                        field.isId(),
                        field.isRequired(),
                        field.isUnique(),
                        field.getReferenceTarget(),
                        field.getReferenceSemantics(),
                        field.getDomainType(),
                        field.getEnumValues(),
                        field.getSchema(),
                        domainType,
                        rawFieldNode,
                        field.getUi()
                ));
                appendNestedFieldEntries(
                        fieldEntries,
                        entity.getName(),
                        field.getName(),
                        field.getSchema(),
                        domainType,
                        rawFieldNode
                );
            }
        }
        fieldEntries.sort(Comparator
                .comparing((ObjectNode node) -> text(node, "concept"))
                .thenComparing(node -> text(node, "fieldPath")));
        return toArray(fieldEntries);
    }

    private static ArrayNode toEnumCatalog(CompiledModel model) {
        List<ObjectNode> enumEntries = new ArrayList<>();
        for (CompiledConcept entity : sortedConcepts(model)) {
            for (CompiledField field : sortedFields(entity)) {
                if (!field.getEnumValues().isEmpty()) {
                    if (!field.getEnumOptions().isEmpty()) {
                        for (CompiledEnumOption option : sortedEnumOptions(field.getEnumOptions())) {
                            ObjectNode node = JsonNodeFactory.instance.objectNode();
                            node.put("concept", safe(entity.getName()));
                            node.put("fieldPath", safe(field.getName()));
                            node.put("value", safe(option.getValue()));
                            node.put("label", safe(firstNonBlank(option.getLabel(), humanizeSegment(option.getValue()))));
                            if (option.getOrder() == null) {
                                node.putNull("order");
                            } else {
                                node.put("order", option.getOrder());
                            }
                            node.put("group", safe(option.getGroup()));
                            node.put("default", option.isDefaultValue());
                            node.put("deprecated", option.isDeprecated());
                            node.put("iconHint", safe(option.getIconHint()));
                            node.put("badgeHint", safe(option.getBadgeHint()));
                            node.put("description", safe(option.getDescription()));
                            node.set("values", toStringArray(sortStrings(field.getEnumValues())));
                            enumEntries.add(node);
                        }
                    } else {
                        ObjectNode node = JsonNodeFactory.instance.objectNode();
                        node.put("concept", safe(entity.getName()));
                        node.put("fieldPath", safe(field.getName()));
                        node.set("values", toStringArray(sortStrings(field.getEnumValues())));
                        enumEntries.add(node);
                    }
                }
                appendNestedEnumEntries(enumEntries, entity.getName(), field.getName(), field.getSchema());
            }
        }
        enumEntries.sort(Comparator
                .comparing((ObjectNode node) -> text(node, "concept"))
                .thenComparing(node -> text(node, "fieldPath"))
                .thenComparing(node -> numericOrMax(node, "order"))
                .thenComparing(node -> text(node, "value")));
        return toArray(enumEntries);
    }

    private static ArrayNode toReferenceCatalog(CompiledModel model) {
        ArrayNode references = JsonNodeFactory.instance.arrayNode();
        for (CompiledConcept entity : sortedConcepts(model)) {
            for (CompiledField field : sortedFields(entity)) {
                if (field.getReferenceTarget() == null || field.getReferenceTarget().isBlank()) {
                    continue;
                }
                CompiledConcept targetEntity = model.findConcept(field.getReferenceTarget()).orElse(null);
                CompiledReferenceSemantics referenceSemantics = field.getReferenceSemantics();
                String displayField = firstNonBlank(
                        referenceSemantics == null ? null : referenceSemantics.getDisplayField(),
                        inferReferenceDisplayField(targetEntity)
                );
                List<String> searchFields = effectiveReferenceSearchFields(referenceSemantics, targetEntity, displayField);
                List<String> previewFields = effectiveReferencePreviewFields(referenceSemantics, targetEntity, displayField);
                List<String> pickerColumns = effectiveReferencePickerColumns(referenceSemantics, searchFields, displayField);
                CompiledField anchorField = resolveReferenceAnchor(targetEntity, referenceSemantics);
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("concept", safe(entity.getName()));
                node.put("fieldPath", safe(field.getName()));
                node.put("targetConcept", safe(field.getReferenceTarget()));
                node.put("sourceTruthLevel", safe(entity.getTruthLevel()));
                node.put("targetTruthLevel", safe(targetEntity == null ? "" : targetEntity.getTruthLevel()));
                node.put("via", safe(referenceSemantics == null ? "" : referenceSemantics.getVia()));
                node.put("onDelete", safe(referenceSemantics == null ? "restrict" : firstNonBlank(referenceSemantics.getOnDelete(), "restrict")));
                node.put("anchorField", safe(anchorField == null ? "" : anchorField.getName()));
                node.put("anchorType", safe(anchorField == null ? "" : anchorField.getDslType()));
                node.put("cardinality", referenceSemantics != null && referenceSemantics.isMultiple()
                        ? "many-to-many"
                        : (field.isUnique() ? "one-to-one" : "many-to-one"));
                node.put("upwardTruthEdge", targetEntity != null
                        && truthRank(entity.getTruthLevel()) > truthRank(targetEntity.getTruthLevel()));
                node.put("multiple", referenceSemantics != null && referenceSemantics.isMultiple());
                node.put("required", field.isRequired());
                node.put("displayField", safe(displayField));
                node.put("displayTemplate", effectiveReferenceDisplayTemplate(referenceSemantics, displayField));
                node.set("searchFields", toStringArray(searchFields));
                node.set("previewFields", toStringArray(previewFields));
                node.set("pickerColumns", toStringArray(pickerColumns));
                node.put("previewCardTemplate", effectiveReferencePreviewCardTemplate(referenceSemantics, previewFields, displayField));
                node.put("defaultFilter", effectiveReferenceDefaultFilter(referenceSemantics));
                node.put("inlineCreate", safe(referenceSemantics == null ? "deny" : firstNonBlank(referenceSemantics.getInlineCreatePolicy(), "deny")));
                references.add(node);
            }
        }
        return references;
    }

    private static CompiledField resolveReferenceAnchor(
            CompiledConcept targetEntity,
            CompiledReferenceSemantics referenceSemantics
    ) {
        if (targetEntity == null) {
            return null;
        }
        String via = referenceSemantics == null ? null : referenceSemantics.getVia();
        if (via != null && !via.isBlank()) {
            for (CompiledField field : targetEntity.getFields()) {
                if (field != null && via.equalsIgnoreCase(field.getName())) {
                    return field;
                }
            }
        }
        for (CompiledField field : targetEntity.getFields()) {
            if (field != null && field.isId()) {
                return field;
            }
        }
        return null;
    }

    private static int truthRank(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() >= 2 && normalized.charAt(0) == 'T') {
            try {
                return Integer.parseInt(normalized.substring(1, 2));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private static ArrayNode toActionCatalog(CompiledModel model) {
        List<ObjectNode> entries = new ArrayList<>();

        for (CompiledFlow flow : sortedFlows(model)) {
            ObjectNode flowNode = JsonNodeFactory.instance.objectNode();
            flowNode.put("kind", "flow");
            flowNode.put("ownerName", safe(flow.getName()));
            flowNode.put("name", safe(flow.getName()));
            flowNode.put("concept", safe(flow.getConcept()));
            flowNode.put("mode", safe(flow.getMode()));
            flowNode.put("actionType", "flow");
            flowNode.put("orderKey", "flow");
            flowNode.set("invariantRefs", JsonNodeFactory.instance.arrayNode());
            flowNode.put("triggerEvent", "");
            flowNode.put("capability", "");
            flowNode.put("operation", "");
            flowNode.put("eventName", "");
            flowNode.put("condition", "");
            flowNode.put("scope", "");
            flowNode.put("inputRef", "");
            flowNode.put("outputRef", "");
            flowNode.put("mapFromRef", "");
            flowNode.put("mapToRef", "");
            flowNode.put("returnValueRef", "");
            flowNode.put("awaitEventName", "");
            flowNode.put("awaitMatchCorrelation", "");
            flowNode.set("awaitPayloadMatch", JsonNodeFactory.instance.objectNode());
            flowNode.putNull("delaySeconds");
            flowNode.put("stepDepth", 0);
            flowNode.put("branchPath", "");
            flowNode.put("childStepCount", 0);
            flowNode.set("capabilityArgsRefs", JsonNodeFactory.instance.arrayNode());
            flowNode.put("correlationHint", "");
            putActionMetadata(flowNode, flow.getAction(), null);
            entries.add(flowNode);

            appendFlowStepEntries(entries, flow, flow.getSteps(), "");
        }

        for (CompiledOrchestration rule : sortedOrchestrationRules(model)) {
            List<CompiledOrchestrationAction> actions = rule.getActions().isEmpty()
                    ? (rule.getAction() == null ? List.of() : List.of(rule.getAction()))
                    : rule.getActions();
            for (int index = 0; index < actions.size(); index++) {
                CompiledOrchestrationAction action = actions.get(index);
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("kind", "orchestrationAction");
                node.put("ownerName", safe(rule.getName()));
                node.put("name", safe(rule.getName()) + "#" + (index + 1));
                node.put("concept", safe(action.getConcept()));
                node.put("mode", "");
                node.put("actionType", safe(action.getType()));
                node.put("orderKey", "rule:" + pad(index));
                node.set("invariantRefs", JsonNodeFactory.instance.arrayNode());
                node.put("triggerEvent", rule.getTrigger() == null ? "" : safe(rule.getTrigger().getEvent()));
                node.put("capability", safe(action.getCapability()));
                node.put("operation", safe(action.getOperation()));
                node.put("eventName", safe(action.getEvent()));
                node.put("condition", safe(rule.getCondition()));
                node.put("scope", "");
                node.put("mapFromRef", "");
                node.put("mapToRef", "");
                node.put("returnValueRef", "");
                putActionMetadata(node, action.getAction(), null);
                entries.add(node);
            }
        }

        entries.sort(Comparator
                .comparing((ObjectNode node) -> text(node, "ownerName"))
                .thenComparing(node -> text(node, "kind"))
                .thenComparing(node -> text(node, "orderKey"))
                .thenComparing(node -> text(node, "name")));
        return toArray(entries);
    }

    private static ArrayNode toTransitionCatalog(CompiledModel model) {
        List<ObjectNode> entries = new ArrayList<>();
        for (CompiledConcept entity : sortedConcepts(model)) {
            CompiledLifecycle lifecycle = entity.getLifecycle();
            if (lifecycle == null) {
                continue;
            }
            List<CompiledStateTransition> transitions = new ArrayList<>(lifecycle.getTransitions());
            transitions.sort(Comparator
                    .comparing((CompiledStateTransition transition) -> normalize(transition.getFrom()))
                    .thenComparing(transition -> normalize(transition.getTo()))
                    .thenComparing(transition -> normalize(transition.getEvent())));
            for (CompiledStateTransition transition : transitions) {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("concept", safe(entity.getName()));
                node.put("statusField", safe(lifecycle.getStatusField()));
                node.put("from", safe(transition.getFrom()));
                node.put("to", safe(transition.getTo()));
                node.put("event", safe(transition.getEvent()));
                node.put("guard", safe(transition.getGuard()));
                node.put("actionLabel", safe(transition.getActionLabel()));
                putActionMetadata(node, transition.getAction(), transition.getActionLabel());
                node.set("requiredPayload", toStringArray(sortStrings(transition.getRequiredPayload())));
                node.set("metadata", toStringMap(transition.getMetadata()));
                entries.add(node);
            }
        }
        return toArray(entries);
    }

    private static ArrayNode toLayoutCatalog(JsonNode rawModelRoot, CompiledModel model) {
        RawModelIndex rawModelIndex = RawModelIndex.from(rawModelRoot);
        Map<String, CompiledDomainType> domainTypesByName = indexDomainTypes(model);
        List<ObjectNode> entries = new ArrayList<>();
        for (CompiledConcept entity : sortedConcepts(model)) {
            JsonNode rawEntityNode = rawModelIndex.entity(entity.getName());
            for (CompiledField field : sortedFields(entity)) {
                JsonNode rawFieldNode = rawEntityField(rawEntityNode, field.getName());
                CompiledDomainType domainType = domainTypesByName.get(normalize(field.getDomainType()));
                entries.add(layoutEntry(
                        entity.getName(),
                        field.getName(),
                        field.getName(),
                        inferSchemaType(field.getDslType(), field.getSchema()),
                        field.getDslType(),
                        domainType,
                        field.getReferenceTarget(),
                        field.getReferenceSemantics(),
                        field.getEnumValues(),
                        rawUi(rawFieldNode),
                        field.getUi()
                ));
                appendNestedLayoutEntries(entries, entity.getName(), field.getName(), field.getSchema(), domainType, rawFieldNode);
            }
        }
        entries.sort(Comparator
                .comparing((ObjectNode node) -> text(node, "concept"))
                .thenComparing(node -> numericOrMax(node, "order"))
                .thenComparing(node -> text(node, "fieldPath")));
        return toArray(entries);
    }

    private static ArrayNode toValidationCatalog(CompiledModel model) {
        List<ObjectNode> entries = new ArrayList<>();
        for (CompiledConcept entity : sortedConcepts(model)) {
            for (CompiledInvariant invariant : sortedInvariants(entity)) {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("kind", safe(invariant.getType()));
                node.put("concept", safe(entity.getName()));
                node.put("fieldPath", safe(invariant.getField()));
                node.put("invariantRef", safe(invariant.getRef()));
                node.put("expression", safe(invariant.getExpression()));
                node.put("flowName", "");
                node.put("stepName", "");
                node.put("transition", "");
                node.put("event", "");
                entries.add(node);
            }

            for (CompiledField field : sortedFields(entity)) {
                if (field.getDomainType() != null && !field.getDomainType().isBlank()) {
                    ObjectNode node = JsonNodeFactory.instance.objectNode();
                    node.put("kind", "domainType");
                    node.put("concept", safe(entity.getName()));
                    node.put("fieldPath", safe(field.getName()));
                    node.put("invariantRef", safe(field.getDomainType()));
                    node.put("expression", field.getSchema() == null ? "" : safe(field.getSchema().getRegex()));
                    node.put("flowName", "");
                    node.put("stepName", "");
                    node.put("transition", "");
                    node.put("event", "");
                    entries.add(node);
                }
                CompiledSchema fieldSchema = field.getSchema();
                if (fieldSchema != null && fieldSchema.getDefaultValue() != null) {
                    ObjectNode node = JsonNodeFactory.instance.objectNode();
                    node.put("kind", "staticDefault");
                    node.put("concept", safe(entity.getName()));
                    node.put("fieldPath", safe(field.getName()));
                    node.put("invariantRef", "");
                    node.put("expression", "");
                    node.put("flowName", "");
                    node.put("stepName", "");
                    node.put("transition", "");
                    node.put("event", "");
                    entries.add(node);
                }
                if (fieldSchema != null && fieldSchema.getDefaultExpression() != null && !fieldSchema.getDefaultExpression().isBlank()) {
                    ObjectNode node = JsonNodeFactory.instance.objectNode();
                    node.put("kind", "dynamicDefault");
                    node.put("concept", safe(entity.getName()));
                    node.put("fieldPath", safe(field.getName()));
                    node.put("invariantRef", "");
                    node.put("expression", safe(fieldSchema.getDefaultExpression()));
                    node.put("flowName", "");
                    node.put("stepName", "");
                    node.put("transition", "");
                    node.put("event", "");
                    entries.add(node);
                }
                if (fieldSchema != null && fieldSchema.getDerivedExpression() != null && !fieldSchema.getDerivedExpression().isBlank()) {
                    ObjectNode node = JsonNodeFactory.instance.objectNode();
                    node.put("kind", "derivedField");
                    node.put("concept", safe(entity.getName()));
                    node.put("fieldPath", safe(field.getName()));
                    node.put("invariantRef", "");
                    node.put("expression", safe(fieldSchema.getDerivedExpression()));
                    node.put("flowName", "");
                    node.put("stepName", "");
                    node.put("transition", "");
                    node.put("event", "");
                    entries.add(node);
                }
                appendNestedRequiredValidationEntries(entries, entity.getName(), field.getName(), field.getSchema());
            }

            CompiledLifecycle lifecycle = entity.getLifecycle();
            if (lifecycle != null) {
                List<CompiledStateTransition> transitions = new ArrayList<>(lifecycle.getTransitions());
                transitions.sort(Comparator
                        .comparing((CompiledStateTransition transition) -> normalize(transition.getFrom()))
                        .thenComparing(transition -> normalize(transition.getTo()))
                        .thenComparing(transition -> normalize(transition.getEvent())));
                for (CompiledStateTransition transition : transitions) {
                    for (String requiredField : sortStrings(transition.getRequires())) {
                        ObjectNode node = JsonNodeFactory.instance.objectNode();
                        node.put("kind", "transitionRequired");
                        node.put("concept", safe(entity.getName()));
                        node.put("fieldPath", safe(requiredField));
                        node.put("invariantRef", "");
                        node.put("expression", "");
                        node.put("flowName", "");
                        node.put("stepName", "");
                        node.put("transition", safe(transition.getFrom()) + "->" + safe(transition.getTo()));
                        node.put("event", safe(transition.getEvent()));
                        entries.add(node);
                    }
                }
            }
        }

        for (CompiledFlow flow : sortedFlows(model)) {
            appendFlowInvariantValidationEntries(entries, flow, flow.getSteps());
        }

        entries.sort(Comparator
                .comparing((ObjectNode node) -> text(node, "concept"))
                .thenComparing(node -> text(node, "kind"))
                .thenComparing(node -> text(node, "fieldPath"))
                .thenComparing(node -> text(node, "invariantRef"))
                .thenComparing(node -> text(node, "flowName"))
                .thenComparing(node -> text(node, "stepName"))
                .thenComparing(node -> text(node, "transition")));
        return toArray(entries);
    }

    private static void appendFlowStepEntries(
            List<ObjectNode> entries,
            CompiledFlow flow,
            List<CompiledFlowStep> steps,
            String prefix
    ) {
        for (int index = 0; index < steps.size(); index++) {
            CompiledFlowStep step = steps.get(index);
            String orderKey = prefix.isBlank() ? pad(index) : prefix + "." + pad(index);
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("kind", "flowStep");
            node.put("ownerName", safe(flow.getName()));
            node.put("name", safe(step.getName()));
            node.put("concept", safe(flow.getConcept()));
            node.put("mode", safe(flow.getMode()));
            node.put("actionType", safe(step.getType()));
            node.put("orderKey", orderKey);
            node.set("invariantRefs", toStringArray(sortStrings(step.getInvariants())));
            node.put("triggerEvent", "");
            node.put("capability", step.getCapabilityCall() == null ? "" : safe(step.getCapabilityCall().getCapabilityName()));
            node.put("operation", step.getCapabilityCall() == null ? "" : safe(step.getCapabilityCall().getOperation()));
            node.put("eventName", safe(step.getEventName()));
            node.put("condition", safe(step.getCondition()));
            node.put("scope", safe(step.getScope()));
            node.put("inputRef", safe(step.getCapabilityCall() == null ? step.getMapFromRef() : step.getCapabilityCall().getInputRef()));
            node.put("outputRef", safe(step.getCapabilityCall() == null ? step.getMapToRef() : step.getCapabilityCall().getOutputRef()));
            node.put("mapFromRef", safe(step.getMapFromRef()));
            node.put("mapToRef", safe(step.getMapToRef()));
            node.put("returnValueRef", safe(step.getReturnValueRef()));
            node.put("awaitEventName", safe(step.getAwaitEventName()));
            if (step.getAwaitMatchCorrelation() == null) {
                node.put("awaitMatchCorrelation", "");
            } else {
                node.put("awaitMatchCorrelation", step.getAwaitMatchCorrelation());
            }
            node.set("awaitPayloadMatch", toStringMap(step.getAwaitPayloadMatch()));
            if (step.getDelaySeconds() == null) {
                node.putNull("delaySeconds");
            } else {
                node.put("delaySeconds", step.getDelaySeconds());
            }
            node.put("stepDepth", computeStepDepth(orderKey));
            node.put("branchPath", branchPath(orderKey));
            node.put("childStepCount", step.getThenSteps().size() + step.getElseSteps().size());
            node.set("capabilityArgsRefs", toStringArray(step.getCapabilityCall() == null ? List.of() : step.getCapabilityCall().getArgsRefs()));
            node.put("correlationHint", flowCorrelationHint(step));
            putActionMetadata(node, step.getAction(), null);
            entries.add(node);

            appendFlowStepEntries(entries, flow, step.getThenSteps(), orderKey + ".then");
            appendFlowStepEntries(entries, flow, step.getElseSteps(), orderKey + ".else");
        }
    }

    private static void appendFlowInvariantValidationEntries(
            List<ObjectNode> entries,
            CompiledFlow flow,
            List<CompiledFlowStep> steps
    ) {
        for (CompiledFlowStep step : steps) {
            for (String invariantRef : sortStrings(step.getInvariants())) {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("kind", "flowInvariantRef");
                node.put("concept", safe(firstNonBlank(step.getScope(), flow.getConcept())));
                node.put("fieldPath", "");
                node.put("invariantRef", safe(invariantRef));
                node.put("expression", "");
                node.put("flowName", safe(flow.getName()));
                node.put("stepName", safe(step.getName()));
                node.put("transition", "");
                node.put("event", "");
                entries.add(node);
            }
            appendFlowInvariantValidationEntries(entries, flow, step.getThenSteps());
            appendFlowInvariantValidationEntries(entries, flow, step.getElseSteps());
        }
    }

    private static int computeStepDepth(String orderKey) {
        if (orderKey == null || orderKey.isBlank()) {
            return 0;
        }
        int depth = 0;
        int index = 0;
        while (index >= 0 && index < orderKey.length()) {
            int thenIndex = orderKey.indexOf(".then", index);
            int elseIndex = orderKey.indexOf(".else", index);
            int nextIndex;
            if (thenIndex < 0) {
                nextIndex = elseIndex;
            } else if (elseIndex < 0) {
                nextIndex = thenIndex;
            } else {
                nextIndex = Math.min(thenIndex, elseIndex);
            }
            if (nextIndex < 0) {
                break;
            }
            depth++;
            index = nextIndex + 5;
        }
        return depth;
    }

    private static String branchPath(String orderKey) {
        if (orderKey == null || orderKey.isBlank()) {
            return "";
        }
        List<String> path = new ArrayList<>();
        int index = 0;
        while (index >= 0 && index < orderKey.length()) {
            int thenIndex = orderKey.indexOf(".then", index);
            int elseIndex = orderKey.indexOf(".else", index);
            int nextIndex;
            String nextLabel;
            if (thenIndex < 0 && elseIndex < 0) {
                break;
            }
            if (thenIndex >= 0 && (elseIndex < 0 || thenIndex < elseIndex)) {
                nextIndex = thenIndex;
                nextLabel = "then";
            } else {
                nextIndex = elseIndex;
                nextLabel = "else";
            }
            path.add(nextLabel);
            index = nextIndex + 5;
        }
        return String.join("/", path);
    }

    private static String flowCorrelationHint(CompiledFlowStep step) {
        if (step == null) {
            return "";
        }
        if (Boolean.TRUE.equals(step.getAwaitMatchCorrelation())) {
            return "match-current-correlation";
        }
        if (step.getAwaitPayloadMatch() != null && !step.getAwaitPayloadMatch().isEmpty()) {
            return "payload-match";
        }
        String stepType = normalize(step.getType());
        if ("event".equals(stepType) || "scheduleevent".equals(stepType) || "await".equals(stepType)) {
            return "flow-correlation";
        }
        return "";
    }

    private static void putActionMetadata(ObjectNode node, CompiledActionMetadata action, String fallbackLabel) {
        node.put("label", safe(firstNonBlank(action == null ? null : action.getLabel(), fallbackLabel)));
        node.put("confirmationText", safe(action == null ? null : action.getConfirmationText()));
        node.put("successMessage", safe(action == null ? null : action.getSuccessMessage()));
        node.put("failureHint", safe(action == null ? null : action.getFailureHint()));
        node.put("dangerLevel", safe(action == null ? null : action.getDangerLevel()));
        node.put("visibleWhen", safe(action == null ? null : action.getVisibleWhen()));
        node.put("permissionHint", safe(action == null ? null : action.getPermissionHint()));
        node.put("inputFormHint", safe(action == null ? null : action.getInputFormHint()));
    }

    private static void appendNestedFieldEntries(
            List<ObjectNode> entries,
            String concept,
            String parentPath,
            CompiledSchema schema,
            CompiledDomainType domainType,
            JsonNode rawSchemaNode
    ) {
        if (schema == null) {
            return;
        }
        List<String> propertyNames = new ArrayList<>(schema.getProperties().keySet());
        propertyNames.sort(String.CASE_INSENSITIVE_ORDER);
        for (String propertyName : propertyNames) {
            CompiledSchema propertySchema = schema.getProperties().get(propertyName);
            String fieldPath = parentPath + "." + propertyName;
            JsonNode rawPropertyNode = rawProperty(rawSchemaNode, propertyName);
            entries.add(fieldEntry(
                    concept,
                    fieldPath,
                    parentPath,
                    propertyName,
                    inferSchemaType(propertySchema == null ? null : propertySchema.getType(), propertySchema),
                    propertySchema == null ? "" : safe(propertySchema.getType()),
                    inferJavaType(propertySchema),
                    false,
                    schema.getRequired().contains(propertyName),
                    false,
                    null,
                    null,
                    "",
                    propertySchema == null ? List.of() : propertySchema.getEnumValues(),
                    propertySchema,
                    domainType,
                    rawPropertyNode,
                    null
            ));
            appendNestedFieldEntries(entries, concept, fieldPath, propertySchema, domainType, rawPropertyNode);
        }
        appendArrayItemFieldEntries(entries, concept, parentPath, schema, domainType, rawSchemaNode);
    }

    private static void appendArrayItemFieldEntries(
            List<ObjectNode> entries,
            String concept,
            String parentPath,
            CompiledSchema schema,
            CompiledDomainType domainType,
            JsonNode rawSchemaNode
    ) {
        if (schema == null || schema.getItems() == null) {
            return;
        }
        String itemPath = parentPath + "[]";
        JsonNode rawItemsNode = rawSchemaNode == null ? null : rawSchemaNode.get("items");
        entries.add(fieldEntry(
                concept,
                itemPath,
                parentPath,
                "[]",
                inferSchemaType(schema.getItems().getType(), schema.getItems()),
                safe(schema.getItems().getType()),
                inferJavaType(schema.getItems()),
                false,
                false,
                false,
                null,
                null,
                "",
                schema.getItems().getEnumValues(),
                schema.getItems(),
                domainType,
                rawItemsNode,
                null
        ));
        appendNestedFieldEntries(entries, concept, itemPath, schema.getItems(), domainType, rawItemsNode);
    }

    private static void appendNestedEnumEntries(
            List<ObjectNode> entries,
            String concept,
            String parentPath,
            CompiledSchema schema
    ) {
        if (schema == null) {
            return;
        }
        List<String> propertyNames = new ArrayList<>(schema.getProperties().keySet());
        propertyNames.sort(String.CASE_INSENSITIVE_ORDER);
        for (String propertyName : propertyNames) {
            CompiledSchema propertySchema = schema.getProperties().get(propertyName);
            String fieldPath = parentPath + "." + propertyName;
            if (propertySchema != null && !propertySchema.getEnumValues().isEmpty()) {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("concept", safe(concept));
                node.put("fieldPath", safe(fieldPath));
                node.set("values", toStringArray(sortStrings(propertySchema.getEnumValues())));
                entries.add(node);
            }
            appendNestedEnumEntries(entries, concept, fieldPath, propertySchema);
        }
        if (schema.getItems() != null) {
            appendNestedEnumEntries(entries, concept, parentPath + "[]", schema.getItems());
        }
    }

    private static void appendNestedLayoutEntries(
            List<ObjectNode> entries,
            String concept,
            String parentPath,
            CompiledSchema schema,
            CompiledDomainType domainType,
            JsonNode rawSchemaNode
    ) {
        if (schema == null) {
            return;
        }
        List<String> propertyNames = new ArrayList<>(schema.getProperties().keySet());
        propertyNames.sort(String.CASE_INSENSITIVE_ORDER);
        for (String propertyName : propertyNames) {
            CompiledSchema propertySchema = schema.getProperties().get(propertyName);
            String fieldPath = parentPath + "." + propertyName;
            JsonNode rawPropertyNode = rawProperty(rawSchemaNode, propertyName);
            entries.add(layoutEntry(
                    concept,
                    fieldPath,
                    propertyName,
                    inferSchemaType(propertySchema == null ? null : propertySchema.getType(), propertySchema),
                    propertySchema == null ? "" : safe(propertySchema.getType()),
                    domainType,
                    null,
                    null,
                    propertySchema == null ? List.of() : propertySchema.getEnumValues(),
                    rawUi(rawPropertyNode),
                    null
            ));
            appendNestedLayoutEntries(entries, concept, fieldPath, propertySchema, domainType, rawPropertyNode);
        }
        if (schema.getItems() != null) {
            appendNestedLayoutEntries(entries, concept, parentPath + "[]", schema.getItems(), domainType, rawSchemaNode == null ? null : rawSchemaNode.get("items"));
        }
    }

    private static void appendNestedRequiredValidationEntries(
            List<ObjectNode> entries,
            String concept,
            String parentPath,
            CompiledSchema schema
    ) {
        if (schema == null) {
            return;
        }
        for (String requiredField : sortStrings(schema.getRequired())) {
            String fieldPath = parentPath + "." + requiredField;
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("kind", "required");
            node.put("concept", safe(concept));
            node.put("fieldPath", safe(fieldPath));
            node.put("invariantRef", "required(" + fieldPath + ")");
            node.put("expression", "");
            node.put("flowName", "");
            node.put("stepName", "");
            node.put("transition", "");
            node.put("event", "");
            entries.add(node);
        }
        List<String> propertyNames = new ArrayList<>(schema.getProperties().keySet());
        propertyNames.sort(String.CASE_INSENSITIVE_ORDER);
        for (String propertyName : propertyNames) {
            appendNestedRequiredValidationEntries(entries, concept, parentPath + "." + propertyName, schema.getProperties().get(propertyName));
        }
        if (schema.getItems() != null) {
            appendNestedRequiredValidationEntries(entries, concept, parentPath + "[]", schema.getItems());
        }
    }

    private static ObjectNode fieldEntry(
            String concept,
            String fieldPath,
            String parentPath,
            String segment,
            String schemaType,
            String dslType,
            String javaType,
            boolean id,
            boolean required,
            boolean unique,
            String referenceTarget,
            CompiledReferenceSemantics referenceSemantics,
            String domainTypeName,
            List<String> enumValues,
            CompiledSchema schema,
            CompiledDomainType domainType,
            JsonNode rawSchemaNode,
            CompiledPresentationMetadata ui
    ) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("concept", safe(concept));
        node.put("fieldPath", safe(fieldPath));
        node.put("parentPath", safe(parentPath));
        node.put("segment", safe(segment));
        node.put("dslType", safe(dslType));
        node.put("schemaType", safe(schemaType));
        node.put("javaType", safe(javaType));
        node.put("id", id);
        node.put("required", required);
        node.put("unique", unique);
        node.put("referenceTarget", safe(referenceTarget));
        node.put("domainType", safe(domainTypeName));
        node.put("formatHint", domainType == null ? "" : safe(domainType.getFormatHint()));
        node.put("nested", isNestedSchema(schema));
        node.put("repeatedSection", "array".equals(normalize(schemaType)));
        if (schema == null || schema.getMinItems() == null) {
            node.putNull("minItems");
        } else {
            node.put("minItems", schema.getMinItems());
        }
        if (schema == null || schema.getMaxItems() == null) {
            node.putNull("maxItems");
        } else {
            node.put("maxItems", schema.getMaxItems());
        }
        if (schema == null || schema.getUniqueItems() == null) {
            node.putNull("uniqueItems");
        } else {
            node.put("uniqueItems", schema.getUniqueItems());
        }
        node.put("itemIdentityField", schema == null ? "" : safe(schema.getItemIdentityField()));
        node.put("duplicationPolicy", schema == null ? "" : safe(schema.getDuplicationPolicy()));
        node.put("validationPath", safe(fieldPath));
        node.set("enumValues", toStringArray(sortStrings(enumValues)));
        node.set("normalizationRules", toStringArray(domainType == null ? List.of() : domainType.getNormalizationRules()));
        node.set("examples", toStringArray(domainType == null ? List.of() : domainType.getExamples()));
        if (schema == null || schema.getDefaultValue() == null) {
            node.putNull("defaultValue");
        } else {
            node.set("defaultValue", MAPPER.valueToTree(schema.getDefaultValue()));
        }
        node.put("defaultExpression", schema == null ? "" : safe(schema.getDefaultExpression()));
        node.put("derivedExpression", schema == null ? "" : safe(schema.getDerivedExpression()));
        node.put("computed", schema != null && schema.getDerivedExpression() != null && !schema.getDerivedExpression().isBlank());
        String description = schema == null ? "" : safe(schema.getDescription());
        if (description.isBlank() && rawSchemaNode != null && rawSchemaNode.has("description")) {
            description = safe(rawSchemaNode.get("description").asText());
        }
        node.put("description", description);
        JsonNode uiNode = firstPresentationNode(presentationNode(ui), rawUi(rawSchemaNode));
        node.put("label", readUiText(uiNode, domainUiNode(domainType), "label", labelFromPath(fieldPath)));
        node.put("shortLabel", readUiText(uiNode, "shortLabel", ""));
        node.put("helpText", readUiText(uiNode, domainUiNode(domainType), "helpText", ""));
        node.put("placeholder", readUiText(uiNode, domainUiNode(domainType), "placeholder", ""));
        node.put("group", readUiText(uiNode, "group", ""));
        node.put("section", readUiText(uiNode, "section", ""));
        Integer order = readUiInt(uiNode, "order");
        if (order == null) {
            node.putNull("order");
        } else {
            node.put("order", order);
        }
        node.put("advanced", readUiBoolean(uiNode, "advanced"));
        node.put("deprecated", readUiBoolean(uiNode, "deprecated"));
        node.set("presentationExamples", toStringArray(readUiArray(uiNode, "examples")));
        node.put("widget", readUiText(uiNode, domainUiNode(domainType), "widget", defaultWidget(schemaType, dslType, domainType, referenceTarget, enumValues)));
        node.put("visibleWhen", readUiText(uiNode, "visibleWhen", ""));
        node.put("enabledWhen", readUiText(uiNode, "enabledWhen", ""));
        node.put("readonlyWhen", readUiText(uiNode, "readonlyWhen", ""));
        node.put("requiredWhen", readUiText(uiNode, "requiredWhen", ""));
        node.put("pickerType", effectivePickerType(uiNode, referenceTarget));
        node.put("allowInlineCreate", effectiveAllowInlineCreate(uiNode, referenceSemantics));
        List<String> effectiveSearchFields = effectiveInteractionSearchFields(uiNode, referenceSemantics);
        List<String> effectivePreviewFields = explicitOrFallbackReferencePreviewFields(referenceSemantics);
        String effectiveDisplayField = explicitReferenceDisplayField(referenceSemantics);
        node.set("searchFields", toStringArray(effectiveSearchFields));
        node.put("filterPreset", effectiveFilterPreset(uiNode, referenceSemantics));
        node.put("displayTemplate", effectiveReferenceDisplayTemplate(referenceSemantics, effectiveDisplayField));
        node.set("pickerColumns", toStringArray(effectiveReferencePickerColumns(referenceSemantics, effectiveSearchFields, effectiveDisplayField)));
        node.put("previewCardTemplate", effectiveReferencePreviewCardTemplate(referenceSemantics, effectivePreviewFields, effectiveDisplayField));
        node.put("tab", readUiText(uiNode, "tab", ""));
        Integer column = readUiInt(uiNode, "column");
        if (column == null) {
            node.putNull("column");
        } else {
            node.put("column", column);
        }
        Integer columnSpan = readUiInt(uiNode, "columnSpan");
        if (columnSpan == null) {
            node.putNull("columnSpan");
        } else {
            node.put("columnSpan", columnSpan);
        }
        node.put("width", readUiText(uiNode, "width", ""));
        node.put("summaryCard", readUiBooleanObject(uiNode, "summaryCard"));
        node.put("listColumn", readUiBooleanObject(uiNode, "listColumn"));
        Integer listColumnOrder = readUiInt(uiNode, "listColumnOrder");
        if (listColumnOrder == null) {
            node.putNull("listColumnOrder");
        } else {
            node.put("listColumnOrder", listColumnOrder);
        }
        node.put("displayMode", readUiText(uiNode, "displayMode", ""));
        node.put("defaultSort", readUiText(uiNode, "defaultSort", ""));
        node.put("defaultGroup", readUiText(uiNode, "defaultGroup", ""));
        Integer formColumns = readUiInt(uiNode, "formColumns");
        if (formColumns == null) {
            node.putNull("formColumns");
        } else {
            node.put("formColumns", formColumns);
        }
        return node;
    }

    private static ObjectNode layoutEntry(
            String concept,
            String fieldPath,
            String segment,
            String schemaType,
            String dslType,
            CompiledDomainType domainType,
            String referenceTarget,
            CompiledReferenceSemantics referenceSemantics,
            List<String> enumValues,
            JsonNode uiNode,
            CompiledPresentationMetadata ui
    ) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        JsonNode effectiveUiNode = firstPresentationNode(presentationNode(ui), uiNode);
        JsonNode domainUiNode = domainUiNode(domainType);
        node.put("concept", safe(concept));
        node.put("fieldPath", safe(fieldPath));
        node.put("label", readUiText(effectiveUiNode, domainUiNode, "label", labelFromPath(fieldPath)));
        node.put("shortLabel", readUiText(effectiveUiNode, "shortLabel", ""));
        node.put("description", readUiText(effectiveUiNode, "description", ""));
        node.put("widget", readUiText(effectiveUiNode, domainUiNode, "widget", defaultWidget(schemaType, dslType, domainType, referenceTarget, enumValues)));
        node.put("placeholder", readUiText(effectiveUiNode, domainUiNode, "placeholder", ""));
        node.put("helpText", readUiText(effectiveUiNode, domainUiNode, "helpText", ""));
        node.put("repeatedSection", "array".equals(normalize(schemaType)));
        Integer order = readUiInt(effectiveUiNode, "order");
        if (order == null) {
            node.putNull("order");
        } else {
            node.put("order", order);
        }
        if (schemaType != null && "array".equals(normalize(schemaType)) && fieldPath != null && !fieldPath.isBlank()) {
            node.put("collectionPath", safe(fieldPath));
        } else {
            node.put("collectionPath", "");
        }
        node.put("group", readUiText(effectiveUiNode, "group", ""));
        node.put("section", readUiText(effectiveUiNode, "section", ""));
        node.put("advanced", readUiBoolean(effectiveUiNode, "advanced"));
        node.put("deprecated", readUiBoolean(effectiveUiNode, "deprecated"));
        node.set("examples", toStringArray(readUiArray(effectiveUiNode, "examples")));
        node.put("visibleWhen", readUiText(effectiveUiNode, "visibleWhen", ""));
        node.put("enabledWhen", readUiText(effectiveUiNode, "enabledWhen", ""));
        node.put("readonlyWhen", readUiText(effectiveUiNode, "readonlyWhen", ""));
        node.put("requiredWhen", readUiText(effectiveUiNode, "requiredWhen", ""));
        node.put("pickerType", effectivePickerType(effectiveUiNode, referenceTarget));
        node.put("allowInlineCreate", effectiveAllowInlineCreate(effectiveUiNode, referenceSemantics));
        List<String> effectiveSearchFields = effectiveInteractionSearchFields(effectiveUiNode, referenceSemantics);
        List<String> effectivePreviewFields = explicitOrFallbackReferencePreviewFields(referenceSemantics);
        String effectiveDisplayField = explicitReferenceDisplayField(referenceSemantics);
        node.set("searchFields", toStringArray(effectiveSearchFields));
        node.put("filterPreset", effectiveFilterPreset(effectiveUiNode, referenceSemantics));
        node.put("displayTemplate", effectiveReferenceDisplayTemplate(referenceSemantics, effectiveDisplayField));
        node.set("pickerColumns", toStringArray(effectiveReferencePickerColumns(referenceSemantics, effectiveSearchFields, effectiveDisplayField)));
        node.put("previewCardTemplate", effectiveReferencePreviewCardTemplate(referenceSemantics, effectivePreviewFields, effectiveDisplayField));
        node.put("tab", readUiText(effectiveUiNode, "tab", ""));
        Integer column = readUiInt(effectiveUiNode, "column");
        if (column == null) {
            node.putNull("column");
        } else {
            node.put("column", column);
        }
        Integer columnSpan = readUiInt(effectiveUiNode, "columnSpan");
        if (columnSpan == null) {
            node.putNull("columnSpan");
        } else {
            node.put("columnSpan", columnSpan);
        }
        node.put("width", readUiText(effectiveUiNode, "width", ""));
        node.put("summaryCard", readUiBooleanObject(effectiveUiNode, "summaryCard"));
        node.put("listColumn", readUiBooleanObject(effectiveUiNode, "listColumn"));
        Integer listColumnOrder = readUiInt(effectiveUiNode, "listColumnOrder");
        if (listColumnOrder == null) {
            node.putNull("listColumnOrder");
        } else {
            node.put("listColumnOrder", listColumnOrder);
        }
        node.put("displayMode", readUiText(effectiveUiNode, "displayMode", ""));
        node.put("defaultSort", readUiText(effectiveUiNode, "defaultSort", ""));
        node.put("defaultGroup", readUiText(effectiveUiNode, "defaultGroup", ""));
        Integer formColumns = readUiInt(effectiveUiNode, "formColumns");
        if (formColumns == null) {
            node.putNull("formColumns");
        } else {
            node.put("formColumns", formColumns);
        }
        node.put("source", effectiveUiNode == null || effectiveUiNode.isMissingNode()
                ? ((domainUiNode == null || domainUiNode.isMissingNode()) ? "derived-default" : "domain-type-default")
                : "explicit-ui");
        node.put("segment", safe(segment));
        return node;
    }

    private static JsonNode readRawModel(Path modelPath) {
        if (modelPath == null) {
            return null;
        }
        try {
            if (!Files.exists(modelPath)) {
                return null;
            }
            return MAPPER.readTree(Files.readString(modelPath));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read raw model for compiled metadata: " + modelPath, exception);
        }
    }

    private static JsonNode rawEntityField(JsonNode rawEntityNode, String fieldName) {
        if (rawEntityNode == null || !rawEntityNode.has("fields") || !rawEntityNode.get("fields").isArray()) {
            return null;
        }
        for (JsonNode fieldNode : rawEntityNode.get("fields")) {
            if (fieldNode != null && normalize(fieldNode.path("name").asText()).equals(normalize(fieldName))) {
                return fieldNode;
            }
        }
        return null;
    }

    private static JsonNode rawProperty(JsonNode rawSchemaNode, String propertyName) {
        if (rawSchemaNode == null || !rawSchemaNode.has("properties") || !rawSchemaNode.get("properties").isObject()) {
            return null;
        }
        return rawSchemaNode.get("properties").get(propertyName);
    }

    private static JsonNode rawUi(JsonNode rawFieldNode) {
        if (rawFieldNode == null || !rawFieldNode.has("ui")) {
            return null;
        }
        JsonNode uiNode = rawFieldNode.get("ui");
        return uiNode != null && uiNode.isObject() ? uiNode : null;
    }

    private static JsonNode domainUiNode(CompiledDomainType domainType) {
        if (domainType == null || domainType.getUi() == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("label", safe(domainType.getUi().getLabel()));
        node.put("placeholder", safe(domainType.getUi().getPlaceholder()));
        node.put("helpText", safe(domainType.getUi().getHelpText()));
        node.put("widget", safe(domainType.getUi().getWidget()));
        return node;
    }

    private static JsonNode presentationNode(CompiledPresentationMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("label", safe(metadata.getLabel()));
        node.put("shortLabel", safe(metadata.getShortLabel()));
        node.put("description", safe(metadata.getDescription()));
        node.put("helpText", safe(metadata.getHelpText()));
        node.put("placeholder", safe(metadata.getPlaceholder()));
        node.put("group", safe(metadata.getGroup()));
        node.put("section", safe(metadata.getSection()));
        if (metadata.getOrder() == null) {
            node.putNull("order");
        } else {
            node.put("order", metadata.getOrder());
        }
        if (metadata.getAdvanced() == null) {
            node.putNull("advanced");
        } else {
            node.put("advanced", metadata.getAdvanced());
        }
        if (metadata.getDeprecated() == null) {
            node.putNull("deprecated");
        } else {
            node.put("deprecated", metadata.getDeprecated());
        }
        node.set("examples", toStringArray(metadata.getExamples()));
        node.put("widget", safe(metadata.getWidget()));
        node.put("visibleWhen", safe(metadata.getVisibleWhen()));
        node.put("enabledWhen", safe(metadata.getEnabledWhen()));
        node.put("readonlyWhen", safe(metadata.getReadonlyWhen()));
        node.put("requiredWhen", safe(metadata.getRequiredWhen()));
        node.put("pickerType", safe(metadata.getPickerType()));
        if (metadata.getAllowInlineCreate() == null) {
            node.putNull("allowInlineCreate");
        } else {
            node.put("allowInlineCreate", metadata.getAllowInlineCreate());
        }
        node.set("searchFields", toStringArray(metadata.getSearchFields()));
        node.put("filterPreset", safe(metadata.getFilterPreset()));
        node.put("tab", safe(metadata.getTab()));
        if (metadata.getColumn() == null) {
            node.putNull("column");
        } else {
            node.put("column", metadata.getColumn());
        }
        if (metadata.getColumnSpan() == null) {
            node.putNull("columnSpan");
        } else {
            node.put("columnSpan", metadata.getColumnSpan());
        }
        node.put("width", safe(metadata.getWidth()));
        if (metadata.getSummaryCard() == null) {
            node.putNull("summaryCard");
        } else {
            node.put("summaryCard", metadata.getSummaryCard());
        }
        if (metadata.getListColumn() == null) {
            node.putNull("listColumn");
        } else {
            node.put("listColumn", metadata.getListColumn());
        }
        if (metadata.getListColumnOrder() == null) {
            node.putNull("listColumnOrder");
        } else {
            node.put("listColumnOrder", metadata.getListColumnOrder());
        }
        if (metadata.getFormColumns() == null) {
            node.putNull("formColumns");
        } else {
            node.put("formColumns", metadata.getFormColumns());
        }
        node.put("displayMode", safe(metadata.getDisplayMode()));
        node.put("defaultSort", safe(metadata.getDefaultSort()));
        node.put("defaultGroup", safe(metadata.getDefaultGroup()));
        return node;
    }

    private static JsonNode firstPresentationNode(JsonNode primary, JsonNode fallback) {
        if (primary != null && !primary.isNull() && !primary.isMissingNode()) {
            return primary;
        }
        return fallback;
    }

    private static String readUiText(JsonNode uiNode, JsonNode fallbackUiNode, String key, String defaultValue) {
        if (uiNode != null && uiNode.has(key)) {
            String value = safe(uiNode.get(key).asText());
            return value.isBlank() ? defaultValue : value;
        }
        if (fallbackUiNode != null && fallbackUiNode.has(key)) {
            String value = safe(fallbackUiNode.get(key).asText());
            return value.isBlank() ? defaultValue : value;
        }
        return defaultValue;
    }

    private static String readUiText(JsonNode uiNode, String key, String defaultValue) {
        if (uiNode == null || !uiNode.has(key)) {
            return defaultValue;
        }
        String value = safe(uiNode.get(key).asText());
        return value.isBlank() ? defaultValue : value;
    }

    private static Integer readUiInt(JsonNode uiNode, String key) {
        if (uiNode == null || !uiNode.has(key) || !uiNode.get(key).canConvertToInt()) {
            return null;
        }
        return uiNode.get(key).intValue();
    }

    private static boolean readUiBoolean(JsonNode uiNode, String key) {
        if (uiNode == null || !uiNode.has(key) || uiNode.get(key).isNull()) {
            return false;
        }
        return uiNode.get(key).asBoolean(false);
    }

    private static Boolean readUiBooleanObject(JsonNode uiNode, String key) {
        if (uiNode == null || !uiNode.has(key) || uiNode.get(key).isNull()) {
            return null;
        }
        return uiNode.get(key).asBoolean(false);
    }

    private static List<String> readUiArray(JsonNode uiNode, String key) {
        if (uiNode == null || !uiNode.has(key) || !uiNode.get(key).isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : uiNode.get(key)) {
            if (item != null && !item.isNull()) {
                values.add(safe(item.asText()));
            }
        }
        return values;
    }

    private static String effectivePickerType(JsonNode uiNode, String referenceTarget) {
        String explicit = readUiText(uiNode, "pickerType", "");
        if (!explicit.isBlank()) {
            return explicit;
        }
        if (referenceTarget != null && !referenceTarget.isBlank()) {
            return "search";
        }
        return "";
    }

    private static String effectiveAllowInlineCreate(JsonNode uiNode, CompiledReferenceSemantics referenceSemantics) {
        if (uiNode != null && uiNode.has("allowInlineCreate") && !uiNode.get("allowInlineCreate").isNull()) {
            return uiNode.get("allowInlineCreate").asBoolean(false) ? "allow" : "deny";
        }
        if (referenceSemantics != null && referenceSemantics.getInlineCreatePolicy() != null
                && !referenceSemantics.getInlineCreatePolicy().isBlank()) {
            return referenceSemantics.getInlineCreatePolicy();
        }
        return "";
    }

    private static List<String> effectiveInteractionSearchFields(
            JsonNode uiNode,
            CompiledReferenceSemantics referenceSemantics
    ) {
        List<String> explicit = readUiArray(uiNode, "searchFields");
        if (!explicit.isEmpty()) {
            return explicit;
        }
        if (referenceSemantics != null && referenceSemantics.getSearchFields() != null) {
            return referenceSemantics.getSearchFields();
        }
        return List.of();
    }

    private static String effectiveFilterPreset(JsonNode uiNode, CompiledReferenceSemantics referenceSemantics) {
        String explicit = readUiText(uiNode, "filterPreset", "");
        if (!explicit.isBlank()) {
            return explicit;
        }
        return effectiveReferenceDefaultFilter(referenceSemantics);
    }

    private static String explicitReferenceDisplayField(CompiledReferenceSemantics referenceSemantics) {
        if (referenceSemantics == null || referenceSemantics.getDisplayField() == null) {
            return "";
        }
        return referenceSemantics.getDisplayField();
    }

    private static List<String> explicitOrFallbackReferencePreviewFields(CompiledReferenceSemantics referenceSemantics) {
        if (referenceSemantics == null) {
            return List.of();
        }
        if (referenceSemantics.getPreviewFields() != null && !referenceSemantics.getPreviewFields().isEmpty()) {
            return referenceSemantics.getPreviewFields();
        }
        if (referenceSemantics.getDisplayField() != null && !referenceSemantics.getDisplayField().isBlank()) {
            return List.of(referenceSemantics.getDisplayField());
        }
        return List.of();
    }

    private static List<String> effectiveReferencePickerColumns(
            CompiledReferenceSemantics referenceSemantics,
            List<String> searchFields,
            String displayField
    ) {
        List<String> configured = referenceSemantics == null ? List.of() : referenceSemantics.getPickerColumns();
        if (!configured.isEmpty()) {
            return normalizedUniqueStrings(configured);
        }
        List<String> inferred = new ArrayList<>();
        if (searchFields != null && !searchFields.isEmpty()) {
            inferred.addAll(searchFields);
        }
        if (displayField != null && !displayField.isBlank()) {
            inferred.add(displayField);
        }
        return normalizedUniqueStrings(inferred);
    }

    private static String effectiveReferenceDisplayTemplate(
            CompiledReferenceSemantics referenceSemantics,
            String displayField
    ) {
        if (referenceSemantics != null
                && referenceSemantics.getDisplayTemplate() != null
                && !referenceSemantics.getDisplayTemplate().isBlank()) {
            return referenceSemantics.getDisplayTemplate();
        }
        if (displayField != null && !displayField.isBlank()) {
            return "{{" + displayField + "}}";
        }
        return "";
    }

    private static String effectiveReferencePreviewCardTemplate(
            CompiledReferenceSemantics referenceSemantics,
            List<String> previewFields,
            String displayField
    ) {
        if (referenceSemantics != null
                && referenceSemantics.getPreviewCardTemplate() != null
                && !referenceSemantics.getPreviewCardTemplate().isBlank()) {
            return referenceSemantics.getPreviewCardTemplate();
        }
        List<String> fields = previewFields == null || previewFields.isEmpty()
                ? (displayField == null || displayField.isBlank() ? List.of() : List.of(displayField))
                : previewFields;
        if (fields.isEmpty()) {
            return "";
        }
        return fields.stream()
                .map(field -> "{{" + field + "}}")
                .collect(Collectors.joining(" | "));
    }

    private static String effectiveReferenceDefaultFilter(CompiledReferenceSemantics referenceSemantics) {
        if (referenceSemantics == null || referenceSemantics.getDefaultFilter() == null) {
            return "";
        }
        return referenceSemantics.getDefaultFilter();
    }

    private static String conceptLabel(CompiledConcept entity) {
        if (entity == null) {
            return "";
        }
        if (entity.getUi() != null && entity.getUi().getLabel() != null && !entity.getUi().getLabel().isBlank()) {
            return entity.getUi().getLabel();
        }
        return humanizeSegment(entity.getName());
    }

    private static String conceptUiText(CompiledPresentationMetadata metadata, String key, String defaultValue) {
        return readUiText(presentationNode(metadata), key, defaultValue);
    }

    private static String inferSchemaType(String dslType, CompiledSchema schema) {
        if (schema != null && schema.getType() != null && !schema.getType().isBlank()) {
            return schema.getType();
        }
        if (dslType == null || dslType.isBlank()) {
            return "";
        }
        return switch (dslType.trim().toLowerCase(Locale.ROOT)) {
            case "string", "uuid", "enum", "date", "datetime", "reference" -> "string";
            case "int", "integer", "long" -> "integer";
            case "boolean" -> "boolean";
            case "number", "decimal", "double", "float" -> "number";
            case "object" -> "object";
            case "array" -> "array";
            default -> dslType.trim();
        };
    }

    private static String inferJavaType(CompiledSchema schema) {
        if (schema == null || schema.getType() == null || schema.getType().isBlank()) {
            return "";
        }
        return switch (schema.getType().trim().toLowerCase(Locale.ROOT)) {
            case "string" -> "String";
            case "integer" -> "Integer";
            case "number" -> "Double";
            case "boolean" -> "Boolean";
            case "object" -> "Map<String,Object>";
            case "array" -> "List<Object>";
            default -> schema.getType().trim();
        };
    }

    private static boolean isNestedSchema(CompiledSchema schema) {
        if (schema == null) {
            return false;
        }
        return !schema.getProperties().isEmpty() || schema.getItems() != null;
    }

    private static String defaultWidget(
            String schemaType,
            String dslType,
            CompiledDomainType domainType,
            String referenceTarget,
            List<String> enumValues
    ) {
        if (domainType != null && domainType.getUi() != null
                && domainType.getUi().getWidget() != null
                && !domainType.getUi().getWidget().isBlank()) {
            return domainType.getUi().getWidget();
        }
        if (referenceTarget != null && !referenceTarget.isBlank()) {
            return "select";
        }
        if (enumValues != null && !enumValues.isEmpty()) {
            return "select";
        }
        String normalizedType = normalize(firstNonBlank(schemaType, dslType));
        return switch (normalizedType) {
            case "boolean" -> "checkbox";
            case "integer", "long", "number", "decimal", "double", "float" -> "number";
            case "datetime" -> "datetime-local";
            case "date" -> "date";
            case "object" -> "group";
            case "array" -> "list";
            default -> "text";
        };
    }

    private static String labelFromPath(String fieldPath) {
        if (fieldPath == null || fieldPath.isBlank()) {
            return "";
        }
        String[] segments = fieldPath.replace("[]", " items").split("\\.");
        List<String> words = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            words.add(humanizeSegment(segment));
        }
        return String.join(" ", words).trim();
    }

    private static String humanizeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        char[] chars = segment.toCharArray();
        for (int index = 0; index < chars.length; index++) {
            char current = chars[index];
            if (index > 0 && Character.isUpperCase(current) && Character.isLowerCase(chars[index - 1])) {
                out.append(' ');
            }
            if (current == '_' || current == '-') {
                out.append(' ');
            } else {
                out.append(current);
            }
        }
        String compact = out.toString().replaceAll("\\s+", " ").trim();
        if (compact.isBlank()) {
            return "";
        }
        String[] tokens = compact.split(" ");
        List<String> titleCase = new ArrayList<>();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            titleCase.add(token.substring(0, 1).toUpperCase(Locale.ROOT)
                    + token.substring(1).toLowerCase(Locale.ROOT));
        }
        return String.join(" ", titleCase);
    }

    private static ArrayNode toStringArray(List<String> values) {
        ArrayNode node = JsonNodeFactory.instance.arrayNode();
        for (String value : sortStrings(values)) {
            node.add(safe(value));
        }
        return node;
    }

    private static ObjectNode toStringMap(Map<String, String> values) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (values == null || values.isEmpty()) {
            return node;
        }
        Map<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(values);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            node.put(entry.getKey(), safe(entry.getValue()));
        }
        return node;
    }

    private static List<String> sortStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    private static ArrayNode toArray(List<ObjectNode> nodes) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (ObjectNode node : nodes) {
            node.remove("orderKey");
            array.add(node);
        }
        return array;
    }

    private static List<String> sortedFieldPaths(CompiledConcept entity) {
        List<String> names = new ArrayList<>();
        for (CompiledField field : entity.getFields()) {
            names.add(field.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static String initialState(CompiledLifecycle lifecycle) {
        if (lifecycle == null) {
            return "";
        }
        for (CompiledStateMachineState state : lifecycle.getStates()) {
            if (state != null && state.isInitial()) {
                return state.getValue();
            }
        }
        return "";
    }

    private static List<String> terminalStates(CompiledLifecycle lifecycle) {
        if (lifecycle == null || lifecycle.getStates().isEmpty()) {
            return List.of();
        }
        List<String> terminalStates = new ArrayList<>();
        for (CompiledStateMachineState state : lifecycle.getStates()) {
            if (state != null && state.isTerminal()) {
                terminalStates.add(state.getValue());
            }
        }
        return terminalStates;
    }

    private static List<String> sortedReferenceFields(CompiledConcept entity) {
        List<String> names = new ArrayList<>();
        for (CompiledField field : entity.getFields()) {
            if (field.getReferenceTarget() != null && !field.getReferenceTarget().isBlank()) {
                names.add(field.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static List<String> sortedEnumFields(CompiledConcept entity) {
        List<String> names = new ArrayList<>();
        for (CompiledField field : entity.getFields()) {
            if (!field.getEnumValues().isEmpty()) {
                names.add(field.getName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static List<String> sortedInvariantRefs(CompiledConcept entity) {
        List<String> refs = new ArrayList<>();
        for (CompiledInvariant invariant : entity.getInvariants()) {
            refs.add(invariant.getRef());
        }
        refs.sort(String.CASE_INSENSITIVE_ORDER);
        return refs;
    }

    private static List<CompiledConcept> sortedConcepts(CompiledModel model) {
        List<CompiledConcept> concepts = new ArrayList<>(model.getConcepts());
        concepts.sort(Comparator.comparing(concept -> normalize(concept.getName())));
        return concepts;
    }

    private static List<CompiledField> sortedFields(CompiledConcept entity) {
        List<CompiledField> fields = new ArrayList<>(entity.getFields());
        fields.sort(Comparator.comparing(field -> normalize(field.getName())));
        return fields;
    }

    private static List<CompiledInvariant> sortedInvariants(CompiledConcept entity) {
        List<CompiledInvariant> invariants = new ArrayList<>(entity.getInvariants());
        invariants.sort(Comparator.comparing(invariant -> normalize(invariant.getRef())));
        return invariants;
    }

    private static List<CompiledEnumOption> sortedEnumOptions(List<CompiledEnumOption> enumOptions) {
        List<CompiledEnumOption> options = new ArrayList<>(enumOptions);
        options.sort(Comparator
                .comparing((CompiledEnumOption option) -> option.getOrder() == null ? Integer.MAX_VALUE : option.getOrder())
                .thenComparing(option -> normalize(option.getValue())));
        return options;
    }

    private static String inferReferenceDisplayField(CompiledConcept targetEntity) {
        if (targetEntity == null) {
            return "";
        }
        for (String preferred : List.of("displayName", "fullName", "name", "title", "lastName")) {
            for (CompiledField field : sortedFields(targetEntity)) {
                if (normalize(preferred).equals(normalize(field.getName()))) {
                    return field.getName();
                }
            }
        }
        for (CompiledField field : sortedFields(targetEntity)) {
            String normalizedType = normalize(field.getDslType());
            if (!field.isId()
                    && !"reference".equals(normalizedType)
                    && !"object".equals(normalizedType)
                    && !"array".equals(normalizedType)) {
                return field.getName();
            }
        }
        for (CompiledField field : sortedFields(targetEntity)) {
            if (field.isId()) {
                return field.getName();
            }
        }
        return targetEntity.getFields().isEmpty() ? "" : targetEntity.getFields().get(0).getName();
    }

    private static List<String> effectiveReferenceSearchFields(
            CompiledReferenceSemantics referenceSemantics,
            CompiledConcept targetEntity,
            String displayField
    ) {
        List<String> configured = referenceSemantics == null ? List.of() : referenceSemantics.getSearchFields();
        if (!configured.isEmpty()) {
            return normalizedUniqueStrings(configured);
        }
        List<String> inferred = new ArrayList<>();
        if (displayField != null && !displayField.isBlank()) {
            inferred.add(displayField);
        }
        if (targetEntity != null) {
            for (CompiledField field : sortedFields(targetEntity)) {
                if (field.isUnique() && !field.isId()) {
                    inferred.add(field.getName());
                }
            }
            for (CompiledField field : sortedFields(targetEntity)) {
                if (field.isId()) {
                    inferred.add(field.getName());
                    break;
                }
            }
        }
        return normalizedUniqueStrings(inferred);
    }

    private static List<String> effectiveReferencePreviewFields(
            CompiledReferenceSemantics referenceSemantics,
            CompiledConcept targetEntity,
            String displayField
    ) {
        List<String> configured = referenceSemantics == null ? List.of() : referenceSemantics.getPreviewFields();
        if (!configured.isEmpty()) {
            return normalizedUniqueStrings(configured);
        }
        List<String> inferred = new ArrayList<>();
        if (displayField != null && !displayField.isBlank()) {
            inferred.add(displayField);
        }
        if (targetEntity != null) {
            for (CompiledField field : sortedFields(targetEntity)) {
                if (!field.isId() && inferred.size() < 3) {
                    inferred.add(field.getName());
                }
            }
        }
        return normalizedUniqueStrings(inferred);
    }

    private static List<String> normalizedUniqueStrings(List<String> values) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                ordered.add(value);
            }
        }
        return new ArrayList<>(ordered);
    }

    private static List<CompiledFlow> sortedFlows(CompiledModel model) {
        List<CompiledFlow> flows = new ArrayList<>(model.getFlows());
        flows.sort(Comparator.comparing(flow -> normalize(flow.getName())));
        return flows;
    }

    private static List<CompiledProcedure> sortedProcedures(CompiledModel model) {
        List<CompiledProcedure> procedures = new ArrayList<>(model.getProcedures());
        procedures.sort(Comparator.comparing(procedure -> normalize(procedure.name())));
        return procedures;
    }

    private static List<CompiledPanel> sortedPanels(CompiledModel model) {
        List<CompiledPanel> panels = new ArrayList<>(model.getPanels());
        panels.sort(Comparator.comparing(panel -> normalize(panel.name())));
        return panels;
    }

    private static int countProcedureSteps(List<CompiledProcedureStep> steps) {
        int count = 0;
        for (CompiledProcedureStep step : steps) {
            count++;
            count += countProcedureSteps(step.thenSteps());
            count += countProcedureSteps(step.elseSteps());
            count += countProcedureSteps(step.steps());
        }
        return count;
    }

    private static void collectProcedureStepMetadata(
            List<CompiledProcedureStep> steps,
            LinkedHashSet<String> stepTypes,
            LinkedHashSet<String> concepts,
            LinkedHashSet<String> queries,
            LinkedHashSet<String> calledProcedures
    ) {
        for (CompiledProcedureStep step : steps) {
            if (step.type() != null && !step.type().isBlank()) {
                stepTypes.add(step.type());
            }
            if (step.concept() != null && !step.concept().isBlank()) {
                concepts.add(step.concept());
            }
            if (step.query() != null && !step.query().isBlank()) {
                queries.add(step.query());
            }
            if (step.procedure() != null && !step.procedure().isBlank()) {
                calledProcedures.add(step.procedure());
            }
            collectProcedureStepMetadata(step.thenSteps(), stepTypes, concepts, queries, calledProcedures);
            collectProcedureStepMetadata(step.elseSteps(), stepTypes, concepts, queries, calledProcedures);
            collectProcedureStepMetadata(step.steps(), stepTypes, concepts, queries, calledProcedures);
        }
    }

    private static ArrayNode toPanelDataSourceSummaries(List<CompiledPanelDataSource> dataSources) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<CompiledPanelDataSource> sorted = new ArrayList<>(dataSources);
        sorted.sort(Comparator.comparing(dataSource -> normalize(dataSource.name())));
        for (CompiledPanelDataSource dataSource : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(dataSource.name()));
            node.put("concept", safe(dataSource.concept()));
            node.put("query", safe(dataSource.query()));
            node.put("procedure", safe(dataSource.procedure()));
            array.add(node);
        }
        return array;
    }

    private static ArrayNode toPanelActionSummaries(List<CompiledPanelAction> actions) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        List<CompiledPanelAction> sorted = new ArrayList<>(actions);
        sorted.sort(Comparator.comparing(action -> normalize(action.name())));
        for (CompiledPanelAction action : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(action.name()));
            node.put("label", safe(action.label()));
            node.put("binding", safe(action.binding()));
            node.put("concept", safe(action.concept()));
            node.put("operation", safe(action.operation()));
            node.put("procedure", safe(action.procedure()));
            node.put("flow", safe(action.flow()));
            node.put("visibleWhen", safe(action.visibleWhen()));
            node.put("enabledWhen", safe(action.enabledWhen()));
            node.set("permissionRequirements", toStringArray(action.permissionRequirements()));
            array.add(node);
        }
        return array;
    }

    private static List<String> panelFields(CompiledPanel panel) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (panel.layout() != null) {
            collectPanelLayoutFields(panel.layout(), fields);
        }
        for (CompiledPanelFieldBinding binding : panel.fieldBindings()) {
            if (binding.field() != null && !binding.field().isBlank()) {
                fields.add(binding.field());
            }
        }
        return new ArrayList<>(fields);
    }

    private static void collectPanelLayoutFields(CompiledPanelLayout layout, LinkedHashSet<String> fields) {
        for (String field : layout.fields()) {
            if (field != null && !field.isBlank()) {
                fields.add(field);
            }
        }
        for (CompiledPanelLayout child : layout.children()) {
            collectPanelLayoutFields(child, fields);
        }
    }

    private static List<CompiledEvent> sortedEvents(CompiledModel model) {
        List<CompiledEvent> events = new ArrayList<>(model.getEvents());
        events.sort(Comparator.comparing(event -> normalize(event.getName())));
        return events;
    }

    private static List<CompiledOrchestration> sortedOrchestrationRules(CompiledModel model) {
        List<CompiledOrchestration> rules = new ArrayList<>(model.getOrchestrationRules());
        rules.sort(Comparator.comparing(rule -> normalize(rule.getName())));
        return rules;
    }

    private static List<CompiledDomainType> sortedDomainTypes(CompiledModel model) {
        List<CompiledDomainType> domainTypes = new ArrayList<>(model.getDomainTypes());
        domainTypes.sort(Comparator.comparing(domainType -> normalize(domainType.getName())));
        return domainTypes;
    }

    private static Map<String, CompiledDomainType> indexDomainTypes(CompiledModel model) {
        Map<String, CompiledDomainType> indexed = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (CompiledDomainType domainType : model.getDomainTypes()) {
            indexed.put(normalize(domainType.getName()), domainType);
        }
        return indexed;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static long numericOrMax(ObjectNode node, String property) {
        JsonNode value = node.get(property);
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            return Long.MAX_VALUE;
        }
        return value.longValue();
    }

    private static String text(ObjectNode node, String property) {
        JsonNode value = node.get(property);
        return value == null ? "" : safe(value.asText());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    private static String pad(int value) {
        return String.format(Locale.ROOT, "%04d", value);
    }

    private record RawModelIndex(Map<String, JsonNode> entitiesByName) {
        private static RawModelIndex from(JsonNode rawModelRoot) {
            if (rawModelRoot == null || rawModelRoot.isMissingNode() || rawModelRoot.isNull()) {
                return new RawModelIndex(Map.of());
            }
            JsonNode entitiesNode = rawModelRoot.has("entities") ? rawModelRoot.get("entities") : rawModelRoot.get("concepts");
            if (entitiesNode == null || !entitiesNode.isArray()) {
                return new RawModelIndex(Map.of());
            }
            Map<String, JsonNode> entitiesByName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (JsonNode entityNode : entitiesNode) {
                if (entityNode == null || !entityNode.isObject()) {
                    continue;
                }
                String entityName = safe(entityNode.path("name").asText());
                if (!entityName.isBlank()) {
                    entitiesByName.put(entityName, entityNode);
                }
            }
            return new RawModelIndex(entitiesByName);
        }

        private JsonNode entity(String entityName) {
            return entitiesByName.get(entityName);
        }
    }
}
