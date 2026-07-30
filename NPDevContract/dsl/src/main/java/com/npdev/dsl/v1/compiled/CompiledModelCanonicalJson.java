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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class CompiledModelCanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private CompiledModelCanonicalJson() {
    }

    public static String toJson(CompiledModel model) {
        ObjectNode canonical = toCanonicalObject(model);
        try {
            return MAPPER.writeValueAsString(canonical) + "\n";
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize compiled model as canonical JSON", exception);
        }
    }

    public static void write(Path outFile, CompiledModel model) throws IOException {
        Path parent = outFile.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(outFile, toJson(model));
    }

    private static ObjectNode toCanonicalObject(CompiledModel model) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("dslVersion", safe(model.getDslVersion()));
        root.put("namespace", safe(model.getNamespace()));
        root.put("version", safe(model.getVersion()));

        root.set("domainTypes", toDomainTypes(model));
        root.set("concepts", toConcepts(model));
        root.set("capabilities", toCapabilities(model));
        root.set("bindings", toBindings(model));
        root.set("events", toEvents(model));
        root.set("flows", toFlows(model));
        root.set("orchestrationRules", toOrchestrationRules(model));
        root.set("queries", toQueries(model));
        root.set("ruleProfiles", toRuleProfiles(model));
        root.set("procedures", toProcedures(model));
        root.set("panels", toPanels(model));
        root.set("guidePages", toGuidePages(model));
        root.set("aggregates", toAggregates(model));
        root.set("autoPanels", toAutoPanels(model));
        root.set("documents", toDocuments(model));
        root.set("externalAi", toExternalAi(model));
        return root;
    }

    private static ObjectNode toExternalAi(CompiledModel model) {
        CompiledExternalAi externalAi = model.getExternalAi();
        if (externalAi == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("egress", safe(externalAi.getEgress()));
        node.set("vendors", toStringArray(externalAi.getVendors()));
        return node;
    }

    private static ArrayNode toDocuments(CompiledModel model) {
        ArrayNode documents = JsonNodeFactory.instance.arrayNode();
        List<CompiledDocument> sorted = new ArrayList<>(model.getDocuments());
        sorted.sort(Comparator.comparing(document -> normalize(document.name())));
        for (CompiledDocument document : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(document.name()));
            node.put("concept", safe(document.concept()));
            node.put("title", safe(document.title()));
            node.put("pageSize", safe(document.pageSize()));
            if (document.marginMm() == null) {
                node.putNull("marginMm");
            } else {
                node.put("marginMm", document.marginMm());
            }
            node.set("metadata", toObjectMap(document.metadata()));
            documents.add(node);
        }
        return documents;
    }

    private static ArrayNode toAutoPanels(CompiledModel model) {
        ArrayNode autoPanels = JsonNodeFactory.instance.arrayNode();
        List<CompiledAutoPanel> sorted = new ArrayList<>(model.getAutoPanels());
        sorted.sort(Comparator.comparing(autoPanel -> normalize(autoPanelSortKey(autoPanel))));
        for (CompiledAutoPanel autoPanel : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(autoPanel.name()));
            node.put("concept", safe(autoPanel.concept()));
            node.put("aggregate", safe(autoPanel.aggregate()));
            node.put("route", safe(autoPanel.route()));
            ArrayNode surfaces = JsonNodeFactory.instance.arrayNode();
            autoPanel.surfaces().forEach(surfaces::add);
            node.set("surfaces", surfaces);
            node.set("selection", toAutoPanelSurface(autoPanel.selection()));
            node.set("detail", toAutoPanelSurface(autoPanel.detail()));
            node.set("transaction", toAutoPanelSurface(autoPanel.transaction()));
            node.set("prompt", toAutoPanelSurface(autoPanel.prompt()));
            node.set("metadata", toObjectMap(autoPanel.metadata()));
            autoPanels.add(node);
        }
        return autoPanels;
    }

    private static String autoPanelSortKey(CompiledAutoPanel autoPanel) {
        if (autoPanel.name() != null && !autoPanel.name().isBlank()) {
            return autoPanel.name();
        }
        if (autoPanel.concept() != null && !autoPanel.concept().isBlank()) {
            return autoPanel.concept();
        }
        return autoPanel.aggregate() == null ? "" : autoPanel.aggregate();
    }

    private static JsonNode toAutoPanelSurface(CompiledAutoPanelSurface surface) {
        if (surface == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ArrayNode filters = JsonNodeFactory.instance.arrayNode();
        surface.filters().forEach(filters::add);
        node.set("filters", filters);
        ArrayNode columns = JsonNodeFactory.instance.arrayNode();
        surface.columns().forEach(columns::add);
        node.set("columns", columns);
        ArrayNode fields = JsonNodeFactory.instance.arrayNode();
        surface.fields().forEach(fields::add);
        node.set("fields", fields);
        ArrayNode computed = JsonNodeFactory.instance.arrayNode();
        for (CompiledAutoPanelComputed c : surface.computed()) {
            ObjectNode cn = JsonNodeFactory.instance.objectNode();
            cn.put("col", safe(c.col()));
            cn.put("expr", safe(c.expr()));
            computed.add(cn);
        }
        node.set("computed", computed);
        node.put("labelField", safe(surface.labelField()));
        node.set("metadata", toObjectMap(surface.metadata()));
        return node;
    }

    private static ArrayNode toAggregates(CompiledModel model) {
        ArrayNode aggregates = JsonNodeFactory.instance.arrayNode();
        List<CompiledAggregate> sorted = new ArrayList<>(model.getAggregates());
        sorted.sort(Comparator.comparing(aggregate -> normalize(aggregate.name())));
        for (CompiledAggregate aggregate : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(aggregate.name()));
            node.put("root", safe(aggregate.root()));
            node.set("collections", toAggregateCollections(aggregate.collections()));
            node.put("onCommit", safe(aggregate.onCommit()));
            node.set("metadata", toObjectMap(aggregate.metadata()));
            aggregates.add(node);
        }
        return aggregates;
    }

    private static ArrayNode toAggregateCollections(List<CompiledAggregateCollection> collections) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (CompiledAggregateCollection collection : collections) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(collection.name()));
            node.put("concept", safe(collection.concept()));
            node.put("via", safe(collection.via()));
            node.put("childField", safe(collection.childField()));
            node.put("ownership", safe(collection.ownership()));
            node.put("orderBy", safe(collection.orderBy()));
            node.set("collections", toAggregateCollections(collection.collections()));
            node.set("metadata", toObjectMap(collection.metadata()));
            array.add(node);
        }
        return array;
    }

    private static ArrayNode toConcepts(CompiledModel model) {
        ArrayNode concepts = JsonNodeFactory.instance.arrayNode();
        List<CompiledConcept> sorted = new ArrayList<>(model.getConcepts());
        sorted.sort(Comparator.comparing(concept -> normalize(concept.getName())));
        for (CompiledConcept concept : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(concept.getName()));
            node.put("className", safe(concept.getClassName()));
            node.put("tableName", safe(concept.getTableName()));
            node.put("truthLevel", safe(concept.getTruthLevel()));
            node.put("module", safe(concept.getModule()));
            node.put("renamedFrom", safe(concept.getRenamedFrom()));
            node.set("ui", toPresentationMetadata(concept.getUi()));

            List<CompiledField> fields = new ArrayList<>(concept.getFields());
            fields.sort(Comparator.comparing(field -> normalize(field.getName())));
            ArrayNode fieldsNode = JsonNodeFactory.instance.arrayNode();
            for (CompiledField field : fields) {
                ObjectNode fieldNode = JsonNodeFactory.instance.objectNode();
                fieldNode.put("name", safe(field.getName()));
                fieldNode.put("dslType", safe(field.getDslType()));
                fieldNode.put("javaType", safe(field.getJavaType()));
                fieldNode.put("id", field.isId());
                fieldNode.put("required", field.isRequired());
                fieldNode.put("unique", field.isUnique());
                fieldNode.put("sensitive", field.isSensitive());
                fieldNode.set("enumValues", toStringArray(field.getEnumValues()));
                fieldNode.set("enumOptions", toEnumOptions(field.getEnumOptions()));
                fieldNode.put("referenceTarget", safe(field.getReferenceTarget()));
                fieldNode.put("connectable", safe(field.getConnectable()));
                fieldNode.put("renamedFrom", safe(field.getRenamedFrom()));
                fieldNode.set("referenceSemantics", toReferenceSemantics(field.getReferenceSemantics()));
                fieldNode.put("domainType", safe(field.getDomainType()));
                fieldNode.set("schema", toSchema(field.getSchema()));
                fieldNode.set("ui", toPresentationMetadata(field.getUi()));
                fieldNode.set("file", toFileMetadata(field.getFile()));
                fieldsNode.add(fieldNode);
            }
            node.set("fields", fieldsNode);

            List<String> expressionInvariants = new ArrayList<>(concept.getExpressionInvariants());
            expressionInvariants.sort(String.CASE_INSENSITIVE_ORDER);
            ArrayNode expressionInvariantsNode = JsonNodeFactory.instance.arrayNode();
            for (String expressionInvariant : expressionInvariants) {
                expressionInvariantsNode.add(safe(expressionInvariant));
            }
            node.set("expressionInvariants", expressionInvariantsNode);

            List<CompiledInvariant> invariants = new ArrayList<>(concept.getInvariants());
            invariants.sort(Comparator.comparing(invariant -> normalize(invariant.getRef())));
            ArrayNode invariantsNode = JsonNodeFactory.instance.arrayNode();
            for (CompiledInvariant invariant : invariants) {
                ObjectNode invariantNode = JsonNodeFactory.instance.objectNode();
                invariantNode.put("ref", safe(invariant.getRef()));
                invariantNode.put("type", safe(invariant.getType()));
                invariantNode.put("field", safe(invariant.getField()));
                invariantNode.put("expression", safe(invariant.getExpression()));
                invariantNode.set("fields", toStringArray(invariant.getFields()));
                invariantsNode.add(invariantNode);
            }
            node.set("invariants", invariantsNode);
            node.set("lifecycle", toLifecycle(concept.getLifecycle()));
            // LNCH-1 P0.2 (found by the reflective CanonicalJsonRoundTripCompletenessTest ratchet):
            // indexes was neither written here nor read by CompiledModelCanonicalJsonReader, so a
            // concept's author-declared secondary indexes (LNCH-6) silently vanished across the
            // canonical-JSON round trip -- the exact bug class the comments elsewhere in this file
            // reference "LNCH-6's indexes" as a prior instance of (that prior fix evidently did not
            // cover this writer/reader pair). Needed by LNCH-1 Phase 6's model-vs-previous-canonical
            // diffing, which must see index changes, not just DDL emitted at generation time.
            node.set("indexes", toIndexes(concept.getIndexes()));
            node.set("access", toConceptAccess(concept.getAccess()));
            concepts.add(node);
        }
        return concepts;
    }

    private static ArrayNode toIndexes(List<CompiledIndex> indexes) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        if (indexes == null) {
            return array;
        }
        for (CompiledIndex index : indexes) {
            if (index == null) {
                continue;
            }
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(index.getName()));
            node.set("fields", toStringArray(index.getFields()));
            node.put("unique", index.isUnique());
            array.add(node);
        }
        return array;
    }

    private static ArrayNode toDomainTypes(CompiledModel model) {
        ArrayNode domainTypes = JsonNodeFactory.instance.arrayNode();
        List<CompiledDomainType> sorted = new ArrayList<>(model.getDomainTypes());
        sorted.sort(Comparator.comparing(domainType -> normalize(domainType.getName())));
        for (CompiledDomainType domainType : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(domainType.getName()));
            node.put("baseType", safe(domainType.getBaseType()));
            node.put("javaType", safe(domainType.getJavaType()));
            node.put("formatHint", safe(domainType.getFormatHint()));
            node.set("normalizationRules", toStringArray(domainType.getNormalizationRules()));
            node.set("examples", toStringArray(domainType.getExamples()));
            node.set("validationSchema", toSchema(domainType.getValidationSchema()));
            node.set("ui", toDomainTypeUi(domainType.getUi()));
            domainTypes.add(node);
        }
        return domainTypes;
    }

    private static ObjectNode toDomainTypeUi(CompiledDomainTypeUi ui) {
        if (ui == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("label", safe(ui.getLabel()));
        node.put("placeholder", safe(ui.getPlaceholder()));
        node.put("helpText", safe(ui.getHelpText()));
        node.put("widget", safe(ui.getWidget()));
        return node;
    }

    private static ObjectNode toPresentationMetadata(CompiledPresentationMetadata metadata) {
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
        // LNCH-1 P0.2 (found by the reflective CanonicalJsonRoundTripCompletenessTest ratchet):
        // showInDefaultWebUi was read by CompiledModelCanonicalJsonReader#toPresentationMetadata
        // but never written here, silently dropping a field/concept's default-web-UI visibility
        // override across the canonical-JSON round trip -- same bug class as LNCH-6's indexes.
        if (metadata.getShowInDefaultWebUi() == null) {
            node.putNull("showInDefaultWebUi");
        } else {
            node.put("showInDefaultWebUi", metadata.getShowInDefaultWebUi());
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
        node.put("formPresentation", safe(metadata.getFormPresentation()));
        node.put("defaultSort", safe(metadata.getDefaultSort()));
        node.put("defaultGroup", safe(metadata.getDefaultGroup()));
        node.put("imageField", safe(metadata.getImageField()));
        node.put("customWidgetRef", safe(metadata.getCustomWidgetRef()));
        return node;
    }

    private static ArrayNode toCapabilities(CompiledModel model) {
        ArrayNode capabilities = JsonNodeFactory.instance.arrayNode();
        List<CompiledCapability> sorted = new ArrayList<>(model.getCapabilities());
        sorted.sort(Comparator.comparing(capability -> normalize(capability.getName())));
        for (CompiledCapability capability : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(capability.getName()));
            node.put("type", safe(capability.getType()));

            List<CompiledCapabilityOperation> operations = new ArrayList<>(capability.getOperations());
            operations.sort(Comparator.comparing(operation -> normalize(operation.getName())));
            ArrayNode operationsNode = JsonNodeFactory.instance.arrayNode();
            for (CompiledCapabilityOperation operation : operations) {
                ObjectNode operationNode = JsonNodeFactory.instance.objectNode();
                operationNode.put("name", safe(operation.getName()));
                operationNode.set("input", toStringArray(operation.getInput()));
                operationNode.set("output", toStringArray(operation.getOutput()));
                operationNode.set("inputSchema", toSchema(operation.getInputSchema()));
                operationNode.set("outputSchema", toSchema(operation.getOutputSchema()));
                operationNode.set("executionPolicy", toExecutionPolicy(operation.getExecutionPolicy()));
                operationsNode.add(operationNode);
            }
            node.set("operations", operationsNode);
            capabilities.add(node);
        }
        return capabilities;
    }

    private static ArrayNode toBindings(CompiledModel model) {
        ArrayNode bindings = JsonNodeFactory.instance.arrayNode();
        List<CompiledCapabilityBinding> sorted = new ArrayList<>(model.getBindings());
        sorted.sort(Comparator
                .comparing((CompiledCapabilityBinding binding) -> normalize(binding.getCapability()))
                .thenComparing(binding -> normalize(binding.getAdapter())));
        for (CompiledCapabilityBinding binding : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("capability", safe(binding.getCapability()));
            node.put("adapter", safe(binding.getAdapter()));
            bindings.add(node);
        }
        return bindings;
    }

    private static ArrayNode toEvents(CompiledModel model) {
        ArrayNode events = JsonNodeFactory.instance.arrayNode();
        List<CompiledEvent> sorted = new ArrayList<>(model.getEvents());
        sorted.sort(Comparator.comparing(event -> normalize(event.getName())));
        for (CompiledEvent event : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(event.getName()));
            node.put("conceptName", safe(event.getConceptName()));
            node.put("triggerMode", safe(event.getTriggerMode()));

            List<CompiledEventField> payloadFields = new ArrayList<>(event.getPayloadFields());
            payloadFields.sort(Comparator.comparing(field -> normalize(field.getName())));
            ArrayNode payloadNode = JsonNodeFactory.instance.arrayNode();
            for (CompiledEventField payloadField : payloadFields) {
                ObjectNode payloadFieldNode = JsonNodeFactory.instance.objectNode();
                payloadFieldNode.put("name", safe(payloadField.getName()));
                payloadFieldNode.put("type", safe(payloadField.getType()));
                payloadNode.add(payloadFieldNode);
            }
            node.set("payload", payloadNode);
            events.add(node);
        }
        return events;
    }

    private static ArrayNode toFlows(CompiledModel model) {
        ArrayNode flows = JsonNodeFactory.instance.arrayNode();
        List<CompiledFlow> sorted = new ArrayList<>(model.getFlows());
        sorted.sort(Comparator.comparing(flow -> normalize(flow.getName())));
        for (CompiledFlow flow : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(flow.getName()));
            node.put("concept", safe(flow.getConcept()));
            node.put("mode", safe(flow.getMode()));
            node.put("startEndpoint", flow.isStartEndpoint());
            node.set("inputSchema", toSchema(flow.getInputSchema()));
            node.set("outputSchema", toSchema(flow.getOutputSchema()));
            node.set("action", toActionMetadata(flow.getAction()));
            node.set("steps", toFlowSteps(flow.getSteps()));
            node.set("schedule", toFlowSchedule(flow.getSchedule()));
            flows.add(node);
        }
        return flows;
    }

    private static ObjectNode toFlowSchedule(CompiledFlowSchedule schedule) {
        if (schedule == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("cron", safe(schedule.getCron()));
        node.set("tenantScope", toStringArray(schedule.getTenantScope()));
        return node;
    }

    private static ArrayNode toQueries(CompiledModel model) {
        ArrayNode queries = JsonNodeFactory.instance.arrayNode();
        List<CompiledQuery> sorted = new ArrayList<>(model.getQueries());
        sorted.sort(Comparator.comparing(query -> normalize(query.name())));
        for (CompiledQuery query : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(query.name()));
            node.put("concept", safe(query.concept()));
            node.put("where", safe(query.where()));
            node.set("orderBy", toStringArray(query.orderBy()));
            putNullableInteger(node, "limit", query.limit());
            node.set("parameters", toProcedureParameters(query.parameters()));
            node.set("permissionRequirements", toStringArray(query.permissionRequirements()));
            node.put("tracePolicy", safe(query.tracePolicy()));
            node.put("auditPolicy", safe(query.auditPolicy()));
            node.set("metadata", toObjectMap(query.metadata()));
            queries.add(node);
        }
        return queries;
    }

    private static ArrayNode toRuleProfiles(CompiledModel model) {
        ArrayNode profiles = JsonNodeFactory.instance.arrayNode();
        List<CompiledRuleProfile> sorted = new ArrayList<>(model.getRuleProfiles());
        sorted.sort(Comparator.comparing(profile -> normalize(profile.name())));
        for (CompiledRuleProfile profile : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(profile.name()));
            node.put("description", safe(profile.description()));
            node.set("appliesTo", toStringArray(profile.appliesTo()));
            node.put("enabled", profile.enabled());
            node.set("metadata", toObjectMap(profile.metadata()));
            profiles.add(node);
        }
        return profiles;
    }

    private static ArrayNode toProcedures(CompiledModel model) {
        ArrayNode procedures = JsonNodeFactory.instance.arrayNode();
        List<CompiledProcedure> sorted = new ArrayList<>(model.getProcedures());
        sorted.sort(Comparator.comparing(procedure -> normalize(procedure.name())));
        for (CompiledProcedure procedure : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(procedure.name()));
            node.put("description", safe(procedure.description()));
            node.set("parameters", toProcedureParameters(procedure.parameters()));
            node.set("variables", toProcedureVariables(procedure.variables()));
            node.set("steps", toProcedureSteps(procedure.steps()));
            node.set("returns", toSchema(procedure.returns()));
            node.set("permissionRequirements", toStringArray(procedure.permissionRequirements()));
            node.put("tracePolicy", safe(procedure.tracePolicy()));
            node.put("auditPolicy", safe(procedure.auditPolicy()));
            node.set("actionDescriptor", toGeneratedActionDescriptor(procedure.actionDescriptor()));
            node.set("metadata", toObjectMap(procedure.metadata()));
            procedures.add(node);
        }
        return procedures;
    }

    private static ObjectNode toGeneratedActionDescriptor(CompiledGeneratedActionDescriptorSpec descriptor) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (descriptor == null) {
            return node;
        }
        node.put("actionName", safe(descriptor.actionName()));
        node.set("affectedConcepts", toStringArray(descriptor.affectedConcepts()));
        node.put("sideEffectConcept", safe(descriptor.sideEffectConcept()));
        node.put("eventNameOnSuccess", safe(descriptor.eventNameOnSuccess()));
        node.put("auditResourceType", safe(descriptor.auditResourceType()));
        node.put("idempotencyPolicy", safe(descriptor.idempotencyPolicy()));
        node.put("tracePolicy", safe(descriptor.tracePolicy()));
        node.put("correlationPolicy", safe(descriptor.correlationPolicy()));
        node.put("explicit", descriptor.explicit());
        return node;
    }

    private static ArrayNode toProcedureParameters(List<CompiledProcedureParameter> parameters) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (parameters == null) {
            return out;
        }
        List<CompiledProcedureParameter> sorted = new ArrayList<>(parameters);
        sorted.sort(Comparator.comparing(parameter -> normalize(parameter.name())));
        for (CompiledProcedureParameter parameter : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(parameter.name()));
            node.put("type", safe(parameter.type()));
            node.put("required", parameter.required());
            node.set("schema", toSchema(parameter.schema()));
            node.put("description", safe(parameter.description()));
            out.add(node);
        }
        return out;
    }

    private static ArrayNode toProcedureVariables(List<CompiledProcedureVariable> variables) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (variables == null) {
            return out;
        }
        List<CompiledProcedureVariable> sorted = new ArrayList<>(variables);
        sorted.sort(Comparator.comparing(variable -> normalize(variable.name())));
        for (CompiledProcedureVariable variable : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(variable.name()));
            node.put("type", safe(variable.type()));
            node.set("schema", toSchema(variable.schema()));
            node.set("initialValue", toAnyValueNode(variable.initialValue()));
            out.add(node);
        }
        return out;
    }

    private static ArrayNode toProcedureSteps(List<CompiledProcedureStep> procedureSteps) {
        ArrayNode steps = JsonNodeFactory.instance.arrayNode();
        if (procedureSteps == null) {
            return steps;
        }
        for (CompiledProcedureStep step : procedureSteps) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(step.name()));
            node.put("type", safe(step.type()));
            node.put("target", safe(step.target()));
            node.set("value", toAnyValueNode(step.value()));
            node.put("condition", safe(step.condition()));
            node.put("items", safe(step.items()));
            node.put("as", safe(step.as()));
            node.put("concept", safe(step.concept()));
            node.put("query", safe(step.query()));
            node.set("data", toObjectMap(step.data()));
            node.put("id", safe(step.id()));
            node.put("procedure", safe(step.procedure()));
            // LNCH-1 P0.2 (found by the reflective CanonicalJsonRoundTripCompletenessTest ratchet):
            // flow was read by CompiledModelCanonicalJsonReader#toProcedureSteps but never written
            // here, silently dropping a procedure step's flow reference across the canonical-JSON
            // round trip every generated app's NPDevModelProvider reads at boot -- same bug class
            // as LNCH-6's indexes/LNCH-13's access/LNCH-12's schedule/LNCH-17's loopSteps.
            node.put("flow", safe(step.flow()));
            node.put("capability", safe(step.capability()));
            node.put("operation", safe(step.operation()));
            node.put("event", safe(step.event()));
            node.set("args", toObjectMap(step.args()));
            node.set("thenSteps", toProcedureSteps(step.thenSteps()));
            node.set("elseSteps", toProcedureSteps(step.elseSteps()));
            node.set("steps", toProcedureSteps(step.steps()));
            putNullableBoolean(node, "trace", step.trace());
            putNullableBoolean(node, "audit", step.audit());
            node.set("metadata", toObjectMap(step.metadata()));
            node.set("set", toObjectMap(step.set()));
            // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1B): patchConcept's create-if-missing opt-in.
            putNullableBoolean(node, "createIfMissing", step.createIfMissing());
            steps.add(node);
        }
        return steps;
    }

    private static ArrayNode toPanels(CompiledModel model) {
        ArrayNode panels = JsonNodeFactory.instance.arrayNode();
        List<CompiledPanel> sorted = new ArrayList<>(model.getPanels());
        sorted.sort(Comparator.comparing(panel -> normalize(panel.name())));
        for (CompiledPanel panel : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(panel.name()));
            node.put("route", safe(panel.route()));
            node.put("title", safe(panel.title()));
            node.set("dataSources", toPanelDataSources(panel.dataSources()));
            node.set("layout", toPanelLayout(panel.layout()));
            node.set("fieldBindings", toPanelFieldBindings(panel.fieldBindings()));
            node.put("visibility", safe(panel.visibility()));
            node.put("enabledWhen", safe(panel.enabledWhen()));
            node.set("actions", toPanelActions(panel.actions()));
            node.set("explainability", toObjectMap(panel.explainability()));
            node.set("metadata", toObjectMap(panel.metadata()));
            node.put("guidePage", safe(panel.guidePage()));
            panels.add(node);
        }
        return panels;
    }

    private static ArrayNode toGuidePages(CompiledModel model) {
        ArrayNode guidePages = JsonNodeFactory.instance.arrayNode();
        List<CompiledGuidePage> sorted = new ArrayList<>(model.getGuidePages());
        sorted.sort(Comparator.comparing(page -> normalize(page.name())));
        for (CompiledGuidePage page : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(page.name()));
            node.put("default", page.isDefault());
            node.set("regions", toGuidePageRegions(page.regions()));
            node.set("theme", toGuidePageTheme(page.theme()));
            node.set("gadgets", toGuidePageGadgets(page.gadgets()));
            guidePages.add(node);
        }
        return guidePages;
    }

    private static JsonNode toGuidePageRegions(CompiledGuidePageRegions regions) {
        if (regions == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("top", regions.top());
        node.set("left", toGuidePageRegion(regions.left()));
        node.set("right", toGuidePageRegion(regions.right()));
        return node;
    }

    private static JsonNode toGuidePageRegion(CompiledGuidePageRegion region) {
        if (region == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("enabled", region.enabled());
        node.put("collapsible", region.collapsible());
        node.put("defaultCollapsed", region.defaultCollapsed());
        node.put("width", region.width());
        return node;
    }

    private static JsonNode toGuidePageTheme(CompiledGuidePageTheme theme) {
        if (theme == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("mode", safe(theme.mode()));
        node.put("accent", safe(theme.accent()));
        node.put("density", safe(theme.density()));
        node.put("logoText", safe(theme.logoText()));
        node.put("logoUrl", safe(theme.logoUrl()));
        return node;
    }

    private static ArrayNode toGuidePageGadgets(List<CompiledGuidePageGadget> gadgets) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (gadgets == null) {
            return out;
        }
        List<CompiledGuidePageGadget> sorted = new ArrayList<>(gadgets);
        sorted.sort(Comparator.comparing(gadget -> normalize(gadget.name())));
        for (CompiledGuidePageGadget gadget : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(gadget.name()));
            node.put("type", safe(gadget.type()));
            node.put("title", safe(gadget.title()));
            out.add(node);
        }
        return out;
    }

    private static ArrayNode toPanelDataSources(List<CompiledPanelDataSource> dataSources) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (dataSources == null) {
            return out;
        }
        List<CompiledPanelDataSource> sorted = new ArrayList<>(dataSources);
        sorted.sort(Comparator.comparing(dataSource -> normalize(dataSource.name())));
        for (CompiledPanelDataSource dataSource : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(dataSource.name()));
            node.put("concept", safe(dataSource.concept()));
            node.put("query", safe(dataSource.query()));
            node.put("procedure", safe(dataSource.procedure()));
            node.set("params", toObjectMap(dataSource.params()));
            node.put("parentDataSource", safe(dataSource.parentDataSource()));
            node.put("parentField", safe(dataSource.parentField()));
            node.put("childField", safe(dataSource.childField()));
            node.set("rowOps", toStringArray(dataSource.rowOps()));
            node.set("addFormFields", toStringArray(dataSource.addFormFields()));
            out.add(node);
        }
        return out;
    }

    private static JsonNode toPanelLayout(CompiledPanelLayout layout) {
        if (layout == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type", safe(layout.type()));
        ArrayNode children = JsonNodeFactory.instance.arrayNode();
        for (CompiledPanelLayout child : layout.children()) {
            children.add(toPanelLayout(child));
        }
        node.set("children", children);
        node.set("fields", toStringArray(layout.fields()));
        node.set("metadata", toObjectMap(layout.metadata()));
        return node;
    }

    private static ArrayNode toPanelFieldBindings(List<CompiledPanelFieldBinding> bindings) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (bindings == null) {
            return out;
        }
        List<CompiledPanelFieldBinding> sorted = new ArrayList<>(bindings);
        sorted.sort(Comparator.comparing(binding -> normalize(binding.field())));
        for (CompiledPanelFieldBinding binding : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("field", safe(binding.field()));
            node.put("source", safe(binding.source()));
            node.put("visibleWhen", safe(binding.visibleWhen()));
            node.put("enabledWhen", safe(binding.enabledWhen()));
            node.put("readonlyWhen", safe(binding.readonlyWhen()));
            node.set("ui", toPresentationMetadata(binding.ui()));
            node.put("editable", binding.editable());
            out.add(node);
        }
        return out;
    }

    private static ArrayNode toPanelActions(List<CompiledPanelAction> actions) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (actions == null) {
            return out;
        }
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
            node.set("explainability", toObjectMap(action.explainability()));
            node.set("metadata", toObjectMap(action.metadata()));
            node.put("scope", safe(action.scope()));
            node.put("dataSource", safe(action.dataSource()));
            node.set("inputFields", toStringArray(action.inputFields()));
            out.add(node);
        }
        return out;
    }

    /** LNCH-13: row-level authorization rule (access: {read, write}). */
    private static ObjectNode toConceptAccess(CompiledConceptAccess access) {
        if (access == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("read", safe(access.getRead()));
        node.put("write", safe(access.getWrite()));
        return node;
    }

    private static ObjectNode toLifecycle(CompiledLifecycle lifecycle) {
        if (lifecycle == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("statusField", safe(lifecycle.getStatusField()));
        ArrayNode statesNode = JsonNodeFactory.instance.arrayNode();
        for (CompiledStateMachineState state : lifecycle.getStates()) {
            ObjectNode stateNode = JsonNodeFactory.instance.objectNode();
            stateNode.put("value", safe(state.getValue()));
            stateNode.put("label", safe(state.getLabel()));
            stateNode.put("initial", state.isInitial());
            stateNode.put("terminal", state.isTerminal());
            stateNode.set("metadata", toStringMap(state.getMetadata()));
            statesNode.add(stateNode);
        }
        node.set("states", statesNode);
        ArrayNode transitionsNode = JsonNodeFactory.instance.arrayNode();
        List<CompiledStateTransition> transitions = new ArrayList<>(lifecycle.getTransitions());
        transitions.sort(Comparator
                .comparing((CompiledStateTransition transition) -> normalize(transition.getFrom()))
                .thenComparing(transition -> normalize(transition.getTo()))
                .thenComparing(transition -> normalize(transition.getEvent())));
        for (CompiledStateTransition transition : transitions) {
            ObjectNode transitionNode = JsonNodeFactory.instance.objectNode();
            transitionNode.put("from", safe(transition.getFrom()));
            transitionNode.put("to", safe(transition.getTo()));
            transitionNode.set("requiredPayload", toStringArray(transition.getRequiredPayload()));
            transitionNode.put("event", safe(transition.getEvent()));
            transitionNode.put("guard", safe(transition.getGuard()));
            transitionNode.put("actionLabel", safe(transition.getActionLabel()));
            transitionNode.set("action", toActionMetadata(transition.getAction()));
            transitionNode.set("metadata", toStringMap(transition.getMetadata()));
            transitionsNode.add(transitionNode);
        }
        node.set("transitions", transitionsNode);
        return node;
    }

    private static ArrayNode toOrchestrationRules(CompiledModel model) {
        ArrayNode rules = JsonNodeFactory.instance.arrayNode();
        List<CompiledOrchestration> sorted = new ArrayList<>(model.getOrchestrationRules());
        sorted.sort(Comparator.comparing(rule -> normalize(rule.getName())));
        for (CompiledOrchestration rule : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(rule.getName()));
            node.put("condition", safe(rule.getCondition()));

            ObjectNode triggerNode = JsonNodeFactory.instance.objectNode();
            if (rule.getTrigger() != null) {
                triggerNode.put("type", safe(rule.getTrigger().getType()));
                triggerNode.put("event", safe(rule.getTrigger().getEvent()));
            } else {
                triggerNode.put("type", "");
                triggerNode.put("event", "");
            }
            node.set("trigger", triggerNode);

            List<CompiledOrchestrationAction> actions = rule.getActions().isEmpty()
                    ? (rule.getAction() == null ? List.of() : List.of(rule.getAction()))
                    : rule.getActions();
            CompiledOrchestrationAction primaryAction = actions.isEmpty() ? null : actions.get(0);
            node.set("action", toOrchestrationActionNode(primaryAction));
            node.set("actions", toOrchestrationActions(actions));
            rules.add(node);
        }
        return rules;
    }

    private static ObjectNode toOrchestrationActionNode(CompiledOrchestrationAction action) {
        ObjectNode actionNode = JsonNodeFactory.instance.objectNode();
        if (action != null) {
            actionNode.put("type", safe(action.getType()));
            actionNode.put("concept", safe(action.getConcept()));
            actionNode.put("capability", safe(action.getCapability()));
            actionNode.put("operation", safe(action.getOperation()));
            actionNode.put("event", safe(action.getEvent()));
            if (action.getDelaySeconds() == null) {
                actionNode.putNull("delaySeconds");
            } else {
                actionNode.put("delaySeconds", action.getDelaySeconds());
            }
            actionNode.set("action", toActionMetadata(action.getAction()));
            actionNode.set("map", toStringMap(action.getMap()));
            return actionNode;
        }
        actionNode.put("type", "");
        actionNode.put("concept", "");
        actionNode.put("capability", "");
        actionNode.put("operation", "");
        actionNode.put("event", "");
        actionNode.putNull("delaySeconds");
        actionNode.set("map", JsonNodeFactory.instance.objectNode());
        return actionNode;
    }

    private static ArrayNode toOrchestrationActions(List<CompiledOrchestrationAction> actions) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (actions == null) {
            return out;
        }
        for (CompiledOrchestrationAction action : actions) {
            out.add(toOrchestrationActionNode(action));
        }
        return out;
    }

    private static ArrayNode toFlowSteps(List<CompiledFlowStep> flowSteps) {
        ArrayNode steps = JsonNodeFactory.instance.arrayNode();
        for (CompiledFlowStep flowStep : flowSteps) {
            ObjectNode stepNode = JsonNodeFactory.instance.objectNode();
            stepNode.put("name", safe(flowStep.getName()));
            stepNode.put("type", safe(flowStep.getType()));
            stepNode.put("checkpoint", safe(flowStep.getCheckpoint()));
            stepNode.put("scope", safe(flowStep.getScope()));
            stepNode.set("invariants", toStringArray(flowStep.getInvariants()));
            stepNode.put("eventName", safe(flowStep.getEventName()));
            stepNode.put("payloadRef", safe(flowStep.getPayloadRef()));
            stepNode.set("eventDataRefs", toStringMap(flowStep.getEventDataRefs()));
            stepNode.put("condition", safe(flowStep.getCondition()));
            stepNode.set("action", toActionMetadata(flowStep.getAction()));
            stepNode.set("thenSteps", toFlowSteps(flowStep.getThenSteps()));
            stepNode.set("elseSteps", toFlowSteps(flowStep.getElseSteps()));
            stepNode.put("awaitEventName", safe(flowStep.getAwaitEventName()));
            stepNode.put("awaitRef", safe(flowStep.getAwaitRef()));
            if (flowStep.getAwaitMatchCorrelation() == null) {
                stepNode.putNull("awaitMatchCorrelation");
            } else {
                stepNode.put("awaitMatchCorrelation", flowStep.getAwaitMatchCorrelation());
            }
            stepNode.set("awaitPayloadMatch", toStringMap(flowStep.getAwaitPayloadMatch()));
            if (flowStep.getDelaySeconds() == null) {
                stepNode.putNull("delaySeconds");
            } else {
                stepNode.put("delaySeconds", flowStep.getDelaySeconds());
            }
            stepNode.put("mapFromRef", safe(flowStep.getMapFromRef()));
            stepNode.put("mapToRef", safe(flowStep.getMapToRef()));
            stepNode.put("returnValueRef", safe(flowStep.getReturnValueRef()));
            stepNode.put("generatedActionName", safe(flowStep.getGeneratedActionName()));
            stepNode.set("capabilityCall", toCapabilityCall(flowStep.getCapabilityCall()));
            // LNCH-17 (found while adding onFailureSteps below): collectionRef/itemKey/loopSteps/
            // maxLoopIterations (LIFT-LOOP-P1's forEach fields) were never written here, so a
            // forEach step's loop body silently vanished across the canonical-JSON round trip every
            // generated app's NPDevModelProvider actually reads at boot -- the same bug class as
            // LNCH-6's indexes/LNCH-13's access/LNCH-12's schedule, pre-existing and unrelated to
            // this feature, fixed alongside it since this method needed touching anyway.
            stepNode.put("collectionRef", safe(flowStep.getCollectionRef()));
            stepNode.put("itemKey", safe(flowStep.getItemKey()));
            stepNode.set("loopSteps", toFlowSteps(flowStep.getLoopSteps()));
            if (flowStep.getMaxLoopIterations() == null) {
                stepNode.putNull("maxLoopIterations");
            } else {
                stepNode.put("maxLoopIterations", flowStep.getMaxLoopIterations());
            }
            stepNode.set("onFailureSteps", toFlowSteps(flowStep.getOnFailureSteps()));
            // Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1A): callProcedure's procedure name.
            stepNode.put("procedureName", safe(flowStep.getProcedureName()));
            steps.add(stepNode);
        }
        return steps;
    }

    private static ObjectNode toActionMetadata(CompiledActionMetadata action) {
        if (action == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("label", safe(action.getLabel()));
        node.put("confirmationText", safe(action.getConfirmationText()));
        node.put("successMessage", safe(action.getSuccessMessage()));
        node.put("failureHint", safe(action.getFailureHint()));
        node.put("dangerLevel", safe(action.getDangerLevel()));
        node.put("visibleWhen", safe(action.getVisibleWhen()));
        node.put("permissionHint", safe(action.getPermissionHint()));
        node.put("inputFormHint", safe(action.getInputFormHint()));
        return node;
    }

    private static ObjectNode toCapabilityCall(CompiledCapabilityCall capabilityCall) {
        if (capabilityCall == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("capabilityName", safe(capabilityCall.getCapabilityName()));
        node.put("capabilityType", safe(capabilityCall.getCapabilityType()));
        node.put("adapterId", safe(capabilityCall.getAdapterId()));
        node.put("operation", safe(capabilityCall.getOperation()));
        node.set("argsRefs", toStringArray(capabilityCall.getArgsRefs()));
        node.put("inputRef", safe(capabilityCall.getInputRef()));
        node.put("outputRef", safe(capabilityCall.getOutputRef()));
        node.set("inputSchema", toSchema(capabilityCall.getInputSchema()));
        node.set("outputSchema", toSchema(capabilityCall.getOutputSchema()));
        node.set("executionPolicy", toExecutionPolicy(capabilityCall.getExecutionPolicy()));
        return node;
    }

    private static ObjectNode toExecutionPolicy(CompiledCapabilityExecutionPolicy executionPolicy) {
        if (executionPolicy == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("retryCount", executionPolicy.getRetryCount());
        node.put("retryDelayMs", executionPolicy.getRetryDelayMs());
        node.put("timeoutMs", executionPolicy.getTimeoutMs());
        node.put("circuitOpenAfterFailures", executionPolicy.getCircuitOpenAfterFailures());
        node.put("circuitOpenMs", executionPolicy.getCircuitOpenMs());
        node.put("bulkheadMaxConcurrent", executionPolicy.getBulkheadMaxConcurrent());
        node.put("idempotencyKeyField", safe(executionPolicy.getIdempotencyKeyField()));
        node.put("failureClassification", safe(executionPolicy.getFailureClassification()));
        return node;
    }

    private static ObjectNode toSchema(CompiledSchema schema) {
        if (schema == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type", safe(schema.getType()));
        node.put("description", safe(schema.getDescription()));
        if (schema.getMinLength() == null) {
            node.putNull("minLength");
        } else {
            node.put("minLength", schema.getMinLength());
        }
        if (schema.getMaxLength() == null) {
            node.putNull("maxLength");
        } else {
            node.put("maxLength", schema.getMaxLength());
        }
        if (schema.getMinItems() == null) {
            node.putNull("minItems");
        } else {
            node.put("minItems", schema.getMinItems());
        }
        if (schema.getMaxItems() == null) {
            node.putNull("maxItems");
        } else {
            node.put("maxItems", schema.getMaxItems());
        }
        if (schema.getUniqueItems() == null) {
            node.putNull("uniqueItems");
        } else {
            node.put("uniqueItems", schema.getUniqueItems());
        }
        node.put("itemIdentityField", safe(schema.getItemIdentityField()));
        node.put("duplicationPolicy", safe(schema.getDuplicationPolicy()));
        if (schema.getMin() == null) {
            node.putNull("min");
        } else {
            node.put("min", schema.getMin());
        }
        if (schema.getMax() == null) {
            node.putNull("max");
        } else {
            node.put("max", schema.getMax());
        }
        node.put("regex", safe(schema.getRegex()));
        node.set("required", toStringArray(schema.getRequired()));
        node.set("enumValues", toStringArray(schema.getEnumValues()));
        node.set("defaultValue", toAnyValueNode(schema.getDefaultValue()));
        node.put("defaultExpression", safe(schema.getDefaultExpression()));
        node.put("derivedExpression", safe(schema.getDerivedExpression()));
        node.set("items", toSchema(schema.getItems()));

        TreeMap<String, CompiledSchema> sortedProperties = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sortedProperties.putAll(schema.getProperties());
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, CompiledSchema> property : sortedProperties.entrySet()) {
            properties.set(property.getKey(), toSchema(property.getValue()));
        }
        node.set("properties", properties);
        return node;
    }

    private static com.fasterxml.jackson.databind.JsonNode toAnyValueNode(Object value) {
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        return MAPPER.valueToTree(value);
    }

    private static ObjectNode toObjectMap(Map<String, Object> values) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (values == null || values.isEmpty()) {
            return node;
        }
        TreeMap<String, Object> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(values);
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            node.set(entry.getKey(), toAnyValueNode(entry.getValue()));
        }
        return node;
    }

    private static void putNullableBoolean(ObjectNode node, String fieldName, Boolean value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value);
        }
    }

    private static void putNullableInteger(ObjectNode node, String fieldName, Integer value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value);
        }
    }

    private static ArrayNode toStringArray(List<String> values) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        if (values == null) {
            return array;
        }
        for (String value : values) {
            array.add(safe(value));
        }
        return array;
    }

    private static ArrayNode toEnumOptions(List<CompiledEnumOption> options) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        if (options == null) {
            return array;
        }
        for (CompiledEnumOption option : options) {
            if (option == null) {
                continue;
            }
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("value", safe(option.getValue()));
            node.put("label", safe(option.getLabel()));
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
            array.add(node);
        }
        return array;
    }

    private static JsonNode toReferenceSemantics(CompiledReferenceSemantics referenceSemantics) {
        if (referenceSemantics == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("target", safe(referenceSemantics.getTarget()));
        node.put("multiple", referenceSemantics.isMultiple());
        node.put("displayField", safe(referenceSemantics.getDisplayField()));
        node.set("searchFields", toStringArray(referenceSemantics.getSearchFields()));
        node.set("previewFields", toStringArray(referenceSemantics.getPreviewFields()));
        node.put("inlineCreate", safe(referenceSemantics.getInlineCreatePolicy()));
        node.put("displayTemplate", safe(referenceSemantics.getDisplayTemplate()));
        node.set("pickerColumns", toStringArray(referenceSemantics.getPickerColumns()));
        node.put("previewCardTemplate", safe(referenceSemantics.getPreviewCardTemplate()));
        node.put("defaultFilter", safe(referenceSemantics.getDefaultFilter()));
        node.put("via", safe(referenceSemantics.getVia()));
        node.put("onDelete", safe(referenceSemantics.getOnDelete()));
        return node;
    }

    /**
     * HARDEN-OBJSTORE: {@code file} was previously dropped by this hand-rolled writer (only the
     * generic Jackson getter-based serialization picked it up), so every generated app's runtime
     * {@code CompiledModel} silently lost a file field's contentTypes/maxSizeBytes/multiple
     * constraints on read-back, defeating {@code FileUploadController}'s upload-time validation.
     */
    private static JsonNode toFileMetadata(CompiledFileMetadata file) {
        if (file == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.set("contentTypes", toStringArray(file.contentTypes()));
        if (file.maxSizeBytes() == null) {
            node.putNull("maxSizeBytes");
        } else {
            node.put("maxSizeBytes", file.maxSizeBytes());
        }
        node.put("multiple", file.multiple());
        return node;
    }

    private static ObjectNode toStringMap(Map<String, String> values) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (values == null || values.isEmpty()) {
            return node;
        }
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(values);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            node.put(entry.getKey(), safe(entry.getValue()));
        }
        return node;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
