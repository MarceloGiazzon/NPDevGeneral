package com.npdev.adapters.events.inproc;

import com.npdev.kernel.events.EventEnvelope;
import com.npdev.kernel.events.EventMetaSummary;
import com.npdev.kernel.ports.EventMetaStore;
import com.npdev.kernel.ports.EventStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class InProcEventStore implements EventStore, EventMetaStore {
    private static final Comparator<EventEnvelope> ASC_EVENT_ORDER =
            Comparator.comparingLong(EventEnvelope::timestampEpochMs).thenComparing(EventEnvelope::eventId);

    private final Map<String, CopyOnWriteArrayList<EventEnvelope>> eventsByCorrelation = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<EventEnvelope>> eventsByName = new ConcurrentHashMap<>();
    private final Map<String, EventEnvelope> eventsById = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<EventEnvelope>> appendListeners = new CopyOnWriteArrayList<>();

    @Override
    public void append(EventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        eventsByCorrelation
                .computeIfAbsent(envelope.correlationId(), key -> new CopyOnWriteArrayList<>())
                .add(envelope);
        eventsByName
                .computeIfAbsent(envelope.eventName(), key -> new CopyOnWriteArrayList<>())
                .add(envelope);
        eventsById.put(envelope.eventId(), envelope);
        for (Consumer<EventEnvelope> listener : appendListeners) {
            listener.accept(envelope);
        }
    }

    public void registerAppendListener(Consumer<EventEnvelope> listener) {
        if (listener == null) {
            return;
        }
        appendListeners.add(listener);
    }

    @Override
    public Optional<EventEnvelope> findFirst(String eventName, String correlationId) {
        return findFirst(eventName, correlationId, null);
    }

    @Override
    public Optional<EventEnvelope> findFirst(String eventName, String correlationId, String tenantId) {
        return read(eventName, correlationId, tenantId).stream()
                .sorted(Comparator.comparingLong(EventEnvelope::timestampEpochMs).thenComparing(EventEnvelope::eventId))
                .findFirst();
    }

    @Override
    public List<EventEnvelope> readByCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        return eventsByCorrelation.getOrDefault(correlationId, new CopyOnWriteArrayList<>()).stream()
                .sorted(ASC_EVENT_ORDER)
                .toList();
    }

    public List<EventEnvelope> snapshotEvents() {
        return eventsById.values().stream()
                .sorted(ASC_EVENT_ORDER)
                .toList();
    }

    @Override
    public List<EventEnvelope> readByCorrelation(String correlationId, String tenantId) {
        String scopedTenantId = normalizeTenant(tenantId);
        return readByCorrelation(correlationId).stream()
                .filter(event -> scopedTenantId == null || scopedTenantId.equals(normalizeTenant(event.tenantId())))
                .sorted(ASC_EVENT_ORDER)
                .toList();
    }

    @Override
    public List<EventEnvelope> readByEventName(String eventName) {
        if (eventName == null || eventName.isBlank()) {
            return List.of();
        }
        return eventsByName.getOrDefault(eventName, new CopyOnWriteArrayList<>()).stream()
                .sorted(ASC_EVENT_ORDER)
                .toList();
    }

    @Override
    public List<EventEnvelope> readByEventName(String eventName, String tenantId) {
        String scopedTenantId = normalizeTenant(tenantId);
        return readByEventName(eventName).stream()
                .filter(event -> scopedTenantId == null || scopedTenantId.equals(normalizeTenant(event.tenantId())))
                .sorted(ASC_EVENT_ORDER)
                .toList();
    }

    @Override
    public List<EventEnvelope> read(String eventName, String correlationId) {
        return read(eventName, correlationId, null);
    }

    @Override
    public List<EventEnvelope> read(String eventName, String correlationId, String tenantId) {
        if (eventName == null || eventName.isBlank() || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        String scopedTenantId = normalizeTenant(tenantId);
        List<EventEnvelope> byCorrelation = readByCorrelation(correlationId);
        if (byCorrelation.isEmpty()) {
            return List.of();
        }
        List<EventEnvelope> out = new ArrayList<>();
        for (EventEnvelope envelope : byCorrelation) {
            if (eventName.equals(envelope.eventName())
                    && (scopedTenantId == null || scopedTenantId.equals(normalizeTenant(envelope.tenantId())))) {
                out.add(envelope);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<EventEnvelope> findByCorrelationId(String tenantId, String correlationId, int limit, int offset) {
        if (correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null) {
            return List.of();
        }
        return paginate(readByCorrelation(correlationId, scopedTenantId).stream()
                .filter(event -> scopedTenantId.equals(normalizeTenant(event.tenantId())))
                .sorted(ASC_EVENT_ORDER)
                .toList(), limit, offset);
    }

    @Override
    public List<EventEnvelope> findByEventName(String tenantId, String eventName, int limit, int offset) {
        if (eventName == null || eventName.isBlank()) {
            return List.of();
        }
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null) {
            return List.of();
        }
        return paginate(readByEventName(eventName, scopedTenantId).stream()
                .filter(event -> scopedTenantId.equals(normalizeTenant(event.tenantId())))
                .sorted(Comparator.comparingLong(EventEnvelope::timestampEpochMs).reversed()
                        .thenComparing(EventEnvelope::eventId, Comparator.reverseOrder()))
                .toList(), limit, offset);
    }

    @Override
    public Optional<EventEnvelope> findByEventId(String tenantId, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null) {
            return Optional.empty();
        }
        EventEnvelope envelope = eventsById.get(eventId);
        if (envelope == null) {
            return Optional.empty();
        }
        if (!scopedTenantId.equals(envelope.tenantId())) {
            return Optional.empty();
        }
        return Optional.of(envelope);
    }

    @Override
    public List<EventMetaSummary> listByCorrelation(String tenantId, String correlationId, int limit, int offset) {
        String scopedTenantId = normalizeTenant(tenantId);
        if (scopedTenantId == null || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        List<EventMetaSummary> summaries = readByCorrelation(correlationId, scopedTenantId).stream()
                .filter(event -> scopedTenantId.equals(normalizeTenant(event.tenantId())))
                .sorted(ASC_EVENT_ORDER)
                .map(InProcEventStore::toMetaSummary)
                .toList();
        return paginateSummaries(summaries, limit, offset);
    }

    private static String normalizeTenant(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        String trimmed = tenantId.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static List<EventEnvelope> paginate(List<EventEnvelope> source, int limit, int offset) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 1000);
        int effectiveOffset = Math.max(offset, 0);
        int fromIndex = Math.min(effectiveOffset, source.size());
        int toIndex = Math.min(fromIndex + effectiveLimit, source.size());
        return List.copyOf(source.subList(fromIndex, toIndex));
    }

    private static List<EventMetaSummary> paginateSummaries(List<EventMetaSummary> source, int limit, int offset) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 1000);
        int effectiveOffset = Math.max(offset, 0);
        int fromIndex = Math.min(effectiveOffset, source.size());
        int toIndex = Math.min(fromIndex + effectiveLimit, source.size());
        return List.copyOf(source.subList(fromIndex, toIndex));
    }

    private static EventMetaSummary toMetaSummary(EventEnvelope event) {
        return new EventMetaSummary(
                event.eventId(),
                event.tenantId(),
                event.correlationId(),
                event.eventName(),
                event.flowName(),
                event.stepIndex(),
                event.timestampEpochMs()
        );
    }
}
