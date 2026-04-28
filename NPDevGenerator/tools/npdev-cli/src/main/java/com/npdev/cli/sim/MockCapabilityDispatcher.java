package com.npdev.cli.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityDispatcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.UUID;

public final class MockCapabilityDispatcher implements CapabilityDispatcher {
    private final Map<String, OperationSimulation> simulationByKey;
    private final Map<String, AtomicInteger> attemptByKey = new ConcurrentHashMap<>();

    private MockCapabilityDispatcher(Map<String, OperationSimulation> simulationByKey) {
        this.simulationByKey = simulationByKey == null ? Map.of() : Map.copyOf(simulationByKey);
    }

    public static MockCapabilityDispatcher defaults() {
        return new MockCapabilityDispatcher(Map.of());
    }

    public static MockCapabilityDispatcher fromFile(Path path, ObjectMapper objectMapper) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(objectMapper, "objectMapper");
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            if (root == null || root.isNull()) {
                return defaults();
            }
            JsonNode operationsNode = root.has("operations") ? root.get("operations") : root;
            if (operationsNode == null || !operationsNode.isObject()) {
                throw new IllegalArgumentException("Simulation file must be a JSON object");
            }
            Map<String, OperationSimulation> simulations = new LinkedHashMap<>();
            operationsNode.fields().forEachRemaining(entry -> simulations.put(
                    entry.getKey(),
                    parseOperationSimulation(entry.getValue(), objectMapper)
            ));
            return new MockCapabilityDispatcher(simulations);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to load simulation file: " + path, exception);
        }
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        String tenantId = normalizeTenant(contextState == null ? null : contextState.get("tenantId"));
        ResolvedSimulation resolvedSimulation = resolveSimulation(tenantId, call.capability(), call.operation());
        if (resolvedSimulation == null) {
            return defaultSuccess(call, tenantId);
        }
        int attempt = attemptByKey.computeIfAbsent(resolvedSimulation.attemptKey(), key -> new AtomicInteger())
                .incrementAndGet();
        SimOutcome outcome = resolvedSimulation.simulation().resolve(call.idempotencyKey(), attempt);
        if (outcome != null && outcome.delayMs() > 0) {
            sleep(outcome.delayMs());
        }
        return toCapabilityResult(outcome, call, tenantId, attempt);
    }

    private ResolvedSimulation resolveSimulation(String tenantId, String capability, String operation) {
        List<String> candidates = new ArrayList<>();
        if (tenantId != null) {
            candidates.add(tenantId + "|" + capability + "|" + operation);
        }
        candidates.add(capability + "|" + operation);
        candidates.add(capability + "." + operation);
        candidates.add(operation);

        for (String candidate : candidates) {
            OperationSimulation simulation = simulationByKey.get(candidate);
            if (simulation != null) {
                return new ResolvedSimulation(simulation, candidate);
            }
        }
        return null;
    }

    private static CapabilityResult defaultSuccess(CapabilityCall call, String tenantId) {
        Map<String, Object> value = new LinkedHashMap<>();
        // Ensure event payloads like UserCreated have the required fields
        value.put("id", UUID.randomUUID().toString());
        value.put("email", "simulated@example.com");

        // Keep the existing metadata that other tests rely on
        value.put("simulated", true);
        value.put("tenantId", tenantId == null ? "default" : tenantId);
        value.put("capability", call.capability());
        value.put("operation", call.operation());
        value.put("adapterId", call.adapterId() == null ? "mock" : call.adapterId());

        return CapabilityResult.success(value);
    }

    private static CapabilityResult toCapabilityResult(
            SimOutcome outcome,
            CapabilityCall call,
            String tenantId,
            int attempt
    ) {
        if (outcome == null || outcome.ok()) {
            Object value = outcome == null ? null : outcome.value();
            return CapabilityResult.success(value == null ? Map.of(
                    "simulated", true,
                    "tenantId", tenantId == null ? "default" : tenantId,
                    "capability", call.capability(),
                    "operation", call.operation(),
                    "attempt", attempt
            ) : value);
        }
        SimError error = outcome.error() == null
                ? new SimError("PERMANENT", "SIM_FAIL", "Simulated failure", Map.of())
                : outcome.error();
        Map<String, Object> details = new LinkedHashMap<>(error.details() == null ? Map.of() : error.details());
        details.put("simulated", true);
        details.put("tenantId", tenantId == null ? "default" : tenantId);
        details.put("capability", call.capability());
        details.put("operation", call.operation());
        details.put("attempt", attempt);
        return CapabilityResult.failure(
                error.code() == null || error.code().isBlank() ? "SIM_FAIL" : error.code(),
                error.message() == null || error.message().isBlank() ? "Simulated failure" : error.message(),
                parseKind(error.kind()),
                Map.copyOf(details)
        );
    }

    private static OperationSimulation parseOperationSimulation(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isNull()) {
            return OperationSimulation.empty();
        }
        if (node.isObject() && (node.has("ok") || node.has("error") || node.has("value"))) {
            return new OperationSimulation(parseOutcome(node, objectMapper), List.of(), Map.of());
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Operation simulation must be an object");
        }

        SimOutcome defaultOutcome = node.has("default")
                ? parseOutcome(node.get("default"), objectMapper)
                : null;

        List<SimOutcome> sequence = new ArrayList<>();
        JsonNode sequenceNode = node.get("sequence");
        if (sequenceNode != null && sequenceNode.isArray()) {
            for (JsonNode step : sequenceNode) {
                sequence.add(parseOutcome(step, objectMapper));
            }
        }

        Map<String, SimOutcome> byIdempotencyKey = new LinkedHashMap<>();
        JsonNode byIdempotencyNode = node.get("byIdempotencyKey");
        if (byIdempotencyNode != null && byIdempotencyNode.isObject()) {
            byIdempotencyNode.fields().forEachRemaining(entry -> byIdempotencyKey.put(
                    entry.getKey(),
                    parseOutcome(entry.getValue(), objectMapper)
            ));
        }
        return new OperationSimulation(defaultOutcome, List.copyOf(sequence), Map.copyOf(byIdempotencyKey));
    }

    private static SimOutcome parseOutcome(JsonNode node, ObjectMapper objectMapper) {
        if (node == null || node.isNull()) {
            return new SimOutcome(true, Map.of(), null, 0L);
        }
        boolean ok = !node.has("ok") || node.get("ok").asBoolean(true);
        Object value = node.has("value")
                ? objectMapper.convertValue(node.get("value"), Object.class)
                : null;
        SimError error = null;
        if (node.has("error") && node.get("error").isObject()) {
            JsonNode errorNode = node.get("error");
            Map<String, Object> details = errorNode.has("details")
                    ? objectMapper.convertValue(errorNode.get("details"), Map.class)
                    : Map.of();
            error = new SimError(
                    textOrNull(errorNode.get("kind")),
                    textOrNull(errorNode.get("code")),
                    textOrNull(errorNode.get("message")),
                    details
            );
        }
        if (!ok && error == null) {
            error = new SimError("PERMANENT", "SIM_FAIL", "Simulated failure", Map.of());
        }
        long delayMs = node.has("delayMs") ? Math.max(0L, node.get("delayMs").asLong(0L)) : 0L;
        return new SimOutcome(ok, value, error, delayMs);
    }

    private static CapabilityErrorKind parseKind(String rawKind) {
        if (rawKind == null || rawKind.isBlank()) {
            return CapabilityErrorKind.PERMANENT;
        }
        try {
            return CapabilityErrorKind.valueOf(rawKind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return CapabilityErrorKind.PERMANENT;
        }
    }

    private static String normalizeTenant(Object rawTenantId) {
        if (rawTenantId == null) {
            return "default";
        }
        String tenant = String.valueOf(rawTenantId).trim();
        return tenant.isBlank() ? "default" : tenant;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private record ResolvedSimulation(
            OperationSimulation simulation,
            String attemptKey
    ) {
    }

    private record OperationSimulation(
            SimOutcome defaultOutcome,
            List<SimOutcome> sequence,
            Map<String, SimOutcome> byIdempotencyKey
    ) {
        static OperationSimulation empty() {
            return new OperationSimulation(null, List.of(), Map.of());
        }

        SimOutcome resolve(String idempotencyKey, int attempt) {
            if (idempotencyKey != null) {
                SimOutcome idempotencyOutcome = byIdempotencyKey.get(idempotencyKey);
                if (idempotencyOutcome != null) {
                    return idempotencyOutcome;
                }
            }
            if (!sequence.isEmpty()) {
                int index = Math.max(0, Math.min(attempt - 1, sequence.size() - 1));
                SimOutcome fromSequence = sequence.get(index);
                if (fromSequence != null) {
                    return fromSequence;
                }
            }
            if (defaultOutcome != null) {
                return defaultOutcome;
            }
            if (!sequence.isEmpty()) {
                return sequence.get(sequence.size() - 1);
            }
            return new SimOutcome(true, Map.of("simulated", true), null, 0L);
        }
    }

    private record SimOutcome(
            boolean ok,
            Object value,
            SimError error,
            long delayMs
    ) {
    }

    private record SimError(
            String kind,
            String code,
            String message,
            Map<String, Object> details
    ) {
    }

    private static void sleep(long delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
