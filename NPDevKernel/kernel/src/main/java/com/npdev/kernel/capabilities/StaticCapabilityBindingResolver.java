package com.npdev.kernel.capabilities;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic resolver for capability binding manifests.
 *
 * Resolution precedence (MOST specific -> LEAST specific):
 * 1. capability + type + tenant + environment
 * 2. capability + type + environment          (binding tenant must be blank)
 * 3. capability + environment                 (binding type+tenant must be blank)
 * 4. capability + type                        (binding env+tenant must be blank)
 * 5. capability only                          (binding type+env+tenant must be blank)
 *
 * Important: "blank" in a binding means "not specified" and should NOT win over a more specific binding.
 */
public final class StaticCapabilityBindingResolver implements CapabilityBindingResolver {

    private final CapabilityBindingManifest manifest;

    public StaticCapabilityBindingResolver(CapabilityBindingManifest manifest) {
        this.manifest = Objects.requireNonNull(manifest, "manifest").normalized();
    }

    @Override
    public Optional<CapabilityBindingDescriptor> resolve(
            String capability,
            String capabilityType,
            String tenantId,
            String environment
    ) {
        String cap = normalizeRequired(capability, "capability");
        String type = normalizeOptional(capabilityType);
        String tenant = normalizeOptional(tenantId);
        String env = normalizeOptional(environment);

        List<CapabilityBindingDescriptor> bindings = manifest.bindings();

        // 1) exact cap+type+tenant+env
        Optional<CapabilityBindingDescriptor> exact = findExact(bindings, cap, type, tenant, env);
        if (exact.isPresent()) return exact;

        // 2) cap+type+env (binding tenant blank)
        Optional<CapabilityBindingDescriptor> typeAndEnv = findCapTypeEnv(bindings, cap, type, env);
        if (typeAndEnv.isPresent()) return typeAndEnv;

        // 3) cap+env (binding type blank, tenant blank)
        Optional<CapabilityBindingDescriptor> envOnly = findCapEnv(bindings, cap, env);
        if (envOnly.isPresent()) return envOnly;

        // 4) cap+type (binding env blank, tenant blank)
        Optional<CapabilityBindingDescriptor> typeOnly = findCapType(bindings, cap, type);
        if (typeOnly.isPresent()) return typeOnly;

        // 5) cap only (binding type blank, env blank, tenant blank)
        return findCapOnly(bindings, cap);
    }

    private static Optional<CapabilityBindingDescriptor> findExact(
            List<CapabilityBindingDescriptor> bindings,
            String cap,
            String type,
            String tenant,
            String env
    ) {
        for (CapabilityBindingDescriptor b : bindings) {
            if (b.capability().equals(cap)
                    && b.capabilityType().equals(type)
                    && b.tenantId().equals(tenant)
                    && b.environment().equals(env)) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityBindingDescriptor> findCapTypeEnv(
            List<CapabilityBindingDescriptor> bindings,
            String cap,
            String type,
            String env
    ) {
        for (CapabilityBindingDescriptor b : bindings) {
            if (b.capability().equals(cap)
                    && b.capabilityType().equals(type)
                    && b.environment().equals(env)
                    && b.tenantId().isBlank()) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityBindingDescriptor> findCapEnv(
            List<CapabilityBindingDescriptor> bindings,
            String cap,
            String env
    ) {
        for (CapabilityBindingDescriptor b : bindings) {
            if (b.capability().equals(cap)
                    && b.environment().equals(env)
                    && b.capabilityType().isBlank()
                    && b.tenantId().isBlank()) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityBindingDescriptor> findCapType(
            List<CapabilityBindingDescriptor> bindings,
            String cap,
            String type
    ) {
        for (CapabilityBindingDescriptor b : bindings) {
            if (b.capability().equals(cap)
                    && b.capabilityType().equals(type)
                    && b.environment().isBlank()
                    && b.tenantId().isBlank()) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityBindingDescriptor> findCapOnly(
            List<CapabilityBindingDescriptor> bindings,
            String cap
    ) {
        for (CapabilityBindingDescriptor b : bindings) {
            if (b.capability().equals(cap)
                    && b.capabilityType().isBlank()
                    && b.environment().isBlank()
                    && b.tenantId().isBlank()) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    private static String normalizeRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value.trim().toLowerCase();
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}