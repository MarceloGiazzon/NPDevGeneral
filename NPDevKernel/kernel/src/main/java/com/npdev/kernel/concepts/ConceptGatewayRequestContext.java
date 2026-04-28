package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ConceptGatewayRequestContext(
        ConceptGatewayOperation operation,
        String conceptName,
        String id,
        String tenantId,
        Map<String, Object> data,
        ExecutionContext executionContext,
        Optional<ConceptRecord> previousRecord
) {
    public ConceptGatewayRequestContext {
        operation = Objects.requireNonNull(operation, "operation");
        conceptName = normalizeRequired(conceptName, "conceptName");
        id = normalizeOptional(id);
        tenantId = normalizeOptional(tenantId);
        data = copyData(data);
        executionContext = executionContext == null ? ExecutionContext.anonymous() : executionContext;
        previousRecord = previousRecord == null ? Optional.empty() : previousRecord;
    }

    public ConceptGatewayRequestContext withData(Map<String, Object> nextData) {
        return new ConceptGatewayRequestContext(
                operation,
                conceptName,
                id,
                tenantId,
                nextData,
                executionContext,
                previousRecord
        );
    }

    public ConceptGatewayRequestContext withPreviousRecord(Optional<ConceptRecord> nextPreviousRecord) {
        return new ConceptGatewayRequestContext(
                operation,
                conceptName,
                id,
                tenantId,
                data,
                executionContext,
                nextPreviousRecord
        );
    }

    private static Map<String, Object> copyData(Map<String, Object> input) {
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
