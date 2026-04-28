package com.finalexec.config;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.kernel.concepts.ConceptGatewaySemanticPolicy;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class RuntimeConceptGatewaySemanticPolicies {

    private RuntimeConceptGatewaySemanticPolicies() {
    }

    public static ConceptGatewaySemanticPolicy fromCompiledModel(CompiledModel compiledModel) {
        if (compiledModel == null) {
            return ConfiguredConceptGatewaySemanticPolicy.empty();
        }

        List<ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition> definitions = new ArrayList<>();
        for (CompiledConcept concept : compiledModel.getConcepts()) {
            definitions.add(toConceptDefinition(concept));
        }
        return new ConfiguredConceptGatewaySemanticPolicy(definitions);
    }

    private static ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition toConceptDefinition(CompiledConcept concept) {
        Map<String, ConfiguredConceptGatewaySemanticPolicy.FieldDefinition> fields = new LinkedHashMap<>();
        for (CompiledField field : concept.getFields()) {
            fields.put(field.getName(), toFieldDefinition(field));
        }
        return new ConfiguredConceptGatewaySemanticPolicy.ConceptDefinition(
                concept.getName(),
                fields,
                invariants(concept),
                lifecycle(concept.getLifecycle()),
                hiddenFields(fields)
        );
    }

    private static ConfiguredConceptGatewaySemanticPolicy.FieldDefinition toFieldDefinition(CompiledField field) {
        CompiledSchema schema = field.getSchema();
        List<String> enumValues = new ArrayList<>();
        enumValues.addAll(field.getEnumValues());
        if (schema != null) {
            for (String enumValue : schema.getEnumValues()) {
                if (!enumValues.contains(enumValue)) {
                    enumValues.add(enumValue);
                }
            }
        }

        return new ConfiguredConceptGatewaySemanticPolicy.FieldDefinition(
                field.getName(),
                field.isRequired(),
                enumValues,
                schema == null ? null : schema.getDefaultValue(),
                schema == null ? null : schema.getDefaultExpression(),
                schema == null ? null : schema.getDerivedExpression(),
                false
        );
    }

    private static List<ConfiguredConceptGatewaySemanticPolicy.InvariantDefinition> invariants(CompiledConcept concept) {
        List<ConfiguredConceptGatewaySemanticPolicy.InvariantDefinition> invariants = new ArrayList<>();
        int index = 1;
        for (String expression : concept.getExpressionInvariants()) {
            if (hasText(expression)) {
                invariants.add(new ConfiguredConceptGatewaySemanticPolicy.InvariantDefinition(
                        "expressionInvariant" + index,
                        expression
                ));
                index++;
            }
        }
        for (CompiledInvariant invariant : concept.getInvariants()) {
            if (hasText(invariant.getExpression())) {
                invariants.add(new ConfiguredConceptGatewaySemanticPolicy.InvariantDefinition(
                        hasText(invariant.getRef()) ? invariant.getRef() : "compiledInvariant" + index,
                        invariant.getExpression()
                ));
                index++;
            }
        }
        return List.copyOf(invariants);
    }

    private static ConfiguredConceptGatewaySemanticPolicy.LifecycleDefinition lifecycle(CompiledLifecycle lifecycle) {
        if (lifecycle == null || !hasText(lifecycle.getStatusField())) {
            return null;
        }

        List<String> states = new ArrayList<>();
        String initial = null;
        for (CompiledStateMachineState state : lifecycle.getStates()) {
            if (!hasText(state.getValue())) {
                continue;
            }
            states.add(state.getValue());
            if (state.isInitial()) {
                initial = state.getValue();
            }
        }
        if (initial == null && !states.isEmpty()) {
            initial = states.get(0);
        }

        List<ConfiguredConceptGatewaySemanticPolicy.StateTransition> transitions = new ArrayList<>();
        for (CompiledStateTransition transition : lifecycle.getTransitions()) {
            if (hasText(transition.getFrom()) && hasText(transition.getTo())) {
                transitions.add(new ConfiguredConceptGatewaySemanticPolicy.StateTransition(
                        transition.getFrom(),
                        transition.getTo()
                ));
            }
        }

        return ConfiguredConceptGatewaySemanticPolicy.LifecycleDefinition.of(
                lifecycle.getStatusField(),
                initial,
                states,
                transitions
        );
    }

    private static LinkedHashSet<String> hiddenFields(
            Map<String, ConfiguredConceptGatewaySemanticPolicy.FieldDefinition> fields
    ) {
        LinkedHashSet<String> hiddenFields = new LinkedHashSet<>();
        for (ConfiguredConceptGatewaySemanticPolicy.FieldDefinition field : fields.values()) {
            if (field.hidden()) {
                hiddenFields.add(field.name());
            }
        }
        return hiddenFields;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
