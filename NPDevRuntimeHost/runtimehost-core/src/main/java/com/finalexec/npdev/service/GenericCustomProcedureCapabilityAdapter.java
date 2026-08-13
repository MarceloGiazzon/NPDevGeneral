package com.finalexec.npdev.service;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Generic in-process adapter for the custom procedure/plugin path.
 *
 * This adapter is intentionally generic. It proves that an externally declared
 * custom capability can be admitted through manifests/packages and executed
 * through the governed plugin runtime path without hardcoding business meaning
 * into NPDev core.
 */
public final class GenericCustomProcedureCapabilityAdapter implements CapabilityAdapter {

    @Override
    public String adapterId() {
        return "plugin:custom-procedure";
    }

    @Override
    public String capability() {
        return "customExtension";
    }

    @Override
    public String capabilityType() {
        return "CustomProcedureCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        String operation = call.operation() == null ? "" : call.operation().trim();
        if (!operation.isEmpty() && !"run".equalsIgnoreCase(operation)) {
            return CapabilityResult.failure(
                    "CUSTOM_PROCEDURE_OPERATION_UNSUPPORTED",
                    "Unsupported custom procedure operation: " + call.operation(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation(), "adapterId", adapterId())
            );
        }

        Map<String, Object> payload = normalizePayload(call.input());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("executionId", UUID.randomUUID().toString());
        output.put("status", "completed");
        output.put("adapterId", adapterId());
        output.put("capability", capability());
        output.put("capabilityType", capabilityType());
        output.put("operation", operation.isEmpty() ? "run" : operation);
        output.put("executedAt", Instant.EPOCH.toString());
        output.put("input", payload);
        if (contextState != null && !contextState.isEmpty()) {
            output.put("contextState", Map.copyOf(contextState));
        }
        return CapabilityResult.success(Map.copyOf(output));
    }

    private static Map<String, Object> normalizePayload(Object input) {
        if (input == null) {
            return new LinkedHashMap<>();
        }
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        return new LinkedHashMap<>(Map.of("value", input));
    }
}
