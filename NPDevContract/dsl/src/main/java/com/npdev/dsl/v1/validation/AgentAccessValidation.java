package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.AgentAccessAst;
import com.npdev.dsl.v1.ast.AgentAccessExposureAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * AGENT-1: structural checks for the optional top-level {@code agentAccess} block -- each exposure
 * names exactly one of a real concept / a real flow, never exposes a {@code sensitive} field, never
 * mixes concept-only ({@code operations}/{@code fields}) with a flow exposure, and no concept is
 * exposed twice. {@code roles} is deliberately never validated here: app roles are data (identity
 * pack {@code Role} rows), not model declarations.
 */
final class AgentAccessValidation {

    private AgentAccessValidation() {
    }

    static void validateAgentAccess(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        AgentAccessAst agentAccess = modelAst.getAgentAccess();
        if (agentAccess == null) {
            return;
        }
        Set<String> flowNames = new HashSet<>();
        for (FlowAst flow : modelAst.getFlows()) {
            flowNames.add(normalize(flow.getName()));
        }

        Set<String> conceptsSeen = new HashSet<>();
        int index = 0;
        for (AgentAccessExposureAst exposure : agentAccess.getExpose()) {
            String here = "agentAccess.expose[" + index + "]";
            boolean hasConcept = hasText(exposure.getConcept());
            boolean hasFlow = hasText(exposure.getFlow());
            if (hasConcept == hasFlow) {
                errors.add(here + ": exactly one of concept / flow must be set");
                index++;
                continue;
            }
            if (hasConcept) {
                ConceptAst concept = entitiesByLower.get(normalize(exposure.getConcept()));
                if (concept == null) {
                    errors.add(here + ": concept '" + exposure.getConcept() + "' does not resolve to a declared concept"
                            + " -- suggestedFix: Name a concept declared in concepts[] (or a composed pack's), "
                            + "or remove this exposure.");
                } else {
                    if (!conceptsSeen.add(normalize(exposure.getConcept()))) {
                        errors.add(here + ": concept '" + exposure.getConcept() + "' is exposed more than once"
                                + " -- suggestedFix: Merge the duplicate exposures of this concept into one entry.");
                    }
                    Set<String> fieldNames = new HashSet<>();
                    for (FieldAst field : concept.getFields()) {
                        fieldNames.add(normalize(field.getName()));
                    }
                    for (String fieldName : exposure.getFields()) {
                        if (!fieldNames.contains(normalize(fieldName))) {
                            errors.add(here + ": fields entry '" + fieldName + "' is not a field of concept '"
                                    + exposure.getConcept() + "'"
                                    + " -- suggestedFix: Use a field name declared on this concept, or drop the entry.");
                            continue;
                        }
                        for (FieldAst field : concept.getFields()) {
                            if (normalize(field.getName()).equals(normalize(fieldName)) && field.isSensitive()) {
                                errors.add(here + ": fields entry '" + fieldName
                                        + "' is sensitive -- sensitive fields are never exposed to agents"
                                        + " -- suggestedFix: Remove this field from the exposure's fields list.");
                            }
                        }
                    }
                }
            } else {
                if (!flowNames.contains(normalize(exposure.getFlow()))) {
                    errors.add(here + ": flow '" + exposure.getFlow() + "' does not resolve to a declared flow"
                            + " -- suggestedFix: Name a flow declared in flows[], or remove this exposure.");
                }
                if (!exposure.getOperations().isEmpty()) {
                    errors.add(here + ": operations is concept-only, not valid on a flow exposure"
                            + " -- suggestedFix: Remove 'operations' from this flow exposure.");
                }
                if (!exposure.getFields().isEmpty()) {
                    errors.add(here + ": fields is concept-only, not valid on a flow exposure"
                            + " -- suggestedFix: Remove 'fields' from this flow exposure.");
                }
            }
            index++;
        }
    }
}
