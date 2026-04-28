package com.npdev.adapters.circuit.inproc;

import com.npdev.kernel.capability.CapabilityOpKey;
import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.CircuitBreakerStateSummary;
import com.npdev.kernel.capability.CircuitState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InProcCircuitBreakerStateStoreTest {
    @Test
    void getPutResetLifecycleIsDeterministic() {
        InProcCircuitBreakerStateStore store = new InProcCircuitBreakerStateStore();
        CapabilityOpKey key = new CapabilityOpKey("tenant-a", "persistence", "save");

        assertEquals(CircuitState.CLOSED, store.get(key).state());

        CircuitBreakerState open = new CircuitBreakerState(CircuitState.OPEN, 5, 1000L, 1000L, 30000L, 0);
        store.put(key, open);

        CircuitBreakerState loaded = store.get(key);
        assertEquals(CircuitState.OPEN, loaded.state());
        assertEquals(5, loaded.consecutiveFailures());

        List<CircuitBreakerStateSummary> summaries = store.listStates("tenant-a", "persistence", "save", 10, 0);
        assertEquals(1, summaries.size());
        assertEquals(CircuitState.OPEN, summaries.get(0).state());

        store.reset(key);
        assertEquals(CircuitState.CLOSED, store.get(key).state());
    }
}
