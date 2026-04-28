package com.npdev.kernel.ports;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;

import java.util.Map;

/**
 * Optional SDK-level adapter contract for capability implementations.
 *
 * The kernel does not require adapters to implement this interface because
 * `RegistryCapabilityDispatcher` currently supports reflective operation dispatch.
 *
 * This port exists to allow future adapters to opt into a stable, typed contract.
 */
public interface CapabilityAdapter {

    /**
     * Stable adapter identifier used by runtime binding manifests.
     */
    String adapterId();

    /**
     * Capability contract name this adapter implements.
     */
    String capability();

    /**
     * Optional capability type/alias.
     */
    default String capabilityType() {
        return capability();
    }

    /**
     * Invoke a capability call using the adapter SDK contract.
     */
    CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState);
}
