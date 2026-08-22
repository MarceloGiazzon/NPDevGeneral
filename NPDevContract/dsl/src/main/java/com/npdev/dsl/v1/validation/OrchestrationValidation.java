package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.ConceptAccessAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.CapabilityOperationAst;
import com.npdev.dsl.v1.ast.DomainTypeAst;
import com.npdev.dsl.v1.ast.CapabilityPolicyAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.EventPayloadAst;
import com.npdev.dsl.v1.ast.ExternalAiAst;
import com.npdev.dsl.v1.ast.EnumOptionAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.FlowScheduleAst;
import com.npdev.dsl.v1.ast.InvariantAst;
import com.npdev.dsl.v1.ast.LifecycleAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.OrchestrationActionAst;
import com.npdev.dsl.v1.ast.OrchestrationAst;
import com.npdev.dsl.v1.ast.OrchestrationTriggerAst;
import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.AggregateCollectionAst;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.AutoPanelComputedAst;
import com.npdev.dsl.v1.ast.AutoPanelSurfaceAst;
import com.npdev.dsl.v1.ast.SelectorAst;
import com.npdev.dsl.v1.ast.GuidePageAst;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.ast.GuidePageGadgetAst;
import com.npdev.dsl.v1.compiled.FieldWidgetDefaults;
import com.npdev.dsl.v1.compiled.GuidePageDefaults;
import com.npdev.dsl.v1.ast.PanelActionAst;
import com.npdev.dsl.v1.ast.PanelAst;
import com.npdev.dsl.v1.ast.PanelDataSourceAst;
import com.npdev.dsl.v1.ast.PresentationMetadataAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.ProcedureParameterAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.ast.QueryAst;
import com.npdev.dsl.v1.ast.ReferenceSemanticsAst;
import com.npdev.dsl.v1.ast.RuleProfileAst;
import com.npdev.dsl.v1.ast.TruthLevel;
import com.npdev.dsl.v1.ast.SchemaAst;
import com.npdev.dsl.v1.ast.StateMachineStateAst;
import com.npdev.dsl.v1.ast.StateTransitionAst;
import com.npdev.dsl.v1.ast.StepAst;
import com.npdev.dsl.v1.resolution.ModelResolutionException;
import com.npdev.dsl.v1.resolution.ModelResolver;
import com.npdev.dsl.v1.resolution.ResolvedModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;
import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;

/**
 * Semantic validation for declarative {@code orchestrationRules}: event-triggered actions (create /
 * callCapability / scheduleEvent) with their condition and field-mapping expressions.
 *
 * <p>Split out of {@code SemanticValidator} (T1.15) as a sub-boundary of the Flow section (see
 * {@link FlowValidation}) -- {@code orchestrationRules} sits adjacent to {@code flows} in
 * {@code model.schema.json} but is large enough on its own to warrant its own file.
 */
final class OrchestrationValidation {

    private OrchestrationValidation() {
    }

