package com.npdev.kernel.ports;

import com.npdev.kernel.events.EventEnvelope;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface EventStore extends EventReadStore {
    void append(EventEnvelope event);

    default Optional<EventEnvelope> findFirst(String eventName, String correlationId) {
        return findFirst(eventName, correlationId, null);
    }

    default Optional<EventEnvelope> findFirst(String eventName, String correlationId, String tenantId) {
        return read(eventName, correlationId, tenantId).stream()
                .sorted(Comparator
                        .comparingLong(EventEnvelope::timestampEpochMs)
                        .thenComparing(EventEnvelope::eventId))
                .findFirst();
    }

    List<EventEnvelope> readByCorrelation(String correlationId);

    default List<EventEnvelope> readByCorrelation(String correlationId, String tenantId) {
        String scopedTenantId = normalizeTenant(tenantId);
        return readByCorrelation(correlationId).stream()
                .filter(event -> scopedTenantId == null || scopedTenantId.equals(normalizeTenant(event.tenantId())))
                .toList();
    }

    List<EventEnvelope> readByEventName(String eventName);

    default List<EventEnvelope> readByEventName(String eventName, String tenantId) {
        String scopedTenantId = normalizeTenant(tenantId);
        return readByEventName(eventName).stream()
                .filter(event -> scopedTenantId == null || scopedTenantId.equals(normalizeTenant(event.tenantId())))
                .toList();
    }

    default List<EventEnvelope> read(String eventName, String correlationId) {
        return read(eventName, correlationId, null);
    }

    default List<EventEnvelope> read(String eventName, String correlationId, String tenantId) {
        if (eventName == null || eventName.isBlank() || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        String scopedTenantId = normalizeTenant(tenantId);
        return readByCorrelation(correlationId, scopedTenantId).stream()
                .filter(event -> eventName.equals(event.eventName()))
                .toList();
    }

    private static String normalizeTenant(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        String trimmed = tenantId.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
