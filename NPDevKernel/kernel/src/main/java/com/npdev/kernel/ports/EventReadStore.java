package com.npdev.kernel.ports;

import com.npdev.kernel.events.EventEnvelope;

import java.util.List;
import java.util.Optional;

public interface EventReadStore {
    default List<EventEnvelope> readByCorrelation(String correlationId, String tenantId) {
        return List.of();
    }

    default List<EventEnvelope> readByEventName(String eventName, String tenantId) {
        return List.of();
    }

    default List<EventEnvelope> findByCorrelationId(String tenantId, String correlationId, int limit, int offset) {
        return List.of();
    }

    default List<EventEnvelope> findByEventName(String tenantId, String eventName, int limit, int offset) {
        return List.of();
    }

    default Optional<EventEnvelope> findByEventId(String tenantId, String eventId) {
        return Optional.empty();
    }
}
