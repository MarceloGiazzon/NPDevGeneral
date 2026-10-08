package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.AgentAccessAst;
import com.npdev.dsl.v1.ast.AgentAccessExposureAst;
import com.npdev.dsl.v1.ast.AgentAccessPhotoIntakeAst;
import com.npdev.dsl.v1.ast.AggregateAst;
import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * AGENT-1: structural checks for the optional top-level {@code agentAccess} block -- each exposure
 * names exactly one of a real concept / a real flow / a real aggregate, never exposes a
 * {@code sensitive} field, never mixes concept-only ({@code operations}/{@code fields}) with a flow
 * exposure, an aggregate exposure only offers {@code get}/{@code save}, and no concept or aggregate
 * is exposed twice. {@code roles} is deliberately never validated here: app roles are data (identity
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

        Set<String> aggregateNames = new HashSet<>();
        for (AggregateAst aggregate : modelAst.getAggregates()) {
            aggregateNames.add(normalize(aggregate.name()));
        }

        Set<String> conceptsSeen = new HashSet<>();
        Set<String> aggregatesSeen = new HashSet<>();
        int index = 0;
        for (AgentAccessExposureAst exposure : agentAccess.getExpose()) {
            String here = "agentAccess.expose[" + index + "]";
            boolean hasConcept = hasText(exposure.getConcept());
            boolean hasFlow = hasText(exposure.getFlow());
            boolean hasAggregate = hasText(exposure.getAggregate());
            if ((hasConcept ? 1 : 0) + (hasFlow ? 1 : 0) + (hasAggregate ? 1 : 0) != 1) {
                errors.add(here + ": exactly one of concept / flow / aggregate must be set");
                index++;
                continue;
            }
            if (hasAggregate) {
                validateAggregateExposure(exposure, here, aggregateNames, aggregatesSeen, errors);
                index++;
                continue;
            }
            if (hasConcept && exposure.getOperations().contains("save")) {
                errors.add(here + ": operation 'save' is aggregate-only -- a concept exposure uses create/update"
                        + " -- suggestedFix: Replace 'save' with 'create'/'update', or expose the aggregate instead.");
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
        validatePhotoIntake(modelAst, agentAccess, entitiesByLower, errors);
    }

    /** P8: an aggregate exposure reads ({@code get}) or saves ({@code save}) the whole tree -- root plus
     *  owned collections -- in one call, through the same commit path the workbench's Save uses. */
    private static void validateAggregateExposure(AgentAccessExposureAst exposure, String here,
            Set<String> aggregateNames, Set<String> aggregatesSeen, List<String> errors) {
        if (!aggregateNames.contains(normalize(exposure.getAggregate()))) {
            errors.add(here + ": aggregate '" + exposure.getAggregate() + "' does not resolve to a declared aggregate"
                    + " -- suggestedFix: Name an aggregate declared in aggregates[], or remove this exposure.");
        } else if (!aggregatesSeen.add(normalize(exposure.getAggregate()))) {
            errors.add(here + ": aggregate '" + exposure.getAggregate() + "' is exposed more than once"
                    + " -- suggestedFix: Merge the duplicate exposures of this aggregate into one entry.");
        }
        for (String operation : exposure.getOperations()) {
            if (!operation.equals("get") && !operation.equals("save")) {
                errors.add(here + ": operation '" + operation + "' is not valid on an aggregate exposure (get, save)"
                        + " -- suggestedFix: Use 'get' and/or 'save', or expose the root concept for list/create/update/delete.");
            }
        }
        if (!exposure.getFields().isEmpty()) {
            errors.add(here + ": fields is concept-only, not valid on an aggregate exposure"
                    + " -- suggestedFix: Remove 'fields' from this aggregate exposure.");
        }
    }

    /** P8 (G5): the photo-intake target is a real concept with a real file field, the procedure (if
     *  any) is declared and reachable over an aggregate rooted at that concept, and a photo channel is on. */
    private static void validatePhotoIntake(ModelAst modelAst, AgentAccessAst agentAccess,
            Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        AgentAccessPhotoIntakeAst intake = agentAccess.getPhotoIntake();
        if (intake == null) {
            return;
        }
        String here = "agentAccess.photoIntake";
        if (agentAccess.getChannels() == null
                || !(agentAccess.getChannels().isTelegram() || agentAccess.getChannels().isWhatsapp())) {
            errors.add(here + ": needs channels.telegram or channels.whatsapp enabled -- photos only arrive on a chat channel"
                    + " -- suggestedFix: Enable a chat channel, or remove photoIntake.");
        }
        ConceptAst concept = hasText(intake.getConcept()) ? entitiesByLower.get(normalize(intake.getConcept())) : null;
        if (concept == null) {
            errors.add(here + ".concept: '" + intake.getConcept() + "' does not resolve to a declared concept"
                    + " -- suggestedFix: Name the concept a photo should become.");
            return;
        }
        Map<String, FieldAst> fields = new HashMap<>();
        for (FieldAst field : concept.getFields()) {
            fields.put(normalize(field.getName()), field);
        }
        FieldAst image = hasText(intake.getImageField()) ? fields.get(normalize(intake.getImageField())) : null;
        if (image == null || !"file".equalsIgnoreCase(image.getType())) {
            errors.add(here + ".imageField: '" + intake.getImageField() + "' must be a file field of " + concept.getName()
                    + " -- suggestedFix: Name the concept's type:file field that holds the photo.");
        }
        if (hasText(intake.getCaptionField())) {
            FieldAst caption = fields.get(normalize(intake.getCaptionField()));
            if (caption == null || !"string".equalsIgnoreCase(caption.getType())) {
                errors.add(here + ".captionField: '" + intake.getCaptionField() + "' must be a string field of "
                        + concept.getName() + " -- suggestedFix: Name a string field, or drop captionField.");
            }
        }
        for (String key : intake.getDefaults().keySet()) {
            FieldAst field = fields.get(normalize(key));
            if (field == null) {
                errors.add(here + ".defaults: '" + key + "' is not a field of " + concept.getName()
                        + " -- suggestedFix: Use a field name declared on this concept, or drop the entry.");
            } else if (field.isSensitive()) {
                errors.add(here + ".defaults: '" + key + "' is sensitive -- agents never write sensitive fields"
                        + " -- suggestedFix: Remove this default.");
            }
        }
        if (hasText(intake.getProcedure())) {
            boolean declared = modelAst.getProcedures().stream()
                    .anyMatch(procedure -> normalize(procedure.name()).equals(normalize(intake.getProcedure())));
            if (!declared) {
                errors.add(here + ".procedure: '" + intake.getProcedure() + "' does not resolve to a declared procedure"
                        + " -- suggestedFix: Name a procedure declared in procedures[], or drop procedure.");
            }
            boolean rooted = modelAst.getAggregates().stream()
                    .anyMatch(aggregate -> normalize(aggregate.root()).equals(normalize(concept.getName())));
            if (!rooted) {
                errors.add(here + ".procedure: runs over a draft through an aggregate, but no aggregate has root '"
                        + concept.getName() + "' -- suggestedFix: Declare an aggregate rooted at " + concept.getName()
                        + ", or drop procedure.");
            }
        }
    }
}
