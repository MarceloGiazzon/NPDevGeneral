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

    /**
     * Whether a caller's own bounded-async dispatch wrapper (a worker-pool thread-hop with a
     * wall-clock timeout, layered ABOVE this adapter's {@link #invoke}) is still needed for this
     * adapter's calls. True by default -- an adapter must explicitly opt out.
     *
     * REG-231: an adapter that runs its own invoke() synchronously, on whatever thread calls it,
     * can return false here so a caller that ALSO thread-hops for its own reasons (timeout
     * containment, thread-local propagation, ...) can skip that hop too when it isn't needed --
     * letting this adapter's own work stay on the caller's thread and see whatever ambient state
     * (e.g. a Spring-managed transaction) that thread is running under.
     */
    default boolean requiresBoundedAsyncDispatch() {
        return true;
    }
}
