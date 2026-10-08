package com.finalexec.agent;

import com.npdev.dsl.v1.compiled.CompiledAgentAccessPhotoIntake;
import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * P8 (G5): the pure half of {@code agentAccess.photoIntake} -- which concept/aggregate/route a photo
 * targets, and how the new row's draft is assembled. Precedence, lowest first: {@code defaults}
 * ({@code "$user.username"} substituted), then the procedure's answer (top-level keys naming a concept
 * field, plus the same one map deep -- a step target such as {@code suggestion}), then the caption,
 * then the uploaded image handle. The REST round trips live in {@link AgentConversationService}.
 */
final class AgentPhotoIntake {

    static final String USER_TOKEN = "$user.username";

    /** Everything a photo needs resolved from the model; empty when photo intake is off. */
    record Target(CompiledAgentAccessPhotoIntake intake, CompiledConcept concept, String route, String aggregate) {
    }

    private AgentPhotoIntake() {
    }

    static Optional<Target> target(CompiledModel model) {
        if (model == null || model.getAgentAccess() == null || model.getAgentAccess().getPhotoIntake() == null) {
            return Optional.empty();
        }
        CompiledAgentAccessPhotoIntake intake = model.getAgentAccess().getPhotoIntake();
        CompiledConcept concept = model.getConcepts().stream()
                .filter(c -> c.getName().equalsIgnoreCase(intake.getConcept())).findFirst().orElse(null);
        if (concept == null) {
            return Optional.empty();
        }
        String aggregate = model.getAggregates().stream()
                .filter(a -> a.root() != null && a.root().equalsIgnoreCase(concept.getName()))
                .map(CompiledAggregate::name).findFirst().orElse(null);
        return Optional.of(new Target(intake, concept,
                SqlIdentifierSupport.aliasPreservingTableName(concept, model.getContexts()), aggregate));
    }

    /** The draft sent to the procedure: the image handle plus the caption, nothing else. */
    static Map<String, Object> procedureInput(Target target, Map<String, Object> imageHandle, String caption) {
        Map<String, Object> draft = new LinkedHashMap<>();
        if (target.intake().getCaptionField() != null && caption != null && !caption.isBlank()) {
            draft.put(fieldName(target, target.intake().getCaptionField()), caption.trim());
        }
        draft.put(fieldName(target, target.intake().getImageField()), imageHandle);
        return draft;
    }

    static Map<String, Object> draft(Target target, String username, Map<String, Object> imageHandle, String caption,
            Map<String, Object> procedureState) {
        Map<String, Object> draft = new LinkedHashMap<>();
        target.intake().getDefaults().forEach((key, value) -> {
            String field = fieldName(target, key);
            if (field != null) {
                draft.put(field, USER_TOKEN.equals(value) ? username : value);
            }
        });
        if (procedureState != null) {
            for (Object nested : procedureState.values()) {
                if (nested instanceof Map<?, ?> map) {
                    putFields(target, map, draft);
                }
            }
            putFields(target, procedureState, draft);
        }
        draft.putAll(procedureInput(target, imageHandle, caption));
        return draft;
    }

    /** Required, non-id fields still without a value -- asked for before a confirm button is offered. */
    static List<String> missingRequired(Target target, Map<String, Object> draft) {
        List<String> missing = new ArrayList<>();
        for (CompiledField field : target.concept().getFields()) {
            Object value = draft.get(field.getName());
            if (field.isRequired() && !field.isId() && (value == null || (value instanceof String s && s.isBlank()))) {
                missing.add(field.getName());
            }
        }
        return missing;
    }

    private static void putFields(Target target, Map<?, ?> source, Map<String, Object> draft) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String field = fieldName(target, String.valueOf(entry.getKey()));
            if (field != null && entry.getValue() != null && !(entry.getValue() instanceof Map<?, ?>)
                    && !isIdField(target, field)) {
                draft.put(field, entry.getValue());
            }
        }
    }

    private static boolean isIdField(Target target, String field) {
        return target.concept().getFields().stream().anyMatch(f -> f.getName().equals(field) && f.isId());
    }

    /** The concept's own spelling of {@code name}, or null when it is not one of its fields. */
    private static String fieldName(Target target, String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        return target.concept().getFields().stream().map(CompiledField::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).equals(wanted)).findFirst().orElse(null);
    }
}
