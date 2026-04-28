package com.npdev.kernel;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime capability registry.
 * Binds capability names to concrete adapters and resolves them by type.
 */
public final class CapabilityRegistry {

    private static final String DEFAULT_ADAPTER_ID = "__default__";
    private final Map<String, Object> adaptersByBindingKey = new ConcurrentHashMap<>();
    private final Map<String, String> defaultAdapterByCapability = new ConcurrentHashMap<>();
    private final Map<String, String> capabilityTypeByCapability = new ConcurrentHashMap<>();
    private final CapabilityContractCatalog contractCatalog;

    public CapabilityRegistry() {
        this(CapabilityContractCatalog.withBuiltIns());
    }

    public CapabilityRegistry(CapabilityContractCatalog contractCatalog) {
        this.contractCatalog = Objects.requireNonNull(contractCatalog, "contractCatalog");
    }

    public CapabilityRegistry register(String capability, Object adapter) {
        return register(capability, null, DEFAULT_ADAPTER_ID, adapter);
    }

    public CapabilityRegistry register(String capability, String capabilityType, Object adapter) {
        return register(capability, capabilityType, DEFAULT_ADAPTER_ID, adapter);
    }

    public CapabilityRegistry register(
            String capability,
            String capabilityType,
            String adapterId,
            Object adapter
    ) {
        String key = normalize(capability);
        if (key.isBlank()) {
            throw new IllegalArgumentException("capability must be non-blank");
        }
        Objects.requireNonNull(adapter, "adapter");
        String normalizedAdapterId = normalizeAdapterId(adapterId);
        if (capabilityType != null && !capabilityType.isBlank()) {
            capabilityTypeByCapability.put(key, normalize(capabilityType));
        }
        adaptersByBindingKey.put(bindingKey(key, normalizedAdapterId), adapter);
        defaultAdapterByCapability.putIfAbsent(key, normalizedAdapterId);
        return this;
    }

    public <T> T resolve(String capability, Class<T> expectedType) {
        String key = normalize(capability);
        String defaultAdapterId = defaultAdapterByCapability.getOrDefault(key, DEFAULT_ADAPTER_ID);
        Object adapter = adaptersByBindingKey.get(bindingKey(key, defaultAdapterId));
        if (adapter == null) {
            throw new IllegalStateException("No adapter registered for capability: " + capability);
        }
        if (!expectedType.isInstance(adapter)) {
            throw new IllegalStateException(
                    "Adapter for capability " + capability + " is not of expected type " + expectedType.getName()
            );
        }
        return expectedType.cast(adapter);
    }

    public <T> T resolve(String capability, String adapterId, Class<T> expectedType) {
        String capabilityKey = normalize(capability);
        String normalizedAdapterId = normalizeAdapterId(adapterId);
        Object adapter = adaptersByBindingKey.get(bindingKey(capabilityKey, normalizedAdapterId));
        if (adapter == null) {
            throw new CapabilityBindingNotFoundException(capability, adapterId);
        }
        if (!expectedType.isInstance(adapter)) {
            throw new IllegalStateException(
                    "Adapter for capability " + capability + " and adapterId " + adapterId
                            + " is not of expected type " + expectedType.getName()
            );
        }
        return expectedType.cast(adapter);
    }

    public boolean has(String capability) {
        return defaultAdapterByCapability.containsKey(normalize(capability));
    }

    public boolean has(String capability, String adapterId) {
        return adaptersByBindingKey.containsKey(bindingKey(normalize(capability), normalizeAdapterId(adapterId)));
    }

    public CapabilityRegistry registerContract(CapabilityContract contract) {
        contractCatalog.register(contract);
        return this;
    }

    
public String debugDefaultAdapterId(String capability) {
    String key = normalize(capability);
    return defaultAdapterByCapability.getOrDefault(key, DEFAULT_ADAPTER_ID);
}

public Map<String, String> debugAdaptersFor(String capability) {
    String key = normalize(capability);
    Map<String, String> out = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : adaptersByBindingKey.entrySet()) {
        String k = entry.getKey();
        if (k != null && k.startsWith(key + "#")) {
            String adapterId = k.substring((key + "#").length());
            Object adapter = entry.getValue();
            out.put(adapterId, adapter == null ? "null" : adapter.getClass().getName());
        }
    }
    return out;
}

public Optional<CapabilityContract> findContract(String capability, String explicitType) {
        String explicit = normalize(explicitType);
        if (!explicit.isBlank()) {
            return contractCatalog.find(explicitType);
        }

        String key = normalize(capability);
        String boundType = capabilityTypeByCapability.getOrDefault(key, "");
        if (!boundType.isBlank()) {
            Optional<CapabilityContract> byBoundType = contractCatalog.find(boundType);
            if (byBoundType.isPresent()) {
                return byBoundType;
            }
        }

        Optional<CapabilityContract> byAlias = contractCatalog.find(capability);
        if (byAlias.isPresent()) {
            return byAlias;
        }

        if (!boundType.isBlank()) {
            return contractCatalog.find(boundType);
        }
        return Optional.empty();
    }

    private static String normalize(String capability) {
        return capability == null ? "" : capability.trim().toLowerCase();
    }

    private static String normalizeAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            return DEFAULT_ADAPTER_ID;
        }
        return adapterId.trim().toLowerCase();
    }

    private static String bindingKey(String capability, String adapterId) {
        return capability + "#" + adapterId;
    }
}
