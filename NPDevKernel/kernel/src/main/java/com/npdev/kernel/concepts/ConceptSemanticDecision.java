package com.npdev.kernel.concepts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ConceptSemanticDecision(
        boolean allowed,
        String code,
        String message,
        Map<String, Object> data,
        List<String> ruleProfiles,
        List<String> defaultsApplied,
        List<String> derivedValuesApplied,
        String lifecycleTransition,
        Map<String, Object> details
) {
    public ConceptSemanticDecision {
        code = normalize(code);
        message = normalize(message);
        data = copyData(data);
        ruleProfiles = copyStrings(ruleProfiles);
        defaultsApplied = copyStrings(defaultsApplied);
        derivedValuesApplied = copyStrings(derivedValuesApplied);
        lifecycleTransition = normalize(lifecycleTransition);
        details = copyData(details);
    }

    public static ConceptSemanticDecision allow(Map<String, Object> data) {
        return new ConceptSemanticDecision(true, "allowed", "allowed", data, List.of(), List.of(), List.of(), null, Map.of());
    }

    public static ConceptSemanticDecision deny(String code, String message) {
        return deny(code, message, Map.of());
    }

    public static ConceptSemanticDecision deny(String code, String message, Map<String, Object> details) {
        return new ConceptSemanticDecision(false, code, message, Map.of(), List.of(), List.of(), List.of(), null, details);
    }

    public ConceptSemanticDecision withRuleProfiles(List<ConceptRuleProfile> profiles) {
        List<String> profileNames = profiles == null
                ? List.of()
                : profiles.stream().map(ConceptRuleProfile::canonicalName).toList();
        return new ConceptSemanticDecision(
                allowed,
                code,
                message,
                data,
                profileNames,
                defaultsApplied,
                derivedValuesApplied,
                lifecycleTransition,
                details
        );
    }

    public ConceptSemanticDecision merge(ConceptSemanticDecision next) {
        if (next == null) {
            return this;
        }
        Map<String, Object> mergedData = next.data().isEmpty() ? data : next.data();
        List<String> mergedProfiles = mergeStrings(ruleProfiles, next.ruleProfiles());
        List<String> mergedDefaults = mergeStrings(defaultsApplied, next.defaultsApplied());
        List<String> mergedDerived = mergeStrings(derivedValuesApplied, next.derivedValuesApplied());
        Map<String, Object> mergedDetails = new LinkedHashMap<>(details);
        mergedDetails.putAll(next.details());
        return new ConceptSemanticDecision(
                allowed && next.allowed(),
                next.code() == null ? code : next.code(),
                next.message() == null ? message : next.message(),
                mergedData,
                mergedProfiles,
                mergedDefaults,
                mergedDerived,
                next.lifecycleTransition() == null ? lifecycleTransition : next.lifecycleTransition(),
                mergedDetails
        );
    }

    private static List<String> mergeStrings(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return List.copyOf(merged);
    }

    private static Map<String, Object> copyData(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static List<String> copyStrings(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : input) {
            String normalized = normalize(value);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
