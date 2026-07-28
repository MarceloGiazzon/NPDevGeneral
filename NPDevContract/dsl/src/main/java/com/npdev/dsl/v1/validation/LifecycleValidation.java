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

import com.npdev.dsl.v1.validation.ConceptValidation.EffectiveEntity;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;
import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;

/**
 * Semantic validation for a concept's declarative lifecycle state machine: status field shape,
 * declared states (exactly one initial), transitions (from/to/guard/event/requiredPayload).
 *
 * <p>Split out of {@code SemanticValidator} (T1.15) as a sub-boundary of the Concept section
 * (see {@link ConceptValidation}).
 */
final class LifecycleValidation {

    private LifecycleValidation() {
    }

    static void validateLifecycle(ConceptAst entity, EffectiveEntity effective, List<String> errors) {
        LifecycleAst lifecycle = entity.getLifecycle();
        if (lifecycle == null) {
            return;
        }

        String conceptName = entity.getName();
        String statusFieldName = lifecycle.getStatusField() == null || lifecycle.getStatusField().isBlank()
                ? "status"
                : lifecycle.getStatusField().trim();

        Map<String, FieldAst> fieldsByName = new HashMap<>();
        for (FieldAst field : effective.fields()) {
            fieldsByName.put(normalize(field.getName()), field);
        }

        FieldAst statusField = fieldsByName.get(normalize(statusFieldName));
        if (statusField == null) {
            errors.add("Entity " + conceptName + " lifecycle: statusField '" + statusFieldName + "' not found");
            return;
        }
        if (!"enum".equals(normalize(statusField.getType()))) {
            errors.add("Entity " + conceptName + " lifecycle: statusField '" + statusFieldName
                    + "' must be enum");
            return;
        }

        Set<String> statusValues = statusField.getEnumValues().stream()
                .map(SemanticValidator::normalize)
                .collect(Collectors.toSet());
        if (statusValues.isEmpty()) {
            errors.add("Entity " + conceptName + " lifecycle: statusField '" + statusFieldName
                    + "' must declare enumValues");
            return;
        }

        Set<String> declaredStates = new LinkedHashSet<>();
        int initialStateCount = 0;
        for (StateMachineStateAst state : lifecycle.getStates()) {
            if (state == null) {
                continue;
            }
            String value = state.getValue() == null ? "" : state.getValue().trim();
            if (value.isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle: state value must be non-blank");
                continue;
            }
            String valueKey = normalize(value);
            if (!statusValues.contains(valueKey)) {
                errors.add("Entity " + conceptName + " lifecycle: state '" + value
                        + "' is not a valid value of status field '" + statusFieldName + "'");
            }
            if (!declaredStates.add(valueKey)) {
                errors.add("Entity " + conceptName + " lifecycle: duplicate state '" + value + "'");
            }
            if (state.getLabel() != null && state.getLabel().isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle state '" + value + "': label must be non-blank");
            }
            if (state.isInitial()) {
                initialStateCount++;
            }
            for (Map.Entry<String, String> metadataEntry : state.getMetadata().entrySet()) {
                String key = metadataEntry.getKey() == null ? "" : metadataEntry.getKey().trim();
                if (key.isBlank()) {
                    errors.add("Entity " + conceptName + " lifecycle state '" + value + "': metadata keys must be non-blank");
                }
            }
        }
        if (!lifecycle.getStates().isEmpty() && initialStateCount != 1) {
            errors.add("Entity " + conceptName + " lifecycle: states must declare exactly one initial state");
        }

        if (lifecycle.getTransitions().isEmpty()) {
            errors.add("Entity " + conceptName + " lifecycle: transitions must declare at least one transition");
            return;
        }

        Set<String> transitionPairs = new HashSet<>();
        for (StateTransitionAst transition : lifecycle.getTransitions()) {
            if (transition == null) {
                continue;
            }
            String from = transition.getFrom() == null ? "" : transition.getFrom().trim();
            String to = transition.getTo() == null ? "" : transition.getTo().trim();
            if (from.isBlank() || to.isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle: transition requires non-blank from/to");
                continue;
            }
            String fromKey = normalize(from);
            String toKey = normalize(to);
            if (!statusValues.contains(fromKey)) {
                errors.add("Entity " + conceptName + " lifecycle: transition from '" + from
                        + "' is not a valid value of status field '" + statusFieldName + "'");
            }
            if (!statusValues.contains(toKey)) {
                errors.add("Entity " + conceptName + " lifecycle: transition to '" + to
                        + "' is not a valid value of status field '" + statusFieldName + "'");
            }
            String pairKey = fromKey + "->" + toKey;
            if (!transitionPairs.add(pairKey)) {
                errors.add("Entity " + conceptName + " lifecycle: duplicate transition " + from + " -> " + to);
            }
            if (!declaredStates.isEmpty()) {
                if (!declaredStates.contains(fromKey)) {
                    errors.add("Entity " + conceptName + " lifecycle: transition from '" + from
                            + "' is not declared in lifecycle.states");
                }
                if (!declaredStates.contains(toKey)) {
                    errors.add("Entity " + conceptName + " lifecycle: transition to '" + to
                            + "' is not declared in lifecycle.states");
                }
            }
            if (transition.getEvent() != null && transition.getEvent().isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                        + ": event must be non-blank when provided");
            }
            if (transition.getGuard() != null) {
                if (transition.getGuard().isBlank()) {
                    errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                            + ": guard must be non-blank when provided");
                } else if (!isSupportedLifecycleGuard(transition.getGuard())) {
                    errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                            + ": guard uses an unsupported expression format");
                }
            }
            if (transition.getActionLabel() != null && transition.getActionLabel().isBlank()) {
                errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                        + ": actionLabel must be non-blank when provided");
            }
            for (Map.Entry<String, String> metadataEntry : transition.getMetadata().entrySet()) {
                String key = metadataEntry.getKey() == null ? "" : metadataEntry.getKey().trim();
                if (key.isBlank()) {
                    errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                            + ": metadata keys must be non-blank");
                }
            }
            for (String requiredField : transition.getRequiredPayload()) {
                if (!fieldsByName.containsKey(normalize(requiredField))) {
                    errors.add("Entity " + conceptName + " lifecycle transition " + from + " -> " + to
                            + ": requiredPayload references unknown field " + requiredField);
                }
            }
        }
    }

    private static boolean isSupportedLifecycleGuard(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return true;
        }
        int notEqualsIndex = trimmed.indexOf("!=");
        if (notEqualsIndex >= 0) {
            return isSupportedLifecycleGuardToken(trimmed.substring(0, notEqualsIndex))
                    && isSupportedLifecycleGuardToken(trimmed.substring(notEqualsIndex + 2));
        }
        int equalsIndex = trimmed.indexOf("==");
        if (equalsIndex >= 0) {
            return isSupportedLifecycleGuardToken(trimmed.substring(0, equalsIndex))
                    && isSupportedLifecycleGuardToken(trimmed.substring(equalsIndex + 2));
        }
        return isSupportedLifecycleGuardToken(trimmed);
    }

    private static boolean isSupportedLifecycleGuardToken(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        if ("null".equalsIgnoreCase(trimmed)
                || "true".equalsIgnoreCase(trimmed)
                || "false".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.length() >= 2;
        }
        if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
            return true;
        }
        return trimmed.matches("(\\$payload|\\$current|\\$next)(\\.[A-Za-z_][A-Za-z0-9_.]*)?")
                || trimmed.matches("[A-Za-z_][A-Za-z0-9_.]*");
    }

}
