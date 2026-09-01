package com.finalexec.npdev.service;

import com.npdev.kernel.CapabilityCall;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PluginExecutionPolicyEvaluator {

    private final Environment environment;
    private final String runtimeEnvironmentOverride;
    private final Set<String> deniedPluginIds;
    private final Set<String> deniedAdapterIds;
    private final Set<String> allowedPluginIds;
    private final Set<String> allowedAdapterIds;

    public PluginExecutionPolicyEvaluator(
            Environment environment,
            String runtimeEnvironmentOverride,
            String deniedPluginIds,
            String deniedAdapterIds,
            String allowedPluginIds,
            String allowedAdapterIds
    ) {
        this.environment = environment;
        this.runtimeEnvironmentOverride = runtimeEnvironmentOverride;
        this.deniedPluginIds = parseList(deniedPluginIds);
        this.deniedAdapterIds = parseList(deniedAdapterIds);
        this.allowedPluginIds = parseList(allowedPluginIds);
        this.allowedAdapterIds = parseList(allowedAdapterIds);
    }

    public PluginExecutionPolicyDecision evaluate(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution,
            CapabilityCall call
    ) {
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(call, "call");

        String runtimeEnvironment = activeEnvironment();
        if (deniedPluginIds.contains(normalize(contribution.pluginId()))) {
            return PluginExecutionPolicyDecision.deny(
                    "PLUGIN_POLICY_DENY_PLUGIN_ID",
                    "Plugin '%s' is denied by runtime policy".formatted(contribution.pluginId()),
                    runtimeEnvironment,
                    contribution,
                    call.operation()
            );
        }
        if (deniedAdapterIds.contains(normalize(contribution.adapterId()))) {
            return PluginExecutionPolicyDecision.deny(
                    "PLUGIN_POLICY_DENY_ADAPTER_ID",
                    "Adapter '%s' is denied by runtime policy".formatted(contribution.adapterId()),
                    runtimeEnvironment,
                    contribution,
                    call.operation()
            );
        }
        if (!allowedPluginIds.isEmpty() && !allowedPluginIds.contains(normalize(contribution.pluginId()))) {
            return PluginExecutionPolicyDecision.deny(
                    "PLUGIN_POLICY_PLUGIN_NOT_ALLOWED",
                    "Plugin '%s' is not in the runtime allowlist".formatted(contribution.pluginId()),
                    runtimeEnvironment,
                    contribution,
                    call.operation()
            );
        }
        if (!allowedAdapterIds.isEmpty() && !allowedAdapterIds.contains(normalize(contribution.adapterId()))) {
            return PluginExecutionPolicyDecision.deny(
                    "PLUGIN_POLICY_ADAPTER_NOT_ALLOWED",
                    "Adapter '%s' is not in the runtime allowlist".formatted(contribution.adapterId()),
                    runtimeEnvironment,
                    contribution,
                    call.operation()
            );
        }
        return PluginExecutionPolicyDecision.allow(runtimeEnvironment, contribution, call.operation());
    }

    /**
     * The Model B callback allowlist gate (SEC-3,
     * docs/architecture/PLUGIN_PROCESS_ISOLATION_DESIGN.md section 1): a running child process may ask
     * the host to perform capability calls on its behalf over the IPC channel, but only capabilities its
     * OWN manifest entry ({@code originalContribution.pluginId()}) declares it needs -- reusing this
     * evaluator's existing allow/deny decision shape rather than inventing a second policy model.
     * Deliberately NOT a reuse of {@code TrustedSourceBytecodeInspector}: that inspector scans compiled
     * bytecode for direct forbidden references at admission time and has no visibility into what a
     * running child asks the host to do over an IPC channel that doesn't exist at admission time.
     */
    public PluginExecutionPolicyDecision evaluateCallback(
            RuntimePluginAdapterRegistry.RegisteredAdapterContribution originalContribution,
            RuntimePluginAdapterRegistry registry,
            String callbackCapability,
            String callbackOperation
    ) {
        Objects.requireNonNull(originalContribution, "originalContribution");
        Objects.requireNonNull(registry, "registry");

        String runtimeEnvironment = activeEnvironment();
        String normalizedCapability = normalize(callbackCapability);
        String normalizedOperation = normalize(callbackOperation);

        if (normalizedCapability.isBlank()) {
            return new PluginExecutionPolicyDecision(
                    false,
                    "PLUGIN_CALLBACK_MISSING_CAPABILITY",
                    "Plugin IPC callback did not name a capability",
                    runtimeEnvironment,
                    originalContribution.pluginId(),
                    normalizedCapability,
                    normalizedOperation,
                    ""
            );
        }
        if (deniedPluginIds.contains(normalize(originalContribution.pluginId()))) {
            return new PluginExecutionPolicyDecision(
                    false,
                    "PLUGIN_POLICY_DENY_PLUGIN_ID",
                    "Plugin '%s' is denied by runtime policy".formatted(originalContribution.pluginId()),
                    runtimeEnvironment,
                    originalContribution.pluginId(),
                    normalizedCapability,
                    normalizedOperation,
                    ""
            );
        }

        RuntimePluginAdapterRegistry.RegisteredAdapterContribution declared = registry.findDeclaredCallback(
                originalContribution.pluginId(), normalizedCapability, normalizedOperation
        );
        if (declared == null) {
            return new PluginExecutionPolicyDecision(
                    false,
                    "PLUGIN_CALLBACK_NOT_DECLARED",
                    "Plugin '%s' is not declared to call back into capability '%s' operation '%s'"
                            .formatted(originalContribution.pluginId(), callbackCapability, callbackOperation),
                    runtimeEnvironment,
                    originalContribution.pluginId(),
                    normalizedCapability,
                    normalizedOperation,
                    ""
            );
        }
        return new PluginExecutionPolicyDecision(
                true,
                "PLUGIN_CALLBACK_ALLOWED",
                "Plugin callback allowed",
                runtimeEnvironment,
                originalContribution.pluginId(),
                normalizedCapability,
                normalizedOperation,
                declared.adapterId()
        );
    }

    public Map<String, Object> policySummary() {
        return Map.of(
                "runtimeEnvironment", runtimeEnvironment(),
                "deniedPluginIds", deniedPluginIds,
                "deniedAdapterIds", deniedAdapterIds,
                "allowedPluginIds", allowedPluginIds,
                "allowedAdapterIds", allowedAdapterIds
        );
    }

    public String runtimeEnvironment() {
        return activeEnvironment();
    }

    private String activeEnvironment() {
        if (runtimeEnvironmentOverride != null && !runtimeEnvironmentOverride.isBlank()) {
            return normalize(runtimeEnvironmentOverride);
        }
        if (environment == null) {
            return "default";
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles == null || activeProfiles.length == 0) {
            return "default";
        }
        String[] preferredOrder = {"dev", "test", "stage", "prod", "default"};
        for (String preferred : preferredOrder) {
            for (String profile : activeProfiles) {
                if (profile != null && preferred.equalsIgnoreCase(profile.trim())) {
                    return preferred;
                }
            }
        }
        for (String profile : activeProfiles) {
            String normalized = normalize(profile);
            if (!normalized.isBlank() && !"postgres".equals(normalized)) {
                return normalized;
            }
        }
        return "default";
    }

    private static Set<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(PluginExecutionPolicyEvaluator::normalize)
                .filter(value -> !value.isBlank())
                .forEach(values::add);
        return Set.copyOf(values);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
