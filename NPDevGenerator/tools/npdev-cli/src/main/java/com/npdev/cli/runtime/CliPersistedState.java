package com.npdev.cli.runtime;

import com.npdev.kernel.capability.CircuitBreakerState;
import com.npdev.kernel.capability.IdempotencyRecord;
import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.execution.FlowInstance;
import com.npdev.kernel.trace.FlowTrace;

import java.util.List;
import java.util.Map;

record CliPersistedState(
        List<EventEnvelope> events,
        List<FlowInstance> flowInstances,
        List<FlowTrace> traces,
        Map<String, String> correlationOwners,
        List<IdempotencyRecord> idempotencyRecords,
        Map<String, CircuitBreakerState> circuitStates
) {
    static CliPersistedState empty() {
        return new CliPersistedState(List.of(), List.of(), List.of(), Map.of(), List.of(), Map.of());
    }
}
