package com.npdev.kernel.capabilities;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Capability binding resolver that applies RuntimeOverridesManifest on top of the static binding manifest.
 * The manifest describes available adapters; the overrides select which adapterId to use at runtime.
 */
public final class RuntimeOverrideCapabilityBindingResolver implements CapabilityBindingResolver {

    private final CapabilityBindingManifest manifest;
    private final RuntimeOverridesManifest overrides;
    private final StaticCapabilityBindingResolver fallback;

    public RuntimeOverrideCapabilityBindingResolver(
            CapabilityBindingManifest manifest,
            RuntimeOverridesManifest overrides
    ) {
        this.manifest = Objects.requireNonNull(manifest, "manifest").normalized();
        this.overrides = overrides == null ? RuntimeOverridesManifest.empty() : overrides;
        this.fallback = new StaticCapabilityBindingResolver(this.manifest);
    }

    @Override
    public Optional<CapabilityBindingDescriptor> resolve(
            String capability,
            String capabilityType,
            String tenantId,
            String environment
    ) {
        String overrideAdapterId = findOverrideAdapterId(capability, capabilityType, tenantId, environment);
        if (overrideAdapterId != null && !overrideAdapterId.isBlank()) {
            Optional<CapabilityBindingDescriptor> overridden = findBindingByAdapterId(
                    manifest.bindings(),
                    capability,
                    capabilityType,
                    tenantId,
                    environment,
                    overrideAdapterId
            );
            if (overridden.isPresent()) {
                return overridden;
            }
        }
        return fallback.resolve(capability, capabilityType, tenantId, environment);
    }

    private String findOverrideAdapterId(String capability, String capabilityType, String tenantId, String environment) {
        List<RuntimeOverridesManifest.CapabilityOverride> rules = overrides.capabilityOverrides();
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        RuntimeOverridesManifest.CapabilityOverride best = null;
        int bestScore = -1;

        for (RuntimeOverridesManifest.CapabilityOverride rule : rules) {
            if (!rule.matches(capability, capabilityType, tenantId, environment)) {
                continue;
            }

            int score = 0;
            if (!rule.capabilityType().isBlank()) score += 1;
            if (!rule.tenantId().isBlank()) score += 2;
            if (!rule.environment().isBlank()) score += 4;

            if (score > bestScore) {
                bestScore = score;
                best = rule;
            }
        }
        return best == null ? null : best.useAdapterId();
    }

    private Optional<CapabilityBindingDescriptor> findBindingByAdapterId(
            List<CapabilityBindingDescriptor> bindings,
            String capability,
            String capabilityType,
            String tenantId,
            String environment,
            String adapterId
    ) {
        String cap = normalizeRequired(capability);
        String type = normalizeOptional(capabilityType);
        String tenant = normalizeOptional(tenantId);
        String env = normalizeOptional(environment);
        String adapter = normalizeRequired(adapterId);

        for (CapabilityBindingDescriptor b : bindings) {
            if (!b.capability().equals(cap)) continue;
            if (!b.adapterId().equals(adapter)) continue;
            if (!b.matches(cap, type, tenant, env)) continue;
            return Optional.of(b);
        }
        return Optional.empty();
    }

    private static String normalizeRequired(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
