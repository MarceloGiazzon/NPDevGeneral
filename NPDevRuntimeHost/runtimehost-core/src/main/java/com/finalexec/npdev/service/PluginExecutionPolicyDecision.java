package com.finalexec.npdev.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PluginExecutionPolicyDecision(
        boolean allowed,
        String decisionCode,
        String message,
        String runtimeEnvironment,
        String pluginId,
        String capability,
        String operation,
        String adapterId
) {

    public PluginExecutionPolicyDecision {
        decisionCode = decisionCode == null ? "" : decisionCode.trim();
        message = message == null ? "" : message.trim();
        runtimeEnvironment = normalize(runtimeEnvironment);
        pluginId = normalize(pluginId);
        capability = normalize(capability);
        operation = normalize(operation);
        adapterId = normalize(adapterId);
    }

    public static PluginExecutionPolicyDecision allow(
            String runtimeEnvironment,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            String operation
    ) {
        Objects.requireNonNull(contribution, "contribution");
        return new PluginExecutionPolicyDecision(
                true,
                "PLUGIN_EXECUTION_ALLOWED",
                "Plugin execution allowed",
                runtimeEnvironment,
                contribution.pluginId(),
                contribution.capability(),
                operation,
                contribution.adapterId()
        );
    }

    public static PluginExecutionPolicyDecision deny(
            String decisionCode,
            String message,
            String runtimeEnvironment,
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            String operation
    ) {
        Objects.requireNonNull(contribution, "contribution");
        return new PluginExecutionPolicyDecision(
                false,
                decisionCode,
                message,
                runtimeEnvironment,
                contribution.pluginId(),
                contribution.capability(),
                operation,
                contribution.adapterId()
        );
    }

    public Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("allowed", allowed);
        summary.put("decisionCode", decisionCode);
        summary.put("message", message);
        summary.put("runtimeEnvironment", runtimeEnvironment);
        summary.put("pluginId", pluginId);
        summary.put("capability", capability);
        summary.put("operation", operation);
        summary.put("adapterId", adapterId);
        return Map.copyOf(summary);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
