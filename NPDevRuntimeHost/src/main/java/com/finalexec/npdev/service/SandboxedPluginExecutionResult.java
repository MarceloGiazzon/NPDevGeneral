package com.finalexec.npdev.service;

import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public record SandboxedPluginExecutionResult(
        Status status,
        Object value,
        String errorCode,
        String errorMessage,
        CapabilityErrorKind errorKind,
        String pluginId,
        String adapterId,
        String capability,
        String operation,
        String runtimeRef,
        String selectedPackageId,
        String selectedPackageVersion,
        String selectedPackagePath,
        String artifactKind,
        String artifactPath,
        String artifactRealizationProvider,
        String artifactRealizationStrategy,
        String realizationStrategy,
        Map<String, Object> outputEvidence,
        String correlationId,
        long timeoutMs,
        long executionDurationMs
) {

    public SandboxedPluginExecutionResult {
        status = Objects.requireNonNull(status, "status");
        errorCode = errorCode == null ? "" : errorCode.trim();
        errorMessage = errorMessage == null ? "" : errorMessage.trim();
        pluginId = normalize(pluginId);
        adapterId = normalize(adapterId);
        capability = normalize(capability);
        operation = normalize(operation);
        runtimeRef = runtimeRef == null ? "" : runtimeRef.trim();
        selectedPackageId = normalizeOptional(selectedPackageId);
        selectedPackageVersion = normalizeOptional(selectedPackageVersion);
        selectedPackagePath = normalizeOptional(selectedPackagePath);
        artifactKind = normalizeOptional(artifactKind);
        artifactPath = normalizeOptional(artifactPath);
        artifactRealizationProvider = normalizeOptional(artifactRealizationProvider);
        artifactRealizationStrategy = normalizeOptional(artifactRealizationStrategy);
        realizationStrategy = realizationStrategy == null ? "" : realizationStrategy.trim();
        outputEvidence = copyEvidence(outputEvidence);
        correlationId = correlationId == null ? "" : correlationId.trim();
        timeoutMs = Math.max(timeoutMs, 1L);
        executionDurationMs = Math.max(executionDurationMs, 0L);
        if (status == Status.SUCCESS && errorKind != null) {
            throw new IllegalArgumentException("Successful sandboxed execution must not contain an error kind");
        }
    }

    public CapabilityResult toCapabilityResult() {
        if (status == Status.SUCCESS) {
            return CapabilityResult.success(value);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pluginId", pluginId);
        details.put("adapterId", adapterId);
        details.put("capability", capability);
        details.put("operation", operation);
        details.put("runtimeRef", runtimeRef);
        details.put("selectedPackageId", selectedPackageId);
        details.put("selectedPackageVersion", selectedPackageVersion);
        details.put("selectedPackagePath", selectedPackagePath);
        details.put("artifactKind", artifactKind);
        details.put("artifactPath", artifactPath);
        details.put("artifactRealizationProvider", artifactRealizationProvider);
        details.put("artifactRealizationStrategy", artifactRealizationStrategy);
        details.put("realizationStrategy", realizationStrategy);
        details.put("outputEvidence", outputEvidence);
        details.put("correlationId", correlationId);
        details.put("timeoutMs", timeoutMs);
        details.put("executionDurationMs", executionDurationMs);
        details.put("sandboxStatus", status.name());
        return CapabilityResult.failure(
                errorCode.isBlank() ? "PLUGIN_EXECUTION_FAILED" : errorCode,
                errorMessage.isBlank() ? "Sandboxed plugin execution failed" : errorMessage,
                errorKind == null ? CapabilityErrorKind.PERMANENT : errorKind,
                Map.copyOf(details)
        );
    }

    public Summary toSummary() {
        return new Summary(
                status.name(),
                pluginId,
                adapterId,
                capability,
                operation,
                runtimeRef,
                selectedPackageId,
                selectedPackageVersion,
                selectedPackagePath,
                artifactKind,
                artifactPath,
                artifactRealizationProvider,
                artifactRealizationStrategy,
                realizationStrategy,
                outputEvidence,
                correlationId,
                errorCode,
                errorMessage,
                timeoutMs,
                executionDurationMs
        );
    }

    public enum Status {
        SUCCESS,
        DENIED,
        FAILED,
        TIMED_OUT
    }

    public record Summary(
            String status,
            String pluginId,
            String adapterId,
            String capability,
            String operation,
            String runtimeRef,
            String selectedPackageId,
            String selectedPackageVersion,
            String selectedPackagePath,
            String artifactKind,
            String artifactPath,
            String artifactRealizationProvider,
            String artifactRealizationStrategy,
            String realizationStrategy,
            Map<String, Object> outputEvidence,
            String correlationId,
            String errorCode,
            String errorMessage,
            long timeoutMs,
            long executionDurationMs
    ) {
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String normalizeOptional(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    static Map<String, Object> summarizeOutputEvidence(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        copyIfPresent(evidence, map, "adapterId");
        copyIfPresent(evidence, map, "pluginProfile");
        copyIfPresent(evidence, map, "channel");
        copyIfPresent(evidence, map, "status");
        return evidence.isEmpty() ? Map.of() : Collections.unmodifiableMap(evidence);
    }

    private static void copyIfPresent(Map<String, Object> target, Map<?, ?> source, String key) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null || !key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                continue;
            }
            Object value = entry.getValue();
            if (value == null) {
                return;
            }
            if (value instanceof String stringValue && stringValue.isBlank()) {
                return;
            }
            target.put(key, value);
            return;
        }
    }

    private static Map<String, Object> copyEvidence(Map<String, Object> outputEvidence) {
        if (outputEvidence == null || outputEvidence.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(outputEvidence));
    }
}
