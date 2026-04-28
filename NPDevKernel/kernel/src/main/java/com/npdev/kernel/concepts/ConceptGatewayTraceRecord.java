package com.npdev.kernel.concepts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ConceptGatewayTraceRecord(
        long timestampMs,
        ConceptGatewayOperation operation,
        String conceptName,
        String id,
        String tenantId,
        String actorId,
        String outcome,
        String reasonCode,
        List<String> ruleProfiles,
        List<String> defaultsApplied,
        List<String> derivedValuesApplied,
        String lifecycleTransition,
        String explanation,
        Map<String, Object> details
) {
    public ConceptGatewayTraceRecord {
        if (timestampMs <= 0L) {
            timestampMs = System.currentTimeMillis();
        }
        if (operation == null) {
            throw new IllegalArgumentException("operation must be non-null");
        }
        conceptName = normalizeRequired(conceptName, "conceptName");
        id = normalizeOptional(id);
        tenantId = normalizeOptional(tenantId);
        actorId = normalizeOptional(actorId);
        outcome = normalizeRequired(outcome, "outcome");
        reasonCode = normalizeOptional(reasonCode);
        ruleProfiles = copyStrings(ruleProfiles);
        defaultsApplied = copyStrings(defaultsApplied);
        derivedValuesApplied = copyStrings(derivedValuesApplied);
        lifecycleTransition = normalizeOptional(lifecycleTransition);
        explanation = normalizeOptional(explanation);
        details = copyDetails(details);
    }

    public static ConceptGatewayTraceRecord fromDecision(
            ConceptGatewayRequestContext request,
            String outcome,
            String reasonCode,
            ConceptSemanticDecision decision
    ) {
        ConceptSemanticDecision safeDecision = decision == null
                ? ConceptSemanticDecision.allow(request.data())
                : decision;
        return new ConceptGatewayTraceRecord(
                System.currentTimeMillis(),
                request.operation(),
                request.conceptName(),
                request.id(),
                request.tenantId(),
                request.executionContext().actorId(),
                outcome,
                reasonCode,
                safeDecision.ruleProfiles(),
                safeDecision.defaultsApplied(),
                safeDecision.derivedValuesApplied(),
                safeDecision.lifecycleTransition(),
                explain(outcome, reasonCode, safeDecision),
                safeDecision.details()
        );
    }

    private static String explain(String outcome, String reasonCode, ConceptSemanticDecision decision) {
        String message = decision == null ? null : decision.message();
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (reasonCode != null && !reasonCode.isBlank()) {
            return reasonCode;
        }
        return outcome;
    }

    private static List<String> copyStrings(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : input) {
            String normalized = normalizeOptional(value);
            if (normalized != null) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> copyDetails(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
