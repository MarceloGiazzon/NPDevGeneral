package com.npdev.kernel.ports;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;

import java.util.Map;

public interface CapabilityDispatcher {
    CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState);

    /**
     * Whether dispatching {@code call} needs a caller's OWN bounded-async wrapper (a thread-hop
     * with a wall-clock timeout, layered ABOVE whatever this dispatcher does internally) -- so a
     * caller can decide this WITHOUT actually invoking the call. True by default, fail-safe.
     *
     * REG-231: a dispatcher that can resolve the target adapter cheaply should answer by checking
     * {@link CapabilityAdapter#requiresBoundedAsyncDispatch()} on it; any resolution failure (a
     * missing binding, a blank adapterId, ...) should answer true, since {@link #invoke} itself is
     * the authoritative place to surface that as a real error -- this method exists only to let a
     * caller skip its own redundant thread-hop, never to duplicate {@link #invoke}'s own validation.
     */
    default boolean requiresBoundedAsyncDispatch(CapabilityCall call) {
        return true;
    }
}