    static void validateOrchestrationRules(ModelAst modelAst, List<String> errors) {
        if (modelAst.getOrchestrationRules().isEmpty()) {
            return;
        }

        Map<String, ConceptAst> entitiesByName = modelAst.getConcepts().stream()
                .collect(Collectors.toMap(
                        entity -> normalize(entity.getName()),
                        entity -> entity,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, EventAst> eventsByName = modelAst.getEvents().stream()
                .collect(Collectors.toMap(
                        event -> normalize(event.getName()),
                        event -> event,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, CapabilityAst> capabilitiesByName = modelAst.getCapabilities().stream()
                .collect(Collectors.toMap(
                        capability -> normalize(capability.getName()),
                        capability -> capability,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        Set<String> orchestrationNames = new HashSet<>();
        for (OrchestrationAst orchestration : modelAst.getOrchestrationRules()) {
            if (orchestration == null) {
                continue;
            }
            String name = orchestration.getName();
            String nameKey = normalize(name);
            if (nameKey.isBlank()) {
                errors.add("Orchestration rule name must be non-blank");
                continue;
            }
            if (!orchestrationNames.add(nameKey)) {
                errors.add("Duplicate orchestration rule name: " + name);
            }

            OrchestrationTriggerAst trigger = orchestration.getTrigger();
            if (trigger == null) {
                errors.add("Orchestration " + name + ": trigger is required");
                continue;
            }
            String triggerType = normalize(trigger.getType());
            if (!"event".equals(triggerType)) {
                errors.add("Orchestration " + name + ": trigger type must be 'event'");
            }
            String triggerEvent = normalize(trigger.getEvent());
            if (triggerEvent.isBlank()) {
                errors.add("Orchestration " + name + ": trigger event is required");
            } else if (!eventsByName.containsKey(triggerEvent)) {
                errors.add("Orchestration " + name + ": trigger event not found: " + trigger.getEvent());
            }

            Set<String> eventPayloadFields = Set.of();
            if (!triggerEvent.isBlank()) {
                EventAst triggerEventAst = eventsByName.get(triggerEvent);
                if (triggerEventAst != null) {
                    eventPayloadFields = triggerEventAst.getPayloadFields().stream()
                            .map(EventPayloadAst::getName)
                            .map(SemanticValidator::normalize)
                            .collect(Collectors.toSet());
                }
            }
            validateOrchestrationCondition(name, orchestration.getCondition(), eventPayloadFields, errors);

            List<OrchestrationActionAst> actionSequence = orchestration.getActions().isEmpty()
                    ? (orchestration.getAction() == null ? List.of() : List.of(orchestration.getAction()))
                    : orchestration.getActions();
            if (actionSequence.isEmpty()) {
                errors.add("Orchestration " + name + ": at least one action is required");
                continue;
            }
            for (int actionIndex = 0; actionIndex < actionSequence.size(); actionIndex++) {
                OrchestrationActionAst action = actionSequence.get(actionIndex);
                if (action == null) {
                    errors.add("Orchestration " + name + ": actions[" + actionIndex + "] is null");
                    continue;
                }
                String actionLabel = actionSequence.size() == 1 && orchestration.getAction() != null
                        ? "action"
                        : "actions[" + actionIndex + "]";
                String actionType = normalize(action.getType());
                if (action.getMap().isEmpty()) {
                    errors.add("Orchestration " + name + ": " + actionLabel + " map must not be empty");
                    continue;
                }

                Set<String> allowedTargetKeys = null;
                if ("create".equals(actionType)) {
                    String conceptKey = normalize(action.getConcept());
                    if (conceptKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel + " concept is required");
                        continue;
                    }
                    ConceptAst targetConcept = entitiesByName.get(conceptKey);
                    if (targetConcept == null) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " concept not found: " + action.getConcept());
                        continue;
                    }
                    allowedTargetKeys = targetConcept.getFields().stream()
                            .map(FieldAst::getName)
                            .map(SemanticValidator::normalize)
                            .collect(Collectors.toSet());
                } else if ("callcapability".equals(actionType)) {
                    String capabilityKey = normalize(action.getCapability());
                    if (capabilityKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " capability is required for callCapability");
                        continue;
                    }
                    CapabilityAst capability = capabilitiesByName.get(capabilityKey);
                    if (capability == null) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " capability not found: " + action.getCapability());
                        continue;
                    }
                    String operationKey = normalize(action.getOperation());
                    if (operationKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " operation is required for callCapability");
                        continue;
                    }
                    CapabilityOperationAst matchedOperation = capability.getOperations().stream()
                            .filter(operation -> operation != null && normalize(operation.getName()).equals(operationKey))
                            .findFirst()
                            .orElse(null);
                    if (matchedOperation == null) {
                        errors.add("Orchestration " + name + ": " + actionLabel + " operation '"
                                + action.getOperation() + "' not found in capability " + capability.getName());
                    } else if (matchedOperation.getInput() != null && !matchedOperation.getInput().isEmpty()) {
                        allowedTargetKeys = matchedOperation.getInput().stream()
                                .map(SemanticValidator::normalize)
                                .collect(Collectors.toSet());
                    }
                } else if ("scheduleevent".equals(actionType)) {
                    String scheduledEventKey = normalize(action.getEvent());
                    if (scheduledEventKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " event is required for scheduleEvent");
                        continue;
                    }
                    if (!eventsByName.containsKey(scheduledEventKey)) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " schedule event not found: " + action.getEvent());
                    }
                    Long delaySeconds = action.getDelaySeconds();
                    if (delaySeconds == null) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " delaySeconds is required for scheduleEvent");
                    } else if (delaySeconds < 0) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " delaySeconds must be >= 0 for scheduleEvent");
                    }
                } else {
                    errors.add("Orchestration " + name + ": unsupported " + actionLabel + " type '"
                            + action.getType() + "'. Allowed: create, callCapability, scheduleEvent");
                    continue;
                }

                for (Map.Entry<String, String> mapping : action.getMap().entrySet()) {
                    String targetField = mapping.getKey();
                    String targetFieldKey = normalize(targetField);
                    if (targetFieldKey.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " map target field must be non-blank");
                        continue;
                    }
                    if (allowedTargetKeys != null
                            && !allowedTargetKeys.isEmpty()
                            && !allowedTargetKeys.contains(targetFieldKey)) {
                        errors.add("Orchestration " + name + ": " + actionLabel
                                + " map references unknown target field " + targetField);
                    }

                    String source = mapping.getValue();
                    if (source == null || source.isBlank()) {
                        errors.add("Orchestration " + name + ": " + actionLabel + " map source for field "
                                + targetField + " must be non-blank");
                        continue;
                    }
                    String trimmed = source.trim();
                    if (trimmed.startsWith("$event.")) {
                        String path = trimmed.substring("$event.".length()).trim();
                        if (path.isBlank()) {
                            errors.add("Orchestration " + name + ": " + actionLabel + " map source for field "
                                    + targetField + " has invalid event path");
                            continue;
                        }
                        String rootField = normalize(path.split("\\.")[0]);
                        if (!eventPayloadFields.isEmpty() && !eventPayloadFields.contains(rootField)) {
                            errors.add("Orchestration " + name + ": " + actionLabel + " map source '" + source
                                    + "' references unknown event payload field " + rootField);
                        }
                    }
                }
            }
        }
    }

    /**
     * R4.2 (roadmap): widened from requiring "exactly one {@code ==}/{@code !=} comparison" -- the
     * exact shape {@code GeneratedCrudRuntimeSupport.evaluateOrchestrationCondition}'s legacy
     * hand-rolled matcher supported -- to the full {@link ComputedExpression} grammar ({@code &&},
     * {@code ||}, {@code >}, arithmetic), now that the runtime tries {@link ComputedExpression}
     * first and falls back to that legacy matcher only for what it cannot parse. A condition that
     * fails to parse is refused here rather than reaching a running app and silently skipping every
     * orchestration action forever (a blank/unparseable condition previously fell through to
     * {@code shouldExecuteOrchestration}'s permissive default). The "at least one event payload
     * field" rule survives unchanged, generalized from the two comparison operands to however many
     * {@link ComputedExpression#referencedFields} finds -- literals (quoted strings, numbers,
     * true/false/null) never appear there since the parser resolves them to AST literals, not
     * variable references, so {@link #isConditionLiteral} stays reachable only for defensive symmetry
     * inside {@link #validateConditionOperand}.
     */
    private static void validateOrchestrationCondition(
            String orchestrationName,
            String rawCondition,
            Set<String> eventPayloadFields,
            List<String> errors
    ) {
        if (rawCondition == null || rawCondition.isBlank()) {
            return;
        }
        String condition = rawCondition.trim();
        if ("true".equalsIgnoreCase(condition) || "false".equalsIgnoreCase(condition)) {
            return;
        }

        Set<String> referencedFields;
        try {
            referencedFields = ComputedExpression.referencedFields(condition);
        } catch (ComputedExpression.ExpressionException syntaxError) {
            errors.add("Orchestration " + orchestrationName
                    + ": condition has invalid syntax: " + condition + " (" + syntaxError.getMessage() + ")");
            return;
        }

        boolean referencesEventField = false;
        for (String token : referencedFields) {
            if (validateConditionOperand(orchestrationName, token, eventPayloadFields, errors)) {
                referencesEventField = true;
            }
        }
        if (!referencesEventField) {
            errors.add("Orchestration " + orchestrationName
                    + ": condition must reference at least one event payload field");
        }
    }

    private static boolean validateConditionOperand(
            String orchestrationName,
            String operand,
            Set<String> eventPayloadFields,
            List<String> errors
    ) {
        if (operand == null || operand.isBlank()) {
            return false;
        }
        String token = operand.trim();
        if (isConditionLiteral(token)) {
            return false;
        }
        if (token.startsWith("$event.")) {
            String path = token.substring("$event.".length()).trim();
            if (path.isBlank()) {
                errors.add("Orchestration " + orchestrationName
                        + ": condition references an invalid event payload path");
                return false;
            }
            String rootField = normalize(path.split("\\.")[0]);
            if (!eventPayloadFields.isEmpty() && !eventPayloadFields.contains(rootField)) {
                errors.add("Orchestration " + orchestrationName + ": condition references unknown event payload field "
                        + rootField);
            }
            return true;
        }

        String rootField = normalize(token.split("\\.")[0]);
        if (rootField.matches("[a-z_][a-z0-9_]*")) {
            if (!eventPayloadFields.isEmpty() && !eventPayloadFields.contains(rootField)) {
                errors.add("Orchestration " + orchestrationName + ": condition references unknown event payload field "
                        + rootField);
                return false;
            }
            return true;
        }

        errors.add("Orchestration " + orchestrationName
                + ": unsupported condition operand '" + operand + "'");
        return false;
    }

    private static boolean isConditionLiteral(String token) {
        if (token == null) {
            return false;
        }
        String trimmed = token.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.length() >= 2;
        }
        if ("null".equalsIgnoreCase(trimmed)
                || "true".equalsIgnoreCase(trimmed)
                || "false".equalsIgnoreCase(trimmed)) {
            return true;
        }
        return trimmed.matches("-?\\d+(\\.\\d+)?");
    }

}
