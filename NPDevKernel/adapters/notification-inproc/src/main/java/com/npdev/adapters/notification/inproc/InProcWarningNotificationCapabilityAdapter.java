package com.npdev.adapters.notification.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alternate sealed notification plugin used to prove binding-driven adapter swapping.
 */
public final class InProcWarningNotificationCapabilityAdapter implements CapabilityAdapter {

    private final List<Map<String, Object>> deliveries = new ArrayList<>();

    @Override
    public String adapterId() {
        return "notification-warning-inproc";
    }

    @Override
    public String capability() {
        return "notification";
    }

    @Override
    public String capabilityType() {
        return "NotificationCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if (!"send".equals(call.operation())) {
            return CapabilityResult.failure(
                    "NOTIFICATION_OPERATION_UNSUPPORTED",
                    "Unsupported notification operation: " + call.operation(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        }
        Object payload = call.args().isEmpty() ? Map.of() : call.args().get(0);
        return CapabilityResult.success(send(payload));
    }

    public Object send(Object payload) {
        Map<String, Object> message = normalizePayload(payload);
        message.putIfAbsent("deliveryId", UUID.randomUUID().toString());
        message.putIfAbsent("status", "warning");
        message.putIfAbsent("channel", inferChannel(message));
        message.putIfAbsent("adapterId", adapterId());
        message.putIfAbsent("pluginProfile", "warning");
        message.put("sentAt", Instant.EPOCH.toString());
        deliveries.add(Map.copyOf(message));
        return Map.copyOf(message);
    }

    public List<Map<String, Object>> deliveries() {
        return List.copyOf(deliveries);
    }

    private static String inferChannel(Map<String, Object> message) {
        if (message.containsKey("email") || message.containsKey("employeeEmail")) {
            return "email-warning";
        }
        return "notification-warning";
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
