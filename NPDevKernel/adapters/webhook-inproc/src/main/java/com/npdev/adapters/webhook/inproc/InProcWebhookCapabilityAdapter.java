package com.npdev.adapters.webhook.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class InProcWebhookCapabilityAdapter implements CapabilityAdapter {

    private final List<Map<String, Object>> deliveries = new ArrayList<>();

    @Override
    public String adapterId() {
        return "webhook-inproc";
    }

    @Override
    public String capability() {
        return "webhook";
    }

    @Override
    public String capabilityType() {
        return "WebhookCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if (!"post".equals(call.operation())) {
            return CapabilityResult.failure(
                    "WEBHOOK_OPERATION_UNSUPPORTED",
                    "Unsupported webhook operation: " + call.operation(),
                    com.npdev.kernel.CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        }
        Object payload = call.args().isEmpty() ? Map.of() : call.args().get(0);
        return CapabilityResult.success(post(payload));
    }

    public Object post(Object payload) {
        Map<String, Object> request = normalizePayload(payload);
        request.putIfAbsent("requestId", UUID.randomUUID().toString());
        request.putIfAbsent("status", "accepted");
        request.put("postedAt", Instant.EPOCH.toString());
        deliveries.add(Map.copyOf(request));
        return Map.copyOf(request);
    }

    public List<Map<String, Object>> deliveries() {
        return List.copyOf(deliveries);
    }

    private static Map<String, Object> normalizePayload(Object payload) {
        if (payload == null) {
            return new LinkedHashMap<>();
        }
        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        return new LinkedHashMap<>(Map.of("value", payload));
    }
}
