package com.npdev.dsl.v1.resolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.CapabilityOperationAst;
import com.npdev.dsl.v1.ast.CapabilityPolicyAst;
import com.npdev.dsl.v1.ast.ActionMetadataAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.EventPayloadAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.InvariantAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.OrchestrationActionAst;
import com.npdev.dsl.v1.ast.OrchestrationAst;
import com.npdev.dsl.v1.ast.ReferenceSemanticsAst;
import com.npdev.dsl.v1.ast.SchemaAst;
import com.npdev.dsl.v1.ast.StepAst;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class ResolvedModelCanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ResolvedModelCanonicalJson() {
    }

    public static String toJson(ModelAst modelAst) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("dslVersion", safe(modelAst.getDslVersion()));
        root.put("namespace", safe(modelAst.getNamespace()));
        root.put("version", safe(modelAst.getVersion()));
        root.set("concepts", toEntities(modelAst.getConcepts()));
        root.set("capabilities", toCapabilities(modelAst.getCapabilities()));
        root.set("bindings", toBindings(modelAst.getBindings()));
        root.set("events", toEvents(modelAst.getEvents()));
        root.set("flows", toFlows(modelAst.getFlows()));
        root.set("orchestrationRules", toOrchestrationRules(modelAst.getOrchestrationRules()));
        try {
            return MAPPER.writeValueAsString(root) + "\n";
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize resolved model canonical JSON", exception);
        }
    }

    private static ArrayNode toEntities(List<ConceptAst> entities) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        List<ConceptAst> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparing(entity -> normalize(entity.getName())));
        for (ConceptAst entity : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(entity.getName()));
            ArrayNode fieldsNode = JsonNodeFactory.instance.arrayNode();
            List<FieldAst> fields = new ArrayList<>(entity.getFields());
            fields.sort(Comparator.comparing(field -> normalize(field.getName())));
            for (FieldAst field : fields) {
                ObjectNode fieldNode = JsonNodeFactory.instance.objectNode();
                fieldNode.put("name", safe(field.getName()));
                fieldNode.put("type", safe(field.getType()));
                fieldNode.put("id", field.isId());
                fieldNode.put("required", field.isRequired());
                fieldNode.put("unique", field.isUnique());
                fieldNode.set("enumValues", toStringArray(field.getEnumValues()));
                fieldNode.set("enumOptions", toEnumOptions(field.getEnumOptions()));
                fieldNode.put("referenceTarget", safe(field.getReferenceTarget()));
                fieldNode.set("referenceSemantics", toReferenceSemantics(field.getReferenceSemantics()));
                fieldNode.set("schema", toSchema(field.getSchema()));
                fieldsNode.add(fieldNode);
            }
            node.set("fields", fieldsNode);
            ArrayNode invariantsNode = JsonNodeFactory.instance.arrayNode();
            List<InvariantAst> invariants = new ArrayList<>(entity.getInvariants());
            invariants.sort(Comparator.comparing(ResolvedModelCanonicalJson::invariantSortKey));
            for (InvariantAst invariant : invariants) {
                ObjectNode invariantNode = JsonNodeFactory.instance.objectNode();
                invariantNode.put("name", safe(invariant.getName()));
                invariantNode.put("type", safe(invariant.getType()));
                invariantNode.set("fields", toStringArray(invariant.getFields()));
                invariantNode.put("expression", safe(invariant.getExpression()));
                invariantsNode.add(invariantNode);
            }
            node.set("invariants", invariantsNode);
            out.add(node);
        }
        return out;
    }

    private static ArrayNode toCapabilities(List<CapabilityAst> capabilities) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        List<CapabilityAst> sorted = new ArrayList<>(capabilities);
        sorted.sort(Comparator.comparing(capability -> normalize(capability.getName())));
        for (CapabilityAst capability : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(capability.getName()));
            node.put("type", safe(capability.getType()));
            ArrayNode operationsNode = JsonNodeFactory.instance.arrayNode();
            List<CapabilityOperationAst> operations = new ArrayList<>(capability.getOperations());
            operations.sort(Comparator.comparing(operation -> normalize(operation.getName())));
            for (CapabilityOperationAst operation : operations) {
                ObjectNode operationNode = JsonNodeFactory.instance.objectNode();
                operationNode.put("name", safe(operation.getName()));
                operationNode.set("input", toStringArray(operation.getInput()));
                operationNode.set("output", toStringArray(operation.getOutput()));
                operationNode.set("inputSchema", toSchema(operation.getInputSchema()));
                operationNode.set("outputSchema", toSchema(operation.getOutputSchema()));
                operationNode.set("policy", toPolicy(operation.getExecutionPolicy()));
                operationsNode.add(operationNode);
            }
            node.set("operations", operationsNode);
            out.add(node);
        }
        return out;
    }

    private static ArrayNode toBindings(List<CapabilityBindingAst> bindings) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        List<CapabilityBindingAst> sorted = new ArrayList<>(bindings);
        sorted.sort(Comparator
                .comparing((CapabilityBindingAst binding) -> normalize(binding.getCapability()))
                .thenComparing(binding -> normalize(binding.getAdapter())));
        for (CapabilityBindingAst binding : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("capability", safe(binding.getCapability()));
            node.put("adapter", safe(binding.getAdapter()));
            out.add(node);
        }
        return out;
    }

    private static ArrayNode toEvents(List<EventAst> events) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        List<EventAst> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(event -> normalize(event.getName())));
        for (EventAst event : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(event.getName()));
            node.put("conceptName", safe(event.getConceptName()));
            node.put("version", safe(event.getVersion()));
            ArrayNode payloadNode = JsonNodeFactory.instance.arrayNode();
            List<EventPayloadAst> payload = new ArrayList<>(event.getPayloadFields());
            payload.sort(Comparator.comparing(field -> normalize(field.getName())));
            for (EventPayloadAst payloadField : payload) {
                ObjectNode payloadFieldNode = JsonNodeFactory.instance.objectNode();
                payloadFieldNode.put("name", safe(payloadField.getName()));
                payloadFieldNode.put("type", safe(payloadField.getType()));
                payloadNode.add(payloadFieldNode);
            }
            node.set("payload", payloadNode);
            out.add(node);
        }
        return out;
    }

    private static ArrayNode toFlows(List<FlowAst> flows) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        List<FlowAst> sorted = new ArrayList<>(flows);
        sorted.sort(Comparator.comparing(flow -> normalize(flow.getName())));
        for (FlowAst flow : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(flow.getName()));
            node.put("concept", safe(flow.getConcept()));
            node.put("mode", safe(flow.getMode()));
            node.set("inputSchema", toSchema(flow.getInputSchema()));
            node.set("outputSchema", toSchema(flow.getOutputSchema()));
            node.set("action", toActionMetadata(flow.getAction()));
            node.set("steps", toSteps(flow.getSteps()));
            out.add(node);
        }
        return out;
    }

    private static ArrayNode toOrchestrationRules(List<OrchestrationAst> rules) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        List<OrchestrationAst> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparing(rule -> normalize(rule.getName())));
        for (OrchestrationAst rule : sorted) {
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

            List<OrchestrationActionAst> actions = rule.getActions().isEmpty()
                    ? (rule.getAction() == null ? List.of() : List.of(rule.getAction()))
                    : rule.getActions();
            OrchestrationActionAst primaryAction = actions.isEmpty() ? null : actions.get(0);
            node.set("action", toOrchestrationActionNode(primaryAction));
            node.set("actions", toOrchestrationActions(actions));
            out.add(node);
        }
        return out;
    }

    private static ObjectNode toOrchestrationActionNode(OrchestrationActionAst action) {
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

    private static ArrayNode toOrchestrationActions(List<OrchestrationActionAst> actions) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (actions == null) {
            return out;
        }
        for (OrchestrationActionAst action : actions) {
            out.add(toOrchestrationActionNode(action));
        }
        return out;
    }

    private static ArrayNode toSteps(List<StepAst> steps) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (StepAst step : steps) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", safe(step.getName()));
            node.put("type", safe(step.getType()));
            node.put("checkpoint", safe(step.getCheckpoint()));
            node.put("scope", safe(step.getScope()));
            node.put("capability", safe(step.getCapability()));
            node.put("operation", safe(step.getOperation()));
            node.put("input", safe(step.getInput()));
            node.put("output", safe(step.getOutput()));
            node.set("args", toStringArray(step.getArgs()));
            node.put("event", safe(step.getEvent()));
            node.put("payload", safe(step.getPayload()));
            node.set("data", toStringMap(step.getData()));
            node.put("condition", safe(step.getCondition()));
            node.set("action", toActionMetadata(step.getAction()));
            node.set("then", toSteps(step.getThenSteps()));
            node.set("else", toSteps(step.getElseSteps()));
            node.put("awaitEvent", safe(step.getAwaitEvent()));
            node.put("awaitRef", safe(step.getAwaitRef()));
            if (step.getAwaitMatchCorrelation() == null) {
                node.putNull("awaitMatchCorrelation");
            } else {
                node.put("awaitMatchCorrelation", step.getAwaitMatchCorrelation());
            }
            node.set("awaitPayloadMatch", toStringMap(step.getAwaitPayloadMatch()));
            if (step.getDelaySeconds() == null) {
                node.putNull("delaySeconds");
            } else {
                node.put("delaySeconds", step.getDelaySeconds());
            }
            node.put("value", safe(step.getReturnValue()));
            node.set("invariants", toStringArray(step.getInvariants()));
            node.set("policy", toPolicy(step.getCapabilityPolicy()));
            out.add(node);
        }
        return out;
    }

    private static ObjectNode toActionMetadata(ActionMetadataAst action) {
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

    private static ObjectNode toPolicy(CapabilityPolicyAst policy) {
        if (policy == null) {
            return null;
        }
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        if (policy.getRetryCount() == null) {
            node.putNull("retryCount");
        } else {
            node.put("retryCount", policy.getRetryCount());
        }
        if (policy.getRetryDelayMs() == null) {
            node.putNull("retryDelayMs");
        } else {
            node.put("retryDelayMs", policy.getRetryDelayMs());
        }
        if (policy.getTimeoutMs() == null) {
            node.putNull("timeoutMs");
        } else {
            node.put("timeoutMs", policy.getTimeoutMs());
        }
        if (policy.getCircuitOpenAfterFailures() == null) {
            node.putNull("circuitOpenAfterFailures");
        } else {
            node.put("circuitOpenAfterFailures", policy.getCircuitOpenAfterFailures());
        }
        if (policy.getCircuitOpenMs() == null) {
            node.putNull("circuitOpenMs");
        } else {
            node.put("circuitOpenMs", policy.getCircuitOpenMs());
        }
        if (policy.getBulkheadMaxConcurrent() == null) {
            node.putNull("bulkheadMaxConcurrent");
        } else {
            node.put("bulkheadMaxConcurrent", policy.getBulkheadMaxConcurrent());
        }
        node.put("idempotencyKeyField", safe(policy.getIdempotencyKeyField()));
        node.put("failureClassification", safe(policy.getFailureClassification()));
        return node;
    }

    private static ObjectNode toSchema(SchemaAst schema) {
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
        if (schema.getMinItems() != null) {
            node.put("minItems", schema.getMinItems());
        }
        if (schema.getMaxItems() != null) {
            node.put("maxItems", schema.getMaxItems());
        }
        if (schema.getUniqueItems() != null) {
            node.put("uniqueItems", schema.getUniqueItems());
        }
        if (schema.getItemIdentityField() != null && !schema.getItemIdentityField().isBlank()) {
            node.put("itemIdentityField", safe(schema.getItemIdentityField()));
        }
        if (schema.getDuplicationPolicy() != null && !schema.getDuplicationPolicy().isBlank()) {
            node.put("duplicationPolicy", safe(schema.getDuplicationPolicy()));
        }
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
        node.set("items", toSchema(schema.getItems()));
        TreeMap<String, SchemaAst> sortedProperties = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sortedProperties.putAll(schema.getProperties());
        ObjectNode propertiesNode = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, SchemaAst> property : sortedProperties.entrySet()) {
            propertiesNode.set(property.getKey(), toSchema(property.getValue()));
        }
        node.set("properties", propertiesNode);
        return node;
    }

    private static ArrayNode toStringArray(List<String> values) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (values == null) {
            return out;
        }
        for (String value : values) {
            out.add(safe(value));
        }
        return out;
    }

    private static ArrayNode toEnumOptions(List<com.npdev.dsl.v1.ast.EnumOptionAst> options) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (options == null) {
            return out;
        }
        for (com.npdev.dsl.v1.ast.EnumOptionAst option : options) {
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
            out.add(node);
        }
        return out;
    }

    private static com.fasterxml.jackson.databind.JsonNode toReferenceSemantics(ReferenceSemanticsAst referenceSemantics) {
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
        return node;
    }

    private static com.fasterxml.jackson.databind.JsonNode toAnyValueNode(Object value) {
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        return MAPPER.valueToTree(value);
    }

    private static ObjectNode toStringMap(Map<String, String> values) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (values == null || values.isEmpty()) {
            return out;
        }
        TreeMap<String, String> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        sorted.putAll(values);
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            out.put(entry.getKey(), safe(entry.getValue()));
        }
        return out;
    }

    private static String invariantSortKey(InvariantAst invariant) {
        if (invariant == null) {
            return "";
        }
        if (invariant.getName() != null && !invariant.getName().isBlank()) {
            return normalize(invariant.getName());
        }
        if ("unique".equalsIgnoreCase(invariant.getType()) && invariant.getFields().size() == 1) {
            return normalize("unique(" + invariant.getFields().get(0) + ")");
        }
        if ("expression".equalsIgnoreCase(invariant.getType())
                && invariant.getExpression() != null
                && !invariant.getExpression().isBlank()) {
            return normalize(invariant.getExpression());
        }
        return normalize(invariant.getType());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
